CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE ingestion_jobs DROP CONSTRAINT ingestion_jobs_status_check;
ALTER TABLE ingestion_jobs ADD CONSTRAINT ingestion_jobs_status_check
    CHECK (status IN ('PENDING','PROCESSING','PARSED','FAILED','INDEX_PENDING','INDEXING','READY','INDEX_FAILED'));

ALTER TABLE documents ADD COLUMN active_version_id uuid REFERENCES document_versions(version_id);

CREATE TABLE chunk_vectors (
    chunk_id uuid PRIMARY KEY REFERENCES chunks(chunk_id) ON DELETE CASCADE,
    embedding_space varchar(64) NOT NULL,
    embedding vector(1024) NOT NULL
);
-- 首版精确余弦检索基线，不引入 HNSW/IVFFlat 近似索引。
