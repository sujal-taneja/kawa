package dev.kawa.job;

import dev.kawa.IntegrationTest;
import dev.kawa.client.EnqueueOptions;
import dev.kawa.client.IdempotencyKeyReusedException;
import dev.kawa.client.JobNotFoundException;
import dev.kawa.client.JobStatus;
import dev.kawa.client.KawaClient;
import dev.kawa.client.KawaRequestException;
import dev.kawa.client.LeaseLostException;
import dev.kawa.client.LeasedJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The published Java client against the real server and database: proves the two agree on the API.
 */
class ClientEndToEndTest extends IntegrationTest {

    record WelcomeEmail(String to) {
    }

    private static final Duration LEASE = Duration.ofSeconds(30);

    @LocalServerPort
    int port;

    KawaClient kawa;

    @BeforeEach
    void connect() {
        kawa = KawaClient.create("http://localhost:" + port);
    }

    @Test
    void enqueueTakeAndAckWithTypedPayloads() {
        UUID id = kawa.enqueue("emails", new WelcomeEmail("ada@example.com"));

        LeasedJob job = kawa.take("emails", LEASE).orElseThrow();
        assertThat(job.id()).isEqualTo(id);
        assertThat(job.payload(WelcomeEmail.class)).isEqualTo(new WelcomeEmail("ada@example.com"));

        kawa.ack(job);

        assertThat(kawa.get(id).status()).isEqualTo(JobStatus.DONE);
        assertThat(kawa.take("emails", LEASE)).isEmpty();
    }

    @Test
    void optionsReachTheServer() {
        UUID normal = kawa.enqueue("emails", new WelcomeEmail("a@b.com"));
        UUID urgent = kawa.enqueue("emails", new WelcomeEmail("urgent@b.com"),
                EnqueueOptions.builder().priority(9).maxAttempts(2).idempotencyKey("urgent-1").build());
        UUID later = kawa.enqueue("emails", new WelcomeEmail("later@b.com"),
                EnqueueOptions.builder().delay(Duration.ofHours(1)).build());

        assertThat(kawa.get(urgent).maxAttempts()).isEqualTo(2);
        assertThat(kawa.get(urgent).idempotencyKey()).isEqualTo("urgent-1");
        assertThat(kawa.take("emails", LEASE).orElseThrow().id()).isEqualTo(urgent);
        assertThat(kawa.take("emails", LEASE).orElseThrow().id()).isEqualTo(normal);
        assertThat(kawa.take("emails", LEASE)).isEmpty();
        assertThat(kawa.get(later).status()).isEqualTo(JobStatus.PENDING);
    }

    @Test
    void retryingAnEnqueueWithTheSameKeyReturnsTheSameJob() {
        EnqueueOptions keyed = EnqueueOptions.builder().idempotencyKey("signup-42").build();

        UUID first = kawa.enqueue("emails", new WelcomeEmail("ada@example.com"), keyed);
        UUID retry = kawa.enqueue("emails", new WelcomeEmail("ada@example.com"), keyed);

        assertThat(retry).isEqualTo(first);
        assertThatThrownBy(() -> kawa.enqueue("emails", new WelcomeEmail("bob@example.com"), keyed))
                .isInstanceOf(IdempotencyKeyReusedException.class)
                .hasMessageContaining("signup-42");
    }

    @Test
    void nackRetriesAndThenKillsTheJob() {
        UUID id = kawa.enqueue("emails", new WelcomeEmail("a@b.com"), EnqueueOptions.builder().maxAttempts(2).build());

        LeasedJob firstTry = kawa.take("emails", LEASE).orElseThrow();
        assertThat(firstTry.isLastAttempt()).isFalse();
        kawa.nack(firstTry, Duration.ofSeconds(1));
        jdbcClient.sql("UPDATE jobs SET available_at = now()").update();

        LeasedJob secondTry = kawa.take("emails", LEASE).orElseThrow();
        assertThat(secondTry.isLastAttempt()).isTrue();
        kawa.nack(secondTry);

        assertThat(kawa.get(id).status()).isEqualTo(JobStatus.DEAD);
    }

    @Test
    void aStaleWorkerGetsLeaseLost() {
        kawa.enqueue("emails", new WelcomeEmail("a@b.com"));
        LeasedJob job = kawa.take("emails", LEASE).orElseThrow();
        kawa.ack(job);

        assertThatThrownBy(() -> kawa.ack(job))
                .isInstanceOf(LeaseLostException.class)
                .hasMessageContaining("not leased with this token");
        assertThatThrownBy(() -> kawa.extend(job, LEASE)).isInstanceOf(LeaseLostException.class);
    }

    @Test
    void serverValidationErrorsAndMissingJobsBecomeTypedExceptions() {
        assertThatThrownBy(() -> kawa.get(UUID.randomUUID())).isInstanceOf(JobNotFoundException.class);
        assertThatThrownBy(() -> kawa.enqueue("emails", new WelcomeEmail("a@b.com"),
                EnqueueOptions.builder().priority(10).build()))
                .isInstanceOf(KawaRequestException.class)
                .hasMessageContaining("priority must be between 0 and 9");
    }
}
