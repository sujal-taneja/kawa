package dev.kawa.job;

import java.util.UUID;

public class LeaseNotHeldException extends RuntimeException {

    private final UUID jobId;

    public LeaseNotHeldException(UUID jobId) {
        super("Job " + jobId + " is not leased with this token. The lease may have expired and been "
                + "reclaimed, the job may already be acked or nacked, or the token is wrong.");
        this.jobId = jobId;
    }

    public UUID jobId() {
        return jobId;
    }
}
