package com.luhao.ecommerce.retrieval.application;

import com.luhao.ecommerce.model.application.EmbeddingClient;
import com.luhao.ecommerce.retrieval.infrastructure.VectorRepository;
import com.luhao.ecommerce.shared.error.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VectorIndexWorker {
    private static final Logger log = LoggerFactory.getLogger(VectorIndexWorker.class);
    private final VectorRepository repository;
    private final EmbeddingClient embedding;
    private volatile boolean ready;
    public VectorIndexWorker(VectorRepository repository, EmbeddingClient embedding) {
        this.repository = repository;
        this.embedding = embedding;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        repository.recover();
        ready = true;
    }

    @Scheduled(fixedDelay = 1000)
    public void processNext() {
        if (!ready) return;
        try {
            var jobs = repository.claim();
            if (jobs.isEmpty()) return;
            UUID jobId = (UUID) jobs.getFirst().get("job_id");
            UUID versionId = (UUID) jobs.getFirst().get("version_id");
            long start = System.nanoTime();
            try {
                var chunks = repository.chunks(versionId);
                List<float[]> vectors = new ArrayList<>();
                // 小批量上限 16，避免一次请求塞入整份文档。
                for (int from = 0; from < chunks.size(); from += 16) {
                    var texts = chunks.subList(from, Math.min(from + 16, chunks.size())).stream()
                            .map(c -> c.get("heading") + "\n" + c.get("content")).toList();
                    vectors.addAll(embedding.embed(texts));
                }
                repository.complete(jobId, versionId, chunks, vectors, embedding.space());
                log.info("vectorIndex jobId={} status=READY chunks={} elapsedMs={}", jobId, vectors.size(),
                        (System.nanoTime() - start) / 1_000_000);
            } catch (Exception e) {
                String code = e instanceof ApiException api ? api.code() : "INDEX_SAVE_OR_MODEL_FAILED";
                repository.fail(jobId, code);
                log.warn("vectorIndex jobId={} errorCode={} errorType={}", jobId, code, e.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.warn("vectorIndex poll errorType={}", e.getClass().getSimpleName());
        }
    }
}
