package dev.kawa.job;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "maxAttempts must be at least 1");
        }
        return repository.enqueue(queue, payloadJson, attempts);
    }

    public Optional<Job> take(String queue, Integer leaseSeconds) {
        return repository.take(queue, durationOr(leaseSeconds, DEFAULT_LEASE));
    }

    public void extend(UUID id, UUID leaseToken, Integer leaseSeconds) {
        requireLeaseHeld(repository.extend(id, leaseToken, durationOr(leaseSeconds, DEFAULT_LEASE)));
    }

    public void ack(UUID id, UUID leaseToken) {
        requireLeaseHeld(repository.ack(id, leaseToken));
    }

    public void nack(UUID id, UUID leaseToken, Integer retryDelaySeconds) {
        requireLeaseHeld(repository.nack(id, leaseToken, durationOr(retryDelaySeconds, DEFAULT_RETRY_DELAY)));
    }

    public Job get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found"));
    }

    // false means the token didn't match: the lease expired and was reaped, or the job was already settled
    private static void requireLeaseHeld(boolean updated) {
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "lease not held: expired, already settled, or wrong token");
        }
    }

    private static Duration durationOr(Integer seconds, Duration fallback) {
        if (seconds == null) {
            return fallback;
        }
        if (seconds < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "durations must be at least 1 second");
        }
        return Duration.ofSeconds(seconds);
    }
}
