package dev.kawa.job;

import dev.kawa.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JobRepositoryTest extends IntegrationTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration NO_DELAY = Duration.ZERO;

    @Autowired
    JobRepository repository;

    @Test
    void takeLeasesTheOldestPendingJob() {
        UUID first = repository.enqueue("emails", "{\"n\":1}", 3);
        repository.enqueue("emails", "{\"n\":2}", 3);

        Job job = repository.take("emails", LEASE).orElseThrow();

        assertThat(job.id()).isEqualTo(first);
        assertThat(job.status()).isEqualTo(JobStatus.LEASED);
        assertThat(job.attempts()).isEqualTo(1);
        assertThat(job.leaseToken()).isNotNull();
        assertThat(job.leaseExpiresAt()).isAfter(Instant.now());
        assertThat(job.payload()).isEqualTo("{\"n\": 1}");
    }

    @Test
    void takeReturnsEmptyWhenNothingIsRunnable() {
        repository.enqueue("other-queue", "{}", 3);
        UUID leased = repository.enqueue("emails", "{}", 3);
        repository.take("emails", LEASE);

        assertThat(repository.take("emails", LEASE)).isEmpty();
        assertThat(repository.findById(leased).orElseThrow().status()).isEqualTo(JobStatus.LEASED);
    }

    @Test
    void ackMarksDoneOnlyWithTheCurrentLeaseToken() {
        UUID id = repository.enqueue("emails", "{}", 3);
        Job job = repository.take("emails", LEASE).orElseThrow();

        assertThat(repository.ack(id, UUID.randomUUID())).isFalse();
        assertThat(repository.ack(id, job.leaseToken())).isTrue();
        assertThat(repository.ack(id, job.leaseToken())).isFalse();
        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(JobStatus.DONE);
    }

    @Test
    void nackRetriesUntilMaxAttemptsThenDies() {
        UUID id = repository.enqueue("emails", "{}", 2);

        Job firstTry = repository.take("emails", LEASE).orElseThrow();
        assertThat(repository.nack(id, firstTry.leaseToken(), NO_DELAY)).isTrue();
        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(JobStatus.PENDING);

        Job secondTry = repository.take("emails", LEASE).orElseThrow();
        assertThat(secondTry.attempts()).isEqualTo(2);
        assertThat(repository.nack(id, secondTry.leaseToken(), NO_DELAY)).isTrue();

        Job dead = repository.findById(id).orElseThrow();
        assertThat(dead.status()).isEqualTo(JobStatus.DEAD);
        assertThat(dead.status().isTerminal()).isTrue();
        assertThat(repository.take("emails", LEASE)).isEmpty();
    }

    @Test
    void nackDelayHidesTheJobUntilItIsDue() {
        UUID id = repository.enqueue("emails", "{}", 3);
        Job job = repository.take("emails", LEASE).orElseThrow();

        repository.nack(id, job.leaseToken(), Duration.ofHours(1));

        assertThat(repository.take("emails", LEASE)).isEmpty();
    }

    @Test
    void extendPushesTheLeaseExpiryOut() {
        UUID id = repository.enqueue("emails", "{}", 3);
        Job job = repository.take("emails", Duration.ofSeconds(5)).orElseThrow();

        assertThat(repository.extend(id, job.leaseToken(), Duration.ofMinutes(10))).isTrue();

        Instant newExpiry = repository.findById(id).orElseThrow().leaseExpiresAt();
        assertThat(newExpiry).isAfter(job.leaseExpiresAt().plus(Duration.ofMinutes(9)));
        assertThat(repository.extend(id, UUID.randomUUID(), LEASE)).isFalse();
    }

    @Test
    void reaperReturnsExpiredLeasesToPendingAndInvalidatesTheOldToken() {
        UUID id = repository.enqueue("emails", "{}", 3);
        Job job = repository.take("emails", LEASE).orElseThrow();
        expireLease(id);

        assertThat(repository.reapExpiredLeases()).isEqualTo(1);

        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(JobStatus.PENDING);
        assertThat(repository.ack(id, job.leaseToken())).isFalse();
        assertThat(repository.take("emails", LEASE).orElseThrow().attempts()).isEqualTo(2);
    }

    @Test
    void reaperKillsAJobWhoseLastAttemptExpired() {
        UUID id = repository.enqueue("emails", "{}", 1);
        repository.take("emails", LEASE);
        expireLease(id);

        repository.reapExpiredLeases();

        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(JobStatus.DEAD);
    }

    @Test
    void reaperLeavesLiveLeasesAlone() {
        UUID id = repository.enqueue("emails", "{}", 3);
        repository.take("emails", LEASE);

        assertThat(repository.reapExpiredLeases()).isZero();
        assertThat(repository.findById(id).orElseThrow().status()).isEqualTo(JobStatus.LEASED);
    }

    private void expireLease(UUID id) {
        jdbcClient.sql("UPDATE jobs SET lease_expires_at = now() - INTERVAL '1 second' WHERE id = :id")
                .param("id", id)
                .update();
    }
}
