package com.luhao.ecommerce.retrieval.api;

import com.luhao.ecommerce.retrieval.application.RetrievalService;
import com.luhao.ecommerce.retrieval.application.VectorIndexService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class RetrievalController {
    private final RetrievalService retrieval;
    private final VectorIndexService indexing;
    public RetrievalController(RetrievalService retrieval, VectorIndexService indexing) {
        this.retrieval = retrieval;
        this.indexing = indexing;
    }

    @PostMapping("/api/admin/ingestion-jobs/{id}/index")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Object> index(@PathVariable UUID id) { return indexing.enqueue(id); }

    @PostMapping("/api/retrieval/search")
    public Map<String, Object> search(@RequestBody SearchRequest request) {
        return retrieval.search(request.productId(), request.query(), request.topK());
    }

    public record SearchRequest(String productId, String query, Integer topK) {}
}
