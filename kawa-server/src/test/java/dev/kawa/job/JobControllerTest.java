package dev.kawa.job;

import dev.kawa.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobControllerTest extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void fullLifecycleOverHttp() {
        ResponseEntity<Map> enqueued = http.postForEntity("/api/v1/queues/emails/jobs",
                Map.of("payload", Map.of("to", "a@b.com")), Map.class);
        assertThat(enqueued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String id = (String) enqueued.getBody().get("id");

        ResponseEntity<Map> taken = http.postForEntity("/api/v1/queues/emails/take?leaseSeconds=30", null, Map.class);
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(taken.getBody().get("payload")).isEqualTo(Map.of("to", "a@b.com"));
        String token = (String) taken.getBody().get("leaseToken");

        ResponseEntity<Void> acked = http.postForEntity("/api/v1/jobs/" + id + "/ack", Map.of("leaseToken", token), Void.class);
        assertThat(acked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<?, ?> job = http.getForObject("/api/v1/jobs/" + id, Map.class);
        assertThat(job.get("status")).isEqualTo("DONE");
    }

    @Test
    void takeOnEmptyQueueIsNoContent() {
        assertThat(http.postForEntity("/api/v1/queues/empty/take", null, Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void ackWithStaleTokenIsConflictProblem() {
        String id = (String) http.postForEntity("/api/v1/queues/emails/jobs", Map.of("payload", Map.of()), Map.class)
                .getBody().get("id");
        http.postForEntity("/api/v1/queues/emails/take", null, Map.class);

        ResponseEntity<Map> response = http.postForEntity("/api/v1/jobs/" + id + "/ack",
                Map.of("leaseToken", UUID.randomUUID().toString()), Map.class);

        assertProblem(response, HttpStatus.CONFLICT, "Lease not held");
        assertThat(response.getBody().get("jobId")).isEqualTo(id);
        assertThat((String) response.getBody().get("detail")).contains("not leased with this token");
    }

    @Test
    void unknownJobIsNotFoundProblem() {
        UUID id = UUID.randomUUID();

        ResponseEntity<Map> response = http.getForEntity("/api/v1/jobs/" + id, Map.class);

        assertProblem(response, HttpStatus.NOT_FOUND, "Job not found");
        assertThat(response.getBody().get("jobId")).isEqualTo(id.toString());
    }

    @Test
    void invalidInputIsBadRequestProblemNamingTheField() {
        ResponseEntity<Map> noPayload = http.postForEntity("/api/v1/queues/emails/jobs", Map.of(), Map.class);
        assertProblem(noPayload, HttpStatus.BAD_REQUEST, "Invalid request");
        assertThat((String) noPayload.getBody().get("detail")).contains("payload is required");

        ResponseEntity<Map> zeroAttempts = http.postForEntity("/api/v1/queues/emails/jobs",
                Map.of("payload", Map.of(), "maxAttempts", 0), Map.class);
        assertProblem(zeroAttempts, HttpStatus.BAD_REQUEST, "Invalid request");
        assertThat((String) zeroAttempts.getBody().get("detail")).contains("maxAttempts must be at least 1");

        ResponseEntity<Map> zeroLease = http.postForEntity("/api/v1/queues/emails/take?leaseSeconds=0", null, Map.class);
        assertProblem(zeroLease, HttpStatus.BAD_REQUEST, "Invalid request");
        assertThat((String) zeroLease.getBody().get("detail")).contains("leaseSeconds must be at least 1");
    }

    @Test
    void malformedRequestsUseTheSameProblemFormat() {
        RequestEntity<String> brokenJson = RequestEntity.post(URI.create("/api/v1/queues/emails/jobs"))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{not json");
        assertThat(http.exchange(brokenJson, Map.class).getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);

        ResponseEntity<Map> badId = http.getForEntity("/api/v1/jobs/not-a-uuid", Map.class);
        assertThat(badId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badId.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
    }

    private static void assertProblem(ResponseEntity<Map> response, HttpStatus status, String title) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("status")).isEqualTo(status.value());
        assertThat(response.getBody().get("title")).isEqualTo(title);
    }
}
