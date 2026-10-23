ALTER TABLE jobs ADD COLUMN priority SMALLINT NOT NULL DEFAULT 0 CHECK (priority BETWEEN 0 AND 9);

-- take() now orders by priority first, so the claim index must too, or every take would sort
DROP INDEX idx_jobs_claimable;
CREATE INDEX idx_jobs_claimable ON jobs (queue, priority DESC, available_at) WHERE status = 'PENDING';
