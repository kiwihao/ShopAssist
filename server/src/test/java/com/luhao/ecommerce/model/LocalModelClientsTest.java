package com.luhao.ecommerce.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luhao.ecommerce.model.infrastructure.ollama.OllamaEmbeddingClient;
import com.luhao.ecommerce.model.infrastructure.deepseek.DeepSeekChatClient;
import com.luhao.ecommerce.model.infrastructure.deepseek.DeepSeekToolCallingClient;
import com.luhao.ecommerce.shared.error.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;

class LocalModelClientsTest {
    HttpServer server; String url,body,request; int status=200;
    ObjectMapper json=new ObjectMapper();
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            request=new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type","application/json");
            e.sendResponseHeaders(status,bytes.length);e.getResponseBody().write(bytes);e.close();
        });
        server.start();url="http://127.0.0.1:"+server.getAddress().getPort();
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void localVectorsUseActualModelAndDisableSilentTruncation() throws Exception {
        float[] v=new float[1024];v[0]=1;
        body=json.writeValueAsString(Map.of("embeddings",List.of(v)));
        var client=new OllamaEmbeddingClient(RestClient.builder(),url,"bge-m3:567m");
        assertThat(client.embed(List.of("尺寸"))).hasSize(1);
        assertThat(client.model()).isEqualTo("bge-m3:567m");
        assertThat(json.readTree(request).path("truncate").asBoolean(true)).isFalse();
    }
    @Test void rejectsLocalModelWithWrongDimensions() {
        body="{\"embeddings\":[[1,2]]}";
        var client=new OllamaEmbeddingClient(RestClient.builder(),url,"bge-m3:567m");
        assertThatThrownBy(()->client.embed(List.of("尺寸"))).isInstanceOf(ApiException.class);
    }
    @Test void deepseekSendsSeparateChatModelAndReadsContent() throws Exception {
        body=json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("content","{}")))));
        var client=new DeepSeekChatClient(RestClient.builder(),url,"test-key","deepseek-flash");
        assertThat(client.complete("输出 JSON","提问")).isEqualTo("{}");
        assertThat(json.readTree(request).path("model").asText()).isEqualTo("deepseek-flash");
        assertThat(request).doesNotContain("test-key");
    }
    @Test void missingDeepseekKeyNeverCallsNetwork() {
        var client=new DeepSeekChatClient(RestClient.builder(),url,"","deepseek-flash");
        assertThatThrownBy(()->client.complete("JSON","问题")).isInstanceOfSatisfying(ApiException.class,
                e->assertThat(e.code()).isEqualTo("CHAT_NOT_CONFIGURED"));
        assertThat(request).isNull();
    }
    @Test void upstreamFailureBecomesRecognizableServiceError() {
        status=503; body="{\"error\":{\"message\":\"upstream unavailable\"}}";
        var client=new DeepSeekChatClient(RestClient.builder(),url,"test-key","deepseek-flash");
        assertThatThrownBy(()->client.complete("JSON","问题")).isInstanceOfSatisfying(ApiException.class,
                e->assertThat(e.code()).isEqualTo("CHAT_HTTP_503"));
    }
    @Test void nativeToolCallsAndToolResultIdsUseTheActualHttpProtocol() throws Exception {
        body=json.writeValueAsString(Map.of("choices",List.of(Map.of("message",Map.of("role","assistant","tool_calls",List.of(Map.of("id","call-1","type","function","function",Map.of("name","query_product_knowledge","arguments","{}"))))))));
        var client=new DeepSeekToolCallingClient(RestClient.builder(),url,"test-key","deepseek-flash");
        var messages=List.<Map<String,Object>>of(Map.of("role","tool","tool_call_id","previous-call","content","{\"status\":\"ANSWER\"}"));
        var response=client.next(messages,List.of(Map.of("type","function","function",Map.of("name","query_product_knowledge"))));
        assertThat(response.path("tool_calls").get(0).path("id").asText()).isEqualTo("call-1");
        var sent=json.readTree(request);
        assertThat(sent.path("messages").get(0).path("tool_call_id").asText()).isEqualTo("previous-call");
        assertThat(sent.path("tools").get(0).path("function").path("name").asText()).isEqualTo("query_product_knowledge");
        assertThat(sent.path("thinking").path("type").asText()).isEqualTo("disabled");
    }
}
