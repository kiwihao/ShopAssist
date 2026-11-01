package com.luhao.ecommerce.model.infrastructure.bigmodel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.luhao.ecommerce.model.application.EmbeddingClient;
import com.luhao.ecommerce.shared.error.ApiException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
@ConditionalOnProperty(name = "demo.embedding.provider", havingValue = "bigmodel")
public class BigModelEmbeddingClient implements EmbeddingClient {
    private static final Logger log = LoggerFactory.getLogger(BigModelEmbeddingClient.class);
    private static final ObjectMapper ERROR_JSON = new ObjectMapper();
    private final RestClient client;
    private final String key;
    private final String space;

    public BigModelEmbeddingClient(RestClient.Builder builder,
            @Value("${demo.embedding.base-url}") String baseUrl,
            @Value("${demo.embedding.api-key}") String key) {
        String endpoint = baseUrl.replaceAll("/+$", "");
        this.key = key.strip();
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.client = builder.baseUrl(endpoint).requestFactory(factory).build();
        try {
            this.space = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((endpoint + "|" + MODEL + "|" + DIMENSIONS).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override public void requireConfigured() {
        if (key.isBlank()) throw error(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_NOT_CONFIGURED", "请在本机设置 BIGMODEL_API_KEY 后重启服务");
    }
    @Override public String space() { return space; }

    @Override public List<float[]> embed(List<String> texts) {
        requireConfigured();
        if (texts.isEmpty() || texts.size() > 64 || texts.stream().anyMatch(t -> t == null || t.isBlank()))
            throw new IllegalArgumentException("Embedding batch must contain 1..64 nonempty texts");
        // 用保守字节上限拦截超长输入，不把字符数当作模型 token 数，也不静默截断证据。
        if (texts.stream().anyMatch(t -> t.getBytes(StandardCharsets.UTF_8).length > 3000))
            throw error(HttpStatus.BAD_REQUEST, "MODEL_INPUT_TOO_LONG", "向量输入过长，请缩短问题或将文档进一步分块");
        long start = System.nanoTime();
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JsonNode response = client.post().uri("/embeddings")
                        .header("Authorization", "Bearer " + key)
                        .body(Map.of("model", MODEL, "dimensions", DIMENSIONS, "input", texts))
                        .retrieve().body(JsonNode.class);
                var vectors = validate(response, texts.size());
                log.info("embedding model={} batchSize={} attempt={} elapsedMs={} totalTokens={}",
                        MODEL, texts.size(), attempt + 1, (System.nanoTime() - start) / 1_000_000,
                        response.path("usage").path("total_tokens").asInt(-1));
                return vectors;
            } catch (RestClientResponseException e) {
                int status = e.getStatusCode().value();
                String upstreamCode = upstreamCode(e);
                long retryDelay = retryDelay(e, upstreamCode);
                if (attempt == 0 && retryDelay >= 0) {
                    log.warn("embedding upstreamStatus={} upstreamCode={} attempt={} retryDelayMs={}",
                            status, upstreamCode, attempt + 1, retryDelay);
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw error(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_INTERRUPTED", "模型调用已中断");
                    }
                    continue;
                }
                log.warn("embedding errorCode=MODEL_HTTP_ERROR upstreamStatus={} upstreamCode={} attempts={}",
                        status, upstreamCode, attempt + 1);
                String code = upstreamCode.equals("UNKNOWN") ? "MODEL_HTTP_ERROR" : "MODEL_HTTP_ERROR_" + upstreamCode;
                throw error(HttpStatus.BAD_GATEWAY, code,
                        "智谱向量接口 HTTP " + status + "，业务码 " + upstreamCode + "。" + errorHint(upstreamCode));
            } catch (ResourceAccessException e) {
                if (attempt == 0) continue;
                throw error(HttpStatus.GATEWAY_TIMEOUT, "MODEL_CONNECTION_ERROR", "向量服务连接失败或超时");
            } catch (RestClientException e) {
                throw invalidResponse();
            }
        }
        throw new IllegalStateException("Unreachable");
    }

    private String upstreamCode(RestClientResponseException e) {
        try {
            byte[] body = e.getResponseBodyAsByteArray();
            if (body.length > 8192) return "UNKNOWN";
            String code = ERROR_JSON.readTree(body).path("error").path("code").asText("");
            // 仅保留数字业务码，不传播供应商 message、响应正文或任何凭据。
            return code.matches("\\d{4,6}") ? code : "UNKNOWN";
        } catch (Exception ignored) {
            return "UNKNOWN";
        }
    }

    private long retryDelay(RestClientResponseException e, String code) {
        int status = e.getStatusCode().value();
        if (!(status >= 500 || (status == 429 && (code.equals("1302") || code.equals("1305"))))) return -1;
        String after = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("Retry-After");
        if (after == null) return 1000;
        // 长等待或 HTTP-date 交还调用方处理，不提前重试而违反上游等待要求。
        if (!after.matches("[0-5]")) return -1;
        return Long.parseLong(after) * 1000;
    }

    private String errorHint(String code) {
        return switch (code) {
            case "1113" -> "请检查标准 API 账户余额或欠费状态。";
            case "1302" -> "账户达到速率限制，请降低请求频率并检查模型并发权益。";
            case "1305" -> "模型当前繁忙，请稍后再试。";
            case "1308", "1310", "1316", "1317", "1318", "1319", "1320", "1321" -> "请检查账户使用额度和重置时间。";
            case "1309", "1314" -> "请检查套餐是否有效。";
            case "1311", "1315" -> "请检查 API Key 类型及此模型的调用权限。";
            case "1313" -> "请在平台查看账户使用限制。";
            default -> "请根据平台业务错误码检查配置、权限或服务状态。";
        };
    }

    private List<float[]> validate(JsonNode response, int count) {
        if (response == null || !response.path("data").isArray() || response.path("data").size() != count)
            throw invalidResponse();
        List<float[]> vectors = new ArrayList<>(Collections.nCopies(count, null));
        for (JsonNode item : response.path("data")) {
            JsonNode indexNode = item.path("index");
            if (!indexNode.isIntegralNumber() || !indexNode.canConvertToInt()) throw invalidResponse();
            int index = indexNode.intValue();
            JsonNode values = item.path("embedding");
            if (index < 0 || index >= count || vectors.get(index) != null || !values.isArray() || values.size() != DIMENSIONS)
                throw invalidResponse();
            float[] vector = new float[DIMENSIONS];
            double norm = 0;
            for (int i = 0; i < DIMENSIONS; i++) {
                if (!values.get(i).isNumber()) throw invalidResponse();
                vector[i] = values.get(i).floatValue();
                if (!Float.isFinite(vector[i])) throw invalidResponse();
                norm += (double) vector[i] * vector[i];
            }
            if (norm == 0) throw invalidResponse();
            vectors.set(index, vector);
        }
        return vectors;
    }

    private ApiException invalidResponse() {
        return error(HttpStatus.BAD_GATEWAY, "MODEL_INVALID_RESPONSE", "向量服务返回的数据数量、顺序或维度不符合要求");
    }
    private ApiException error(HttpStatus status, String code, String message) {
        return new ApiException(status, code, message);
    }
}
