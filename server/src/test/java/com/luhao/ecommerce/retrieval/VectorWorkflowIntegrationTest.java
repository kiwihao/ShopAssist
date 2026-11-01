package com.luhao.ecommerce.retrieval;

import com.luhao.ecommerce.model.application.EmbeddingClient;
import com.luhao.ecommerce.retrieval.infrastructure.VectorRepository;
import com.luhao.ecommerce.shared.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** 真实 HTTP + PostgreSQL/pgvector，模型被替换；不证明智谱召回效果。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfEnvironmentVariable(named = "DEMO_INTEGRATION_TESTS", matches = "true")
class VectorWorkflowIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired VectorRepository vectors;
    @MockitoBean EmbeddingClient embedding;
    @Value("${spring.datasource.url}") String databaseUrl;

    @Test void productIsolationVersionSwitchFailureRecoveryAndSpaceIsolation() throws Exception {
        assertThat(databaseUrl).as("Use a dedicated test database").contains("_test");
        when(embedding.space()).thenReturn("integration-fake-space");
        when(embedding.model()).thenReturn("integration-fake-model");
        useFakeVectors();
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        String aProduct = "IT-A-" + suffix, bProduct = "IT-B-" + suffix;
        for (String id : List.of(aProduct, bProduct)) {
            jdbc.update("INSERT INTO products (product_id,name,category,price_cny,laptop_height_cm,laptop_width_cm,laptop_depth_cm,status) SELECT ?,name,category,price_cny,laptop_height_cm,laptop_width_cm,laptop_depth_cm,status FROM products WHERE product_id='BP-A'", id);
        }
        String filename = "integration-" + UUID.randomUUID() + ".md";
        UUID a = upload(aProduct, filename, 1, "# A\n## 尺寸\n36 cm\n## 清洗\n禁止机洗");
        UUID b = upload(bProduct, filename, 1, "# B\n## 尺寸\n32 cm\n## 清洗\n可以手洗");
        await(a, "PARSED"); await(b, "PARSED");
        index(a); index(b); await(a, "READY"); await(b, "READY");
        var found = search(aProduct);
        assertThat(found).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("product_id")).isEqualTo(aProduct);
            assertThat(row.get("version")).isEqualTo(1);
            assertThat(((Number) row.get("similarity")).doubleValue()).isCloseTo(1, within(0.0001));
        });

        UUID newer = upload(aProduct, filename, 2, "# A 新版\n## 尺寸\n新尺寸 37 cm");
        await(newer, "PARSED");
        when(embedding.embed(anyList())).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "MODEL_HTTP_ERROR", "test failure"));
        index(newer); await(newer, "INDEX_FAILED");
        assertThat(job(newer).get("error_code")).isEqualTo("MODEL_HTTP_ERROR");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM chunk_vectors cv JOIN chunks c USING(chunk_id)
                JOIN ingestion_jobs j ON j.version_id=c.version_id WHERE j.job_id=?
                """, Integer.class, newer)).isZero();
        useFakeVectors();
        assertThat(search(aProduct)).hasSize(2).allSatisfy(row -> assertThat(row.get("version")).isEqualTo(1));
        index(newer); await(newer, "READY");
        assertThat(search(aProduct)).hasSize(1).allSatisfy(row -> assertThat(row.get("version")).isEqualTo(2));
        assertThat(search(bProduct)).hasSize(2).allSatisfy(row -> assertThat(row.get("product_id")).isEqualTo(bProduct));

        // 模拟旧版晚完成，不能将有效版本退回。
        UUID oldVersion = UUID.fromString(job(a).get("version_id").toString());
        var oldChunks = vectors.chunks(oldVersion);
        vectors.complete(a, oldVersion, oldChunks, fakeVectors(oldChunks.size()), embedding.space());
        assertThat(search(aProduct)).hasSize(1).allSatisfy(row -> assertThat(row.get("version")).isEqualTo(2));

        when(embedding.space()).thenReturn("another-space");
        assertThat(search(aProduct)).isEmpty();
        when(embedding.space()).thenReturn("integration-fake-space");
        var invalid = http.postForEntity("/api/retrieval/search", Map.of("productId","UNKNOWN","query","尺寸"), Map.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        var invalidK = http.postForEntity("/api/retrieval/search", Map.of("productId","BP-A","query","尺寸","topK",11), Map.class);
        assertThat(invalidK.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void useFakeVectors() {
        doAnswer(invocation -> fakeVectors(((List<?>) invocation.getArgument(0)).size())).when(embedding).embed(anyList());
    }
    private List<float[]> fakeVectors(int size) {
        List<float[]> result = new ArrayList<>();
        for (int i=0; i<size; i++) {
            float[] vector = new float[1024]; vector[0]=1; result.add(vector);
        }
        return result;
    }
    private UUID upload(String product, String filename, int version, String text) {
        var form = new LinkedMultiValueMap<String,Object>();
        form.add("productId",product); form.add("version",String.valueOf(version));
        form.add("file",new ByteArrayResource(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            @Override public String getFilename() { return filename; }
        });
        var headers = new HttpHeaders(); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var response = http.postForEntity("/api/admin/documents",new HttpEntity<>(form,headers),Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        return UUID.fromString(response.getBody().get("jobId").toString());
    }
    private void index(UUID id) {
        var result = http.postForEntity("/api/admin/ingestion-jobs/"+id+"/index",null,Map.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }
    private Map<?,?> job(UUID id) { return http.getForObject("/api/admin/ingestion-jobs/"+id,Map.class); }
    private void await(UUID id, String expected) throws Exception {
        for (int i=0;i<100;i++) {
            var job = job(id);
            if (expected.equals(job.get("status"))) return;
            if (List.of("FAILED","INDEX_FAILED").contains(job.get("status"))) fail("Unexpected failure: "+job);
            Thread.sleep(100);
        }
        fail("Timed out waiting for " + expected + ": " + job(id));
    }
    @SuppressWarnings("unchecked")
    private List<Map<String,Object>> search(String product) {
        var result = http.postForEntity("/api/retrieval/search",Map.of("productId",product,"query","尺寸","topK",10),Map.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (List<Map<String,Object>>) result.getBody().get("matches");
    }
}
