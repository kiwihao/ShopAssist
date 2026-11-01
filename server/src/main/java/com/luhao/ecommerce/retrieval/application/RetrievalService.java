package com.luhao.ecommerce.retrieval.application;

import com.luhao.ecommerce.model.application.EmbeddingClient;
import com.luhao.ecommerce.product.application.ProductService;
import com.luhao.ecommerce.retrieval.infrastructure.VectorRepository;
import com.luhao.ecommerce.shared.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {
    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private final ProductService products;
    private final EmbeddingClient embedding;
    private final VectorRepository repository;
    public RetrievalService(ProductService products, EmbeddingClient embedding, VectorRepository repository) {
        this.products = products;
        this.embedding = embedding;
        this.repository = repository;
    }

    public Map<String, Object> search(String productId, String query, Integer topK) {
        if (productId == null || productId.isBlank() || query == null || query.isBlank()
                || query.codePointCount(0, query.length()) > 1000)
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_QUERY", "请提供商品 ID 和 1～1000 字符的问题");
        int k = topK == null ? 5 : topK;
        if (k < 1 || k > 10) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_TOP_K", "topK 范围为 1～10");
        products.get(productId);
        String requestId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        float[] vector = embedding.embed(List.of(query.strip())).getFirst();
        long modelMs = (System.nanoTime() - start) / 1_000_000;
        var matches = repository.search(productId, vector, embedding.space(), k);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        log.info("retrieval requestId={} productId={} matches={} embeddingMs={} searchMs={} elapsedMs={}",
                requestId, productId, matches.size(), modelMs, elapsedMs - modelMs, elapsedMs);
        return Map.of("requestId", requestId, "productId", productId, "matches", matches,
                "embeddingMs", modelMs, "searchMs", elapsedMs - modelMs, "elapsedMs", elapsedMs,
                "model", embedding.model(), "dimensions", EmbeddingClient.DIMENSIONS);
    }
}
