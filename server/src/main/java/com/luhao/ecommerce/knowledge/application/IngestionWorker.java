package com.luhao.ecommerce.knowledge.application;

import com.luhao.ecommerce.knowledge.infrastructure.IngestionRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 单应用实例后台消费；不能同时启动多个进程共享数据库，恢复策略不适用于多实例。 */
@Component
public class IngestionWorker {
    private static final Logger log = LoggerFactory.getLogger(IngestionWorker.class);
    private final IngestionRepository repository;
    private final MarkdownChunker chunker;
    private volatile boolean ready;
    public IngestionWorker(IngestionRepository repository, MarkdownChunker chunker) {
        this.repository = repository;
        this.chunker = chunker;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() {
        repository.recoverInterruptedJobs();
        ready = true;
    }

    @Scheduled(fixedDelay = 1000)
    public void processNext() {
        if (!ready) return;
        try {
            var claimed = repository.claim();
            if (claimed.isEmpty()) return;
            var job = claimed.getFirst();
            UUID jobId = (UUID) job.get("job_id"), versionId = (UUID) job.get("version_id");
            long start = System.nanoTime();
            try {
                var chunks = chunker.split(repository.source(versionId));
                if (chunks.isEmpty()) {
                    repository.fail(jobId, "NO_CONTENT");
                } else {
                    repository.complete(jobId, versionId, chunks);
                }
                log.info("ingestion jobId={} chunks={} elapsedMs={}", jobId, chunks.size(), (System.nanoTime() - start) / 1_000_000);
            } catch (Exception e) {
                repository.fail(jobId, "PARSE_OR_SAVE_FAILED");
                log.warn("ingestion jobId={} errorType={}", jobId, e.getClass().getSimpleName());
            }
        } catch (Exception e) {
            log.warn("ingestion poll errorType={}", e.getClass().getSimpleName());
        }
    }
}
