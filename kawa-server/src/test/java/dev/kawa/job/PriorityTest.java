package dev.kawa.job;

import dev.kawa.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PriorityTest extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void urgentJobEnqueuedLastIsTakenFirst() {
        for (int i = 0; i < 5; i++) {
            enqueue(Map.of());
        }
        String urgent = enqueue(Map.of("priority", 9));

        Map<?, ?> taken = take().getBody();

        assertThat(taken.get("id")).isEqualTo(urgent);
        assertThat(taken.get("priority")).isEqualTo(9);
    }

    @Test
    void samePriorityIsTakenOldestFirst() {
        String first = enqueue(Map.of("priority", 5));
        String second = enqueue(Map.of("priority", 5));

        assertThat(take().getBody().get("id")).isEqualTo(first);
        assertThat(take().getBody().get("id")).isEqualTo(second);
    }

    @Test
    void scheduledUrgentJobDoesNotBlockAReadyNormalOne() {
        enqueue(Map.of("priority", 9, "delaySeconds", 3600));
        String ready = enqueue(Map.of());

        assertThat(take().getBody().get("id")).isEqualTo(ready);
        assertThat(take().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    void retriedJobKeepsItsPriority() {
        String urgent = enqueue(Map.of("priority", 9));
        Map<?, ?> firstTry = take().getBody();
        http.postForEntity("/api/v1/jobs/" + urgent + "/nack",
                Map.of("leaseToken", firstTry.get("leaseToken"), "seconds", 1), Void.class);
        enqueue(Map.of());
        jdbcClient.sql("UPDATE jobs SET available_at = now() - INTERVAL '1 second'").update();

        Map<?, ?> retry = take().getBody();

        assertThat(retry.get("id")).isEqualTo(urgent);
        assertThat(retry.get("priority")).isEqualTo(9);
        assertThat(retry.get("attempts")).isEqualTo(2);
    }

    @Test
    void priorityDefaultsToZeroAndMustBeZeroToNine() {
        String plain = enqueue(Map.of());
        assertThat(http.getForObject("/api/v1/jobs/" + plain, Map.class).get("priority")).isEqualTo(0);

        for (int invalid : new int[] {-1, 10}) {
            ResponseEntity<Map> response = http.postForEntity("/api/v1/queues/emails/jobs",
                    Map.of("payload", Map.of(), "priority", invalid), Map.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat((String) response.getBody().get("detail")).contains("priority must be between 0 and 9");
        }
    }

    private String enqueue(Map<String, Object> options) {
        Map<String, Object> body = new HashMap<>(options);
        body.put("payload", Map.of("n", 1));
        ResponseEntity<Map> response = http.postForEntity("/api/v1/queues/emails/jobs", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    private ResponseEntity<Map> take() {
        return http.postForEntity("/api/v1/queues/emails/take", null, Map.class);
    }
}
