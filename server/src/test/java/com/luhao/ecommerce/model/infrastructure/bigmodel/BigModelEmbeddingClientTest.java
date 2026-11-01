package com.luhao.ecommerce.model.infrastructure.bigmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.luhao.ecommerce.shared.error.ApiException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;

class BigModelEmbeddingClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;
    private BigModelEmbeddingClient client;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicReference<String> request = new AtomicReference<>();
    private String response;
    private int status = 200;
    private boolean firstCallFails;
    private String retryAfter;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int call = calls.incrementAndGet();
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            if (retryAfter != null) exchange.getResponseHeaders().add("Retry-After", retryAfter);
            exchange.sendResponseHeaders(firstCallFails && call == 1 ? 503 : status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = create("unit-test-key");
    }
    @AfterEach void stop() { server.stop(0); }
    private BigModelEmbeddingClient create(String key) {
        return new BigModelEmbeddingClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(), key);
    }
    private Map<String, Object> item(int index, int dimensions, float first) {
        float[] vector = new float[dimensions];
        vector[0] = first;
        return Map.of("index", index, "embedding", vector);
    }

    @Test void requests1024DimensionsAndMapsReorderedResults() throws Exception {
        response = json.writeValueAsString(Map.of("data", List.of(item(1,1024,2), item(0,1024,1))));
        var vectors = client.embed(List.of("A", "B"));
        assertThat(vectors.getFirst()[0]).isEqualTo(1);
        assertThat(vectors.getLast()[0]).isEqualTo(2);
        var sent = json.readTree(request.get());
        assertThat(sent.path("model").asText()).isEqualTo("embedding-3");
        assertThat(sent.path("dimensions").asInt()).isEqualTo(1024);
    }

    @Test void rejectsWrongDimensions() throws Exception {
        response = json.writeValueAsString(Map.of("data", List.of(item(0,512,1))));
        assertThatThrownBy(() -> client.embed(List.of("A"))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("MODEL_INVALID_RESPONSE"));
    }

    @Test void rejectsDuplicateIndicesAndZeroVectors() throws Exception {
        response = json.writeValueAsString(Map.of("data", List.of(item(0,1024,1), item(0,1024,2))));
        assertThatThrownBy(() -> client.embed(List.of("A", "B"))).isInstanceOf(ApiException.class);
        response = json.writeValueAsString(Map.of("data", List.of(item(0,1024,0))));
        assertThatThrownBy(() -> client.embed(List.of("A"))).isInstanceOf(ApiException.class);
    }

    @Test void transientServerFailureRetriesOnlyOnce() throws Exception {
        response = json.writeValueAsString(Map.of("data", List.of(item(0,1024,1))));
        firstCallFails = true;
        assertThat(client.embed(List.of("A"))).hasSize(1);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void permanentAuthorizationFailureDoesNotRetryOrExposeResponse() {
        response = "{\"error\":\"private-upstream-message\"}";
        status = 401;
        assertThatThrownBy(() -> client.embed(List.of("A"))).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo("MODEL_HTTP_ERROR");
            assertThat(e.getMessage()).doesNotContain("private-upstream-message", "unit-test-key");
        });
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void malformedJsonReturnsExplicitModelError() {
        response = "invalid-json";
        assertThatThrownBy(() -> client.embed(List.of("A"))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("MODEL_INVALID_RESPONSE"));
    }

    @Test void billingQuotaAndKeyErrorsKeepSafeCodeAndNeverRetry() {
        status = 429;
        for (String code : List.of("1113", "1308", "1311", "1315")) {
            response = "{\"error\":{\"code\":\""+code+"\",\"message\":\"private-message\"}}";
            int before = calls.get();
            assertThatThrownBy(() -> client.embed(List.of("A"))).isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.code()).isEqualTo("MODEL_HTTP_ERROR_"+code);
                assertThat(e.getMessage()).contains(code).doesNotContain("private-message");
            });
            assertThat(calls.get()-before).isEqualTo(1);
        }
    }

    @Test void knownRateLimitRetriesOnlyOnce() {
        status = 429;
        response = "{\"error\":{\"code\":\"1302\"}}";
        retryAfter = "0";
        assertThatThrownBy(() -> client.embed(List.of("A"))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("MODEL_HTTP_ERROR_1302"));
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void unknown429AndLongRetryAfterDoNotRetryBlindly() {
        status = 429;
        response = "{\"error\":{\"code\":\"sensitive-text-unit-test-key\"}}";
        assertThatThrownBy(() -> client.embed(List.of("A"))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("MODEL_HTTP_ERROR"));
        assertThat(calls.get()).isEqualTo(1);
        response = "{\"error\":{\"code\":\"1302\"}}";
        retryAfter = "60";
        assertThatThrownBy(() -> client.embed(List.of("A"))).isInstanceOf(ApiException.class);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test void missingKeyAndOversizedInputNeverCallUpstream() {
        assertThatThrownBy(() -> create("").embed(List.of("A"))).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.code()).isEqualTo("MODEL_NOT_CONFIGURED"));
        assertThatThrownBy(() -> client.embed(List.of("😀".repeat(1000)))).isInstanceOf(ApiException.class);
        assertThat(calls.get()).isZero();
    }
}
