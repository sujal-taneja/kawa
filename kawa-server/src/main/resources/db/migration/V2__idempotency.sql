ALTER TABLE jobs ADD COLUMN idempotency_key TEXT;
ALTER TABLE jobs ADD COLUMN payload_hash BYTEA;

-- the unique index is what makes duplicate enqueues impossible, even under concurrent retries
CREATE UNIQUE INDEX uq_jobs_idempotency ON jobs (queue, idempotency_key) WHERE idempotency_key IS NOT NULL;
