CREATE TABLE products (
    product_id varchar(40) PRIMARY KEY,
    name varchar(100) NOT NULL,
    category varchar(40) NOT NULL,
    price_cny numeric(10,2) NOT NULL CHECK (price_cny >= 0),
    laptop_height_cm numeric(8,2) NOT NULL CHECK (laptop_height_cm > 0),
    laptop_width_cm numeric(8,2) NOT NULL CHECK (laptop_width_cm > 0),
    laptop_depth_cm numeric(8,2) NOT NULL CHECK (laptop_depth_cm > 0),
    status varchar(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE documents (
    document_id uuid PRIMARY KEY,
    product_id varchar(40) NOT NULL REFERENCES products(product_id),
    filename varchar(160) NOT NULL,
    UNIQUE (product_id, filename)
);

CREATE TABLE document_versions (
    version_id uuid PRIMARY KEY,
    document_id uuid NOT NULL REFERENCES documents(document_id),
    version integer NOT NULL CHECK (version > 0),
    source_text text NOT NULL,
    content_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, version)
);

CREATE TABLE ingestion_jobs (
    job_id uuid PRIMARY KEY,
    version_id uuid NOT NULL UNIQUE REFERENCES document_versions(version_id),
    status varchar(20) NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'PARSED', 'FAILED')),
    error_code varchar(40),
    chunk_count integer NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX ingestion_jobs_pending ON ingestion_jobs(created_at) WHERE status = 'PENDING';

CREATE TABLE chunks (
    chunk_id uuid PRIMARY KEY,
    version_id uuid NOT NULL REFERENCES document_versions(version_id),
    ordinal integer NOT NULL,
    heading text NOT NULL,
    line_start integer NOT NULL CHECK (line_start > 0),
    line_end integer NOT NULL CHECK (line_end >= line_start),
    content text NOT NULL,
    UNIQUE (version_id, ordinal)
);

-- PARSED 仅表示分块保存完成，尚未向量化、激活为可检索版本。
