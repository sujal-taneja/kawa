CREATE TABLE jobs (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    queue           TEXT NOT NULL,
    payload         JSONB NOT NULL,
    status          TEXT NOT NULL DEFAULT 'PENDING',
    attempts        INT NOT NULL DEFAULT 0,
    max_attempts    INT NOT NULL DEFAULT 5,
    available_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    lease_token     UUID,
    lease_expires_at TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- the index take() actually uses: "give me the next runnable job in this queue"
CREATE INDEX idx_jobs_claimable ON jobs (queue, available_at) WHERE status = 'PENDING';

-- the index the reaper uses: "give me leases that expired"
CREATE INDEX idx_jobs_leased ON jobs (lease_expires_at) WHERE status = 'LEASED';
