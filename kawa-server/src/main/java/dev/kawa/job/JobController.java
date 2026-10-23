package dev.kawa.job;

import com.fasterxml.jackson.annotation.JsonRawValue;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Jobs", description = "Enqueue, claim and settle jobs")
public class JobController {

    private final JobService service;

    public JobController(JobService service) {
        this.service = service;
    }

    @Operation(summary = "Enqueue a job", description = "201 with the new job's id. With an Idempotency-Key that "
            + "was already used with the same payload: 200 and the original id (header Idempotent-Replayed: true); "
            + "with a different payload: 422.")
    @PostMapping("/queues/{queue}/jobs")
    public ResponseEntity<EnqueueResponse> enqueue(
            @PathVariable String queue,
            @Parameter(description = "Makes the request safe to retry: 1-128 characters, unique per queue")
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

    @Operation(summary = "Claim the next ready job", description = "Leases the highest-priority, oldest ready job. "
            + "200 with the job and its leaseToken, or 204 when nothing is ready.")
    @PostMapping("/queues/{queue}/take")
    public ResponseEntity<JobResponse> take(@PathVariable String queue,
                                            @Parameter(description = "Lease length, default 30")
                                            @RequestParam(required = false) Integer leaseSeconds) {
        return service.take(queue, leaseSeconds)
                .map(job -> ResponseEntity.ok(JobResponse.from(job)))
                .orElse(ResponseEntity.noContent().build());
    }

    @Operation(summary = "Extend a lease", description = "Keeps the job for `seconds` more. 409 if the lease is no longer held.")
    @PostMapping("/jobs/{id}/extend")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void extend(@PathVariable UUID id, @RequestBody LeaseRequest request) {
        service.extend(id, request.leaseToken(), request.seconds());
    }

    @Operation(summary = "Acknowledge success", description = "Marks the job DONE. 409 if the lease is no longer held.")
    @PostMapping("/jobs/{id}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ack(@PathVariable UUID id, @RequestBody LeaseRequest request) {
        service.ack(id, request.leaseToken());
    }

    @Operation(summary = "Report a failure", description = "Retries the job after `seconds` (default 10), or marks it DEAD "
            + "when it has no attempts left. 409 if the lease is no longer held.")
    @PostMapping("/jobs/{id}/nack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void nack(@PathVariable UUID id, @RequestBody LeaseRequest request) {
        service.nack(id, request.leaseToken(), request.seconds());
    }

    @Operation(summary = "Get a job", description = "The job's current state. 404 if there is no such job.")
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
