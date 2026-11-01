package com.luhao.ecommerce.retrieval.infrastructure;

import com.luhao.ecommerce.model.application.EmbeddingClient;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class VectorRepository {
    private final JdbcTemplate jdbc;
    public VectorRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public boolean queue(UUID jobId) {
        return jdbc.update("""
                UPDATE ingestion_jobs SET status='INDEX_PENDING',error_code=NULL,updated_at=now()
                WHERE job_id=? AND status IN ('PARSED','INDEX_FAILED')
                """, jobId) == 1;
    }

    public List<Map<String, Object>> claim() {
        return jdbc.queryForList("""
                WITH candidate AS (
                    SELECT job_id FROM ingestion_jobs WHERE status='INDEX_PENDING'
                    ORDER BY updated_at LIMIT 1 FOR UPDATE SKIP LOCKED
                )
                UPDATE ingestion_jobs SET status='INDEXING',updated_at=now()
                WHERE job_id IN (SELECT job_id FROM candidate) RETURNING job_id,version_id
                """);
    }

    public List<Map<String, Object>> chunks(UUID versionId) {
        return jdbc.queryForList("SELECT chunk_id,heading,content FROM chunks WHERE version_id=? ORDER BY ordinal", versionId);
    }

    @Transactional
    public void complete(UUID jobId, UUID versionId, List<Map<String, Object>> chunks, List<float[]> vectors, String space) {
        if (chunks.isEmpty() || chunks.size() != vectors.size()) throw new IllegalArgumentException("Invalid vector count");
        // 所有模型批次成功后才事务写入，失败不会暴露半套向量或切走旧版。
        for (int i = 0; i < chunks.size(); i++) {
            jdbc.update("""
                    INSERT INTO chunk_vectors(chunk_id,embedding_space,embedding) VALUES (?,?,?::vector)
                    ON CONFLICT(chunk_id) DO UPDATE SET embedding_space=excluded.embedding_space,embedding=excluded.embedding
                    """, chunks.get(i).get("chunk_id"), space, literal(vectors.get(i)));
        }
        jdbc.update("UPDATE ingestion_jobs SET status='READY',error_code=NULL,updated_at=now() WHERE job_id=?", jobId);
        jdbc.update("""
                UPDATE documents d SET active_version_id=? FROM document_versions candidate
                WHERE candidate.version_id=? AND candidate.document_id=d.document_id
                AND (d.active_version_id IS NULL OR candidate.version >=
                    (SELECT version FROM document_versions WHERE version_id=d.active_version_id))
                """, versionId, versionId);
    }

    public void fail(UUID jobId, String code) {
        jdbc.update("UPDATE ingestion_jobs SET status='INDEX_FAILED',error_code=?,updated_at=now() WHERE job_id=?", code, jobId);
    }

    public void recover() {
        jdbc.update("UPDATE ingestion_jobs SET status='INDEX_PENDING',updated_at=now() WHERE status='INDEXING'");
    }

    public List<Map<String, Object>> search(String productId, float[] query, String space, int topK) {
        String vector = literal(query);
        return jdbc.queryForList("""
                SELECT c.chunk_id,d.product_id,d.filename,v.version,c.heading,c.line_start,c.line_end,c.content,
                       1 - (cv.embedding <=> ?::vector) AS similarity
                FROM chunk_vectors cv JOIN chunks c ON c.chunk_id=cv.chunk_id
                JOIN document_versions v ON v.version_id=c.version_id
                JOIN documents d ON d.document_id=v.document_id
                JOIN ingestion_jobs j ON j.version_id=v.version_id
                JOIN products p ON p.product_id=d.product_id
                WHERE d.product_id=? AND p.status='ACTIVE' AND d.active_version_id=v.version_id
                  AND j.status='READY' AND cv.embedding_space=?
                ORDER BY cv.embedding <=> ?::vector, c.chunk_id LIMIT ?
                """, vector, productId, space, vector, topK);
    }

    public static String literal(float[] values) {
        if (values.length != EmbeddingClient.DIMENSIONS) throw new IllegalArgumentException("Invalid vector dimensions");
        double norm = 0;
        for (float value : values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite vector value");
            norm += (double) value * value;
        }
        if (norm == 0) throw new IllegalArgumentException("Zero vector");
        return Arrays.toString(values);
    }
}
