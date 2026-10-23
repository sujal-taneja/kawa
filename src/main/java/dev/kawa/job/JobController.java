package dev.kawa.job;

import com.fasterxml.jackson.annotation.JsonRawValue;
import tools.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @PostMapping("/queues/{queue}/jobs")
    public ResponseEntity<EnqueueResponse> enqueue(
            @PathVariable String queue,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody EnqueueRequest request) {
        if (request.payload() == null || request.payload().isNull()) {
            throw new InvalidJobRequestException("payload is required and must be a JSON value.");
        }
        EnqueueResult result = service.enqueue(queue, request.payload().toString(), request.maxAttempts(),
                request.priority(), idempotencyKey, request.runAt(), request.delaySeconds());
        EnqueueResponse body = new EnqueueResponse(result.jobId());
        if (result.created()) {
            return ResponseEntity.status(HttpStatus.CREATED).body(body);
        }
        return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(body);
    }

    @PostMapping("/queues/{queue}/take")
    public ResponseEntity<JobResponse> take(@PathVariable String queue,
                                            @RequestParam(required = false) Integer leaseSeconds) {
        return service.take(queue, leaseSeconds)
                .map(job -> ResponseEntity.ok(JobResponse.from(job)))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/jobs/{id}/extend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void extend(@PathVariable UUID id, @RequestBody LeaseRequest request) {
        service.extend(id, request.leaseToken(), request.seconds());
    }

    @PostMapping("/jobs/{id}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ack(@PathVariable UUID id, @RequestBody LeaseRequest request) {
        service.ack(id, request.leaseToken());
    }

    @PostMapping("/jobs/{id}/nack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void nack(@PathVariable UUID id, @RequestBody LeaseRequest request) {
        service.nack(id, request.leaseToken(), request.seconds());
    }

    @GetMapping("/jobs/{id}")
    public JobResponse get(@PathVariable UUID id) {
        return JobResponse.from(service.get(id));
    }

    public record EnqueueRequest(JsonNode payload, Integer maxAttempts, Integer priority, Instant runAt,
                                 Integer delaySeconds) {
    }

    public record EnqueueResponse(UUID id) {
    }

    // seconds = lease length for extend, retry delay for nack, ignored for ack
    public record LeaseRequest(UUID leaseToken, Integer seconds) {
    }

    public record JobResponse(
            UUID id,
            String queue,
            String idempotencyKey,
            @JsonRawValue String payload,
            JobStatus status,
            int attempts,
            int maxAttempts,
            int priority,
            Instant availableAt,
            UUID leaseToken,
            Instant leaseExpiresAt,
            Instant createdAt,
            Instant updatedAt
    ) {
        static JobResponse from(Job job) {
            return new JobResponse(job.id(), job.queue(), job.idempotencyKey(), job.payload(), job.status(), job.attempts(),
                    job.maxAttempts(), job.priority(), job.availableAt(), job.leaseToken(), job.leaseExpiresAt(),
                    job.createdAt(), job.updatedAt());
        }
    }
}
