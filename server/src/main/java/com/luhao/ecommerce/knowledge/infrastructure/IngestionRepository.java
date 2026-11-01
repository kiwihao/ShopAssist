package com.luhao.ecommerce.knowledge.infrastructure;

import com.luhao.ecommerce.knowledge.domain.TextChunk;
import com.luhao.ecommerce.shared.error.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IngestionRepository {
    private final JdbcTemplate jdbc;
    public IngestionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    public UUID submit(String productId, String filename, int version, String source, String hash) {
        // 同一文档并发上传串行处理，唯一约束最终保障版本唯一。
        UUID documentId = jdbc.queryForObject("""
                INSERT INTO documents(document_id, product_id, filename) VALUES (?, ?, ?)
                ON CONFLICT(product_id, filename) DO UPDATE SET filename = excluded.filename
                RETURNING document_id
                """, UUID.class, UUID.randomUUID(), productId, filename);
        var existing = jdbc.queryForList("""
                SELECT j.job_id, v.content_hash FROM document_versions v
                JOIN ingestion_jobs j ON j.version_id = v.version_id
                WHERE v.document_id = ? AND v.version = ?
                """, documentId, version);
        if (!existing.isEmpty()) {
            if (!hash.equals(existing.getFirst().get("content_hash")))
                throw new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "同一文档版本内容不同，请使用新版本号");
            return (UUID) existing.getFirst().get("job_id");
        }
        UUID versionId = UUID.randomUUID(), jobId = UUID.randomUUID();
        jdbc.update("INSERT INTO document_versions(version_id, document_id, version, source_text, content_hash) VALUES (?,?,?,?,?)",
                versionId, documentId, version, source, hash);
        jdbc.update("INSERT INTO ingestion_jobs(job_id, version_id, status) VALUES (?,?,'PENDING')", jobId, versionId);
        return jobId;
    }

    public Map<String, Object> job(UUID jobId) {
        var rows = jdbc.queryForList("""
                SELECT j.job_id, j.status, j.error_code, j.chunk_count, j.created_at, j.updated_at,
                       d.document_id, d.product_id, d.filename, v.version_id, v.version, v.content_hash,
                       d.active_version_id, (d.active_version_id=v.version_id) AS active
                FROM ingestion_jobs j JOIN document_versions v ON v.version_id = j.version_id
                JOIN documents d ON d.document_id = v.document_id WHERE j.job_id = ?
                """, jobId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "JOB_NOT_FOUND", "入库任务不存在");
        return rows.getFirst();
    }

    public List<Map<String, Object>> chunks(UUID jobId) {
        job(jobId);
        return jdbc.queryForList("""
                SELECT c.chunk_id, c.ordinal, c.heading, c.line_start, c.line_end, c.content,
                       d.product_id, d.filename, v.version
                FROM chunks c JOIN document_versions v ON v.version_id = c.version_id
                JOIN documents d ON d.document_id = v.document_id
                JOIN ingestion_jobs j ON j.version_id = v.version_id
                WHERE j.job_id = ? ORDER BY c.ordinal
                """, jobId);
    }

    public List<Map<String, Object>> claim() {
        return jdbc.queryForList("""
                WITH candidate AS (
                    SELECT job_id FROM ingestion_jobs WHERE status = 'PENDING'
                    ORDER BY created_at LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE ingestion_jobs SET status='PROCESSING', updated_at=now()
                WHERE job_id IN (SELECT job_id FROM candidate) RETURNING job_id, version_id
                """);
    }

    public String source(UUID versionId) {
        return jdbc.queryForObject("SELECT source_text FROM document_versions WHERE version_id = ?", String.class, versionId);
    }

    @Transactional
    public void complete(UUID jobId, UUID versionId, List<TextChunk> chunks) {
        jdbc.update("DELETE FROM chunks WHERE version_id = ?", versionId);
        for (var chunk : chunks) {
            jdbc.update("INSERT INTO chunks(chunk_id,version_id,ordinal,heading,line_start,line_end,content) VALUES (?,?,?,?,?,?,?)",
                    UUID.randomUUID(), versionId, chunk.ordinal(), chunk.heading(), chunk.lineStart(), chunk.lineEnd(), chunk.content());
        }
        jdbc.update("UPDATE ingestion_jobs SET status='PARSED', chunk_count=?, error_code=NULL, updated_at=now() WHERE job_id=?",
                chunks.size(), jobId);
    }

    public void fail(UUID jobId, String code) {
        jdbc.update("UPDATE ingestion_jobs SET status='FAILED', error_code=?, updated_at=now() WHERE job_id=?", code, jobId);
    }

    public void recoverInterruptedJobs() {
        jdbc.update("UPDATE ingestion_jobs SET status='PENDING', updated_at=now() WHERE status='PROCESSING'");
    }
}
