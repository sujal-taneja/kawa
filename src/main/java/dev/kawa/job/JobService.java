package dev.kawa.job;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);
    static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(10);

    private final JobRepository repository;

    public JobService(JobRepository repository) {
        this.repository = repository;
    }

    public UUID enqueue(String queue, String payloadJson, Integer maxAttempts) {
        int attempts = maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        if (attempts < 1) {
            throw new InvalidJobRequestException("maxAttempts must be at least 1 (got " + attempts + ").");
        }
        return repository.enqueue(queue, payloadJson, attempts);
    }

    public Optional<Job> take(String queue, Integer leaseSeconds) {
        return repository.take(queue, durationOr("leaseSeconds", leaseSeconds, DEFAULT_LEASE));
    }

    public void extend(UUID id, UUID leaseToken, Integer leaseSeconds) {
        requireLeaseHeld(id, repository.extend(id, leaseToken, durationOr("seconds", leaseSeconds, DEFAULT_LEASE)));
    }

    public void ack(UUID id, UUID leaseToken) {
        requireLeaseHeld(id, repository.ack(id, leaseToken));
    }

    public void nack(UUID id, UUID leaseToken, Integer retryDelaySeconds) {
        requireLeaseHeld(id, repository.nack(id, leaseToken,
                durationOr("seconds", retryDelaySeconds, DEFAULT_RETRY_DELAY)));
    }

    public Job get(UUID id) {
        return repository.findById(id).orElseThrow(() -> new JobNotFoundException(id));
    }

    // a single UPDATE only reports "0 rows", not which of expired / settled / wrong token applied
    private static void requireLeaseHeld(UUID id, boolean updated) {
        if (!updated) {
            throw new LeaseNotHeldException(id);
        }
    }

    private static Duration durationOr(String field, Integer seconds, Duration fallback) {
        if (seconds == null) {
            return fallback;
        }
        if (seconds < 1) {
            throw new InvalidJobRequestException(field + " must be at least 1 (got " + seconds + ").");
        }
        return Duration.ofSeconds(seconds);
    }
}
