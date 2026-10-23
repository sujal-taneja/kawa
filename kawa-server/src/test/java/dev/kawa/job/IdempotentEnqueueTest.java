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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotentEnqueueTest extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Autowired
    JobRepository repository;

    @Test
    void sameKeyAndPayloadReturnsTheOriginalJob() {
        ResponseEntity<Map> first = enqueue("emails", "key-1", Map.of("to", "a@b.com"));
        ResponseEntity<Map> retry = enqueue("emails", "key-1", Map.of("to", "a@b.com"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst("Idempotent-Replayed")).isNull();
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        assertThat(jobCount()).isEqualTo(1);

        Map<?, ?> job = http.getForObject("/api/v1/jobs/" + first.getBody().get("id"), Map.class);
        assertThat(job.get("idempotencyKey")).isEqualTo("key-1");
    }

    @Test
    void payloadIsComparedAsJsonNotAsText() {
        ResponseEntity<Map> first = enqueue("emails", "key-1", "{\"a\": 1, \"b\": 2}");
        ResponseEntity<Map> reordered = enqueue("emails", "key-1", "{\"b\":2,\"a\":1}");

        assertThat(reordered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reordered.getBody().get("id")).isEqualTo(first.getBody().get("id"));
    }

    @Test
    void sameKeyWithDifferentPayloadIsRejected() {
        ResponseEntity<Map> first = enqueue("emails", "key-1", Map.of("to", "a@b.com"));
        ResponseEntity<Map> conflicting = enqueue("emails", "key-1", Map.of("to", "someone-else@b.com"));

        assertThat(conflicting.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        assertThat(conflicting.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(conflicting.getBody().get("title")).isEqualTo("Idempotency key reused");
        assertThat(conflicting.getBody().get("jobId")).isEqualTo(first.getBody().get("id"));
        assertThat(jobCount()).isEqualTo(1);
    }

    @Test
    void keysAreScopedPerQueue() {
        ResponseEntity<Map> emails = enqueue("emails", "key-1", Map.of("n", 1));
        ResponseEntity<Map> reports = enqueue("reports", "key-1", Map.of("n", 1));

        assertThat(emails.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reports.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(reports.getBody().get("id")).isNotEqualTo(emails.getBody().get("id"));
    }

    @Test
    void withoutAKeyEveryCallCreatesAJob() {
        assertThat(enqueue("emails", null, Map.of("n", 1)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(enqueue("emails", null, Map.of("n", 1)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jobCount()).isEqualTo(2);
    }

    @Test
    void blankOrOversizedKeysAreRejected() {
        assertThat(enqueue("emails", "   ", Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(enqueue("emails", "k".repeat(129), Map.of()).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(enqueue("emails", "k".repeat(128), Map.of()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void twentyConcurrentRetriesCreateExactlyOneJob() throws Exception {
        int callers = 20;
        CountDownLatch startTogether = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        List<Future<EnqueueResult>> results = new ArrayList<>();
        for (int i = 0; i < callers; i++) {
            results.add(pool.submit(() -> {
                startTogether.await();
                return repository.enqueue(new NewJob("emails", "{\"to\":\"a@b.com\"}", 3, 0, "same-key", null, 0));
            }));
        }

        startTogether.countDown();
        List<EnqueueResult> outcomes = new ArrayList<>();
        for (Future<EnqueueResult> result : results) {
            outcomes.add(result.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(outcomes).filteredOn(EnqueueResult::created).hasSize(1);
        assertThat(outcomes).extracting(EnqueueResult::jobId).containsOnly(outcomes.get(0).jobId());
        assertThat(outcomes).allMatch(EnqueueResult::payloadMatches);
        assertThat(jobCount()).isEqualTo(1);
    }

    private ResponseEntity<Map> enqueue(String queue, String idempotencyKey, Object payload) {
        String payloadJson = payload instanceof String json ? json : null;
        RequestEntity.BodyBuilder request = RequestEntity.post(URI.create("/api/v1/queues/" + queue + "/jobs"))
                .contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        Object body = payloadJson != null ? "{\"payload\": " + payloadJson + "}" : Map.of("payload", payload);
        return http.exchange(request.body(body), Map.class);
    }

    private long jobCount() {
        return jdbcClient.sql("SELECT count(*) FROM jobs").query(Long.class).single();
    }
}
