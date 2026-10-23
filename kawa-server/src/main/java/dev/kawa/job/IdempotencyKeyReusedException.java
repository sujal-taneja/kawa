package dev.kawa.job;

import java.util.UUID;

public class IdempotencyKeyReusedException extends RuntimeException {

    private final UUID existingJobId;

    public IdempotencyKeyReusedException(String idempotencyKey, UUID existingJobId) {
        super("Idempotency-Key '" + idempotencyKey + "' was already used for job " + existingJobId
                + " with a different payload. Use a new key for a different job.");
        this.existingJobId = existingJobId;
    }

    public UUID existingJobId() {
        return existingJobId;
    }
}
