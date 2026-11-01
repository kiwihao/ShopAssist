package com.luhao.ecommerce.retrieval.application;

import com.luhao.ecommerce.knowledge.application.IngestionService;
import com.luhao.ecommerce.model.application.EmbeddingClient;
import com.luhao.ecommerce.retrieval.infrastructure.VectorRepository;
import com.luhao.ecommerce.shared.error.ApiException;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class VectorIndexService {
    private final IngestionService ingestion;
    private final EmbeddingClient embedding;
    private final VectorRepository vectors;
    public VectorIndexService(IngestionService ingestion, EmbeddingClient embedding, VectorRepository vectors) {
        this.ingestion = ingestion;
        this.embedding = embedding;
        this.vectors = vectors;
    }

    public Map<String, Object> enqueue(UUID jobId) {
        var job = ingestion.job(jobId);
        String status = (String) job.get("status");
        if (!status.equals("PARSED") && !status.equals("INDEX_FAILED")
                && !status.equals("INDEX_PENDING") && !status.equals("INDEXING") && !status.equals("READY"))
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_NOT_PARSED", "请先等待文档解析完成");
        if (status.equals("PARSED") || status.equals("INDEX_FAILED")) {
            embedding.requireConfigured();
            vectors.queue(jobId);
        }
        return ingestion.job(jobId);
    }
}
