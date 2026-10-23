package dev.kawa.job;

import dev.kawa.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DelayedJobTest extends IntegrationTest {

    @Autowired
    TestRestTemplate http;

    @Test
    void delayedJobIsInvisibleToTakeUntilItIsDue() {
        String id = enqueueOk(Map.of("delaySeconds", 3600));

        assertThat(take().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(availableAt(id)).isCloseTo(Instant.now().plus(Duration.ofHours(1)), within(1, ChronoUnit.MINUTES));

        shiftAllSchedules(Duration.ofHours(-2));

        ResponseEntity<Map> taken = take();
        assertThat(taken.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(taken.getBody().get("id")).isEqualTo(id);
    }

    @Test
    void runAtSchedulesForThatExactInstant() {
        Instant runAt = Instant.now().plus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);

        String id = enqueueOk(Map.of("runAt", runAt.toString()));

        assertThat(take().getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(availableAt(id)).isEqualTo(runAt);
    }

    @Test
    void runAtInThePastMeansNow() {
        String id = enqueueOk(Map.of("runAt", "2020-01-01T00:00:00Z"));

        assertThat(availableAt(id)).isAfter(Instant.parse("2025-01-01T00:00:00Z"));
        assertThat(take().getBody().get("id")).isEqualTo(id);
    }

    @Test
    void earlierScheduleIsTakenFirst() {
        String later = enqueueOk(Map.of("delaySeconds", 7200));
        String sooner = enqueueOk(Map.of("delaySeconds", 3600));

        shiftAllSchedules(Duration.ofHours(-3));

        assertThat(take().getBody().get("id")).isEqualTo(sooner);
        assertThat(take().getBody().get("id")).isEqualTo(later);
    }

    @Test
    void invalidSchedulesAreRejected() {
        assertBadRequest(Map.of("runAt", Instant.now().plus(Duration.ofHours(1)).toString(), "delaySeconds", 60),
                "either runAt or delaySeconds");
        assertBadRequest(Map.of("delaySeconds", 31_536_001), "delaySeconds must be between 0 and 31536000");
        assertBadRequest(Map.of("delaySeconds", -1), "delaySeconds must be between 0 and 31536000");
        assertBadRequest(Map.of("runAt", Instant.now().plus(Duration.ofDays(400)).toString()), "at most 1 year");

        ResponseEntity<Map> unparseable = http.postForEntity("/api/v1/queues/emails/jobs",
                Map.of("payload", Map.of(), "runAt", "tomorrow morning"), Map.class);
        assertThat(unparseable.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private String enqueueOk(Map<String, Object> schedule) {
        Map<String, Object> body = new HashMap<>(schedule);
        body.put("payload", Map.of("to", "a@b.com"));
        ResponseEntity<Map> response = http.postForEntity("/api/v1/queues/emails/jobs", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    private void assertBadRequest(Map<String, Object> schedule, String detailFragment) {
        Map<String, Object> body = new HashMap<>(schedule);
        body.put("payload", Map.of());
        ResponseEntity<Map> response = http.postForEntity("/api/v1/queues/emails/jobs", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat((String) response.getBody().get("detail")).contains(detailFragment);
    }

    private ResponseEntity<Map> take() {
        return http.postForEntity("/api/v1/queues/emails/take", null, Map.class);
    }

    private Instant availableAt(String id) {
        return Instant.parse((String) http.getForObject("/api/v1/jobs/" + id, Map.class).get("availableAt"));
    }

    // stands in for waiting: moves every schedule by the given amount instead of sleeping
    private void shiftAllSchedules(Duration shift) {
        jdbcClient.sql("UPDATE jobs SET available_at = available_at + :seconds * INTERVAL '1 second'")
                .param("seconds", shift.toSeconds())
                .update();
    }
}
