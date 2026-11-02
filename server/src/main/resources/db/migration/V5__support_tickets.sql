CREATE TABLE ticket_drafts (
    draft_id uuid PRIMARY KEY,
    product_id varchar(40) NOT NULL REFERENCES products(product_id),
    summary text NOT NULL CHECK (length(summary) BETWEEN 1 AND 1000),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    token_hash char(64) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','CREATED','CANCELLED')),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE consultation_tickets (
    ticket_id uuid PRIMARY KEY,
    ticket_number varchar(40) NOT NULL UNIQUE,
    draft_id uuid NOT NULL UNIQUE REFERENCES ticket_drafts(draft_id),
    product_id varchar(40) NOT NULL REFERENCES products(product_id),
    summary text NOT NULL,
    draft_version integer NOT NULL,
    token_hash char(64) NOT NULL,
    idempotency_key varchar(100) NOT NULL UNIQUE,
    status varchar(20) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN')),
    created_at timestamptz NOT NULL DEFAULT now()
);
