package dev.kawa.job;

import dev.kawa.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
    void ackWithStaleTokenIsConflict() {
        String id = (String) http.postForEntity("/api/v1/queues/emails/jobs", Map.of("payload", Map.of()), Map.class)
                .getBody().get("id");
        http.postForEntity("/api/v1/queues/emails/take", null, Map.class);

        ResponseEntity<Void> response = http.postForEntity("/api/v1/jobs/" + id + "/ack",
                Map.of("leaseToken", UUID.randomUUID().toString()), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void badInputIsRejected() {
        assertThat(http.postForEntity("/api/v1/queues/emails/jobs", Map.of(), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.postForEntity("/api/v1/queues/emails/jobs", Map.of("payload", Map.of(), "maxAttempts", 0), Void.class)
                .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/jobs/" + UUID.randomUUID(), Void.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }
}
