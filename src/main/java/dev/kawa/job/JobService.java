package dev.kawa.job;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    static final int DEFAULT_MAX_ATTEMPTS = 5;
    static final Duration DEFAULT_LEASE = Duration.ofSeconds(30);
    static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(10);
    static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
    // a cap catches unit mistakes (milliseconds sent as seconds) and stops forgotten jobs piling up for decades
    static final Duration MAX_DELAY = Duration.ofDays(365);

    private final JobRepository repository;

    public JobService(JobRepository repository) {
        this.repository = repository;
    }

    public EnqueueResult enqueue(String queue, String payloadJson, Integer maxAttempts, String idempotencyKey,
                                 Instant runAt, Integer delaySeconds) {
        int attempts = maxAttempts == null ? DEFAULT_MAX_ATTEMPTS : maxAttempts;
        if (attempts < 1) {
            throw new InvalidJobRequestException("maxAttempts must be at least 1 (got " + attempts + ").");
        }
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH)) {
            throw new InvalidJobRequestException(
                    "Idempotency-Key must be 1 to " + MAX_IDEMPOTENCY_KEY_LENGTH + " non-blank characters.");
        }
        if (runAt != null && delaySeconds != null) {
            throw new InvalidJobRequestException("Give either runAt or delaySeconds, not both.");
        }
        if (delaySeconds != null && (delaySeconds < 0 || delaySeconds > MAX_DELAY.toSeconds())) {
            throw new InvalidJobRequestException(
                    "delaySeconds must be between 0 and " + MAX_DELAY.toSeconds() + " (1 year), got " + delaySeconds + ".");
        }
        // the app clock is fine for a coarse one-year cap; the actual schedule uses the database clock
        if (runAt != null && runAt.isAfter(Instant.now().plus(MAX_DELAY))) {
            throw new InvalidJobRequestException("runAt must be at most 1 year in the future (got " + runAt + ").");
        }
        EnqueueResult result = repository.enqueue(new NewJob(queue, payloadJson, attempts, idempotencyKey, runAt,
                delaySeconds == null ? 0 : delaySeconds));
        // silently returning the old job here would hide a client bug: one key reused for two different jobs
        if (!result.payloadMatches()) {
            throw new IdempotencyKeyReusedException(idempotencyKey, result.jobId());
        }
        return result;
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
