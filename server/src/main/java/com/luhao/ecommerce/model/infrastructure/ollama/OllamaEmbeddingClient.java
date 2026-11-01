package com.luhao.ecommerce.model.infrastructure.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.luhao.ecommerce.model.application.EmbeddingClient;
import com.luhao.ecommerce.shared.error.ApiException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(name="demo.embedding.provider", havingValue="ollama", matchIfMissing=true)
public class OllamaEmbeddingClient implements EmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingClient.class);
    private final RestClient client;
    private final String model;
    private final String space;
    public OllamaEmbeddingClient(RestClient.Builder builder,
            @Value("${demo.embedding.ollama-url}") String url,
            @Value("${demo.embedding.ollama-model}") String model) {
        this.model = model;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.client = builder.baseUrl(url.replaceAll("/+$", "")).requestFactory(factory).build();
        try {
            // 本地/容器访问地址可不同；同一模型名共享空间。换权重须换标签并重建索引。
            space = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("ollama|"+model+"|1024").getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    @Override public void requireConfigured() {}
    @Override public String model() { return model; }
    @Override public String space() { return space; }
    @Override public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty() || texts.size()>64 || texts.stream().anyMatch(t -> t==null || t.isBlank()))
            throw new IllegalArgumentException("Invalid embedding input");
        long start=System.nanoTime();
        try {
            JsonNode response=client.post().uri("/api/embed")
                    .body(Map.of("model",model,"input",texts,"truncate",false,"keep_alive","5m"))
                    .retrieve().body(JsonNode.class);
            if (response==null || !response.path("embeddings").isArray() || response.path("embeddings").size()!=texts.size()) throw invalid();
            List<float[]> result=new ArrayList<>();
            for (JsonNode row:response.path("embeddings")) {
                if (!row.isArray() || row.size()!=DIMENSIONS) throw invalid();
                float[] vector=new float[DIMENSIONS]; double norm=0;
                for(int i=0;i<DIMENSIONS;i++) {
                    if(!row.get(i).isNumber()) throw invalid();
                    vector[i]=row.get(i).floatValue();
                    if(!Float.isFinite(vector[i])) throw invalid();
                    norm+=(double)vector[i]*vector[i];
                }
                if(norm==0) throw invalid();
                result.add(vector);
            }
            log.info("embedding provider=ollama model={} batchSize={} elapsedMs={}",model,texts.size(),(System.nanoTime()-start)/1_000_000);
            return result;
        } catch(ResourceAccessException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"OLLAMA_UNAVAILABLE","本地向量服务未就绪或超时，请检查 Ollama 和模型初始化日志");
        } catch(RestClientResponseException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY,"OLLAMA_HTTP_"+e.getStatusCode().value(),"本地模型调用失败，请确认已下载模型且输入未超长");
        } catch(RestClientException e) { throw invalid(); }
    }
    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_GATEWAY,"OLLAMA_INVALID_RESPONSE","本地模型未返回预期的 1024 维有效向量");
    }
}
