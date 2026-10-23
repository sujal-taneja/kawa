package dev.kawa.job;

import java.util.UUID;

public class JobNotFoundException extends RuntimeException {

    private final UUID jobId;

    public JobNotFoundException(UUID jobId) {
        super("No job exists with id " + jobId + ".");
        this.jobId = jobId;
    }

    public UUID jobId() {
        return jobId;
    }
}
