package dev.kawa.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KawaWorkerTest {

    record WelcomeEmail(String to) {
    }

    private static final String TOKEN = "aaaaaaaa-0000-4000-8000-000000000002";

    private FakeKawaServer server;
    private KawaClient kawa;
    private KawaWorker worker;

    @BeforeEach
    void start() throws IOException {
        server = new FakeKawaServer().thenEmptyQueue();
        kawa = KawaClient.create(server.url());
    }

    @AfterEach
    void stop() {
        if (worker != null) {
            worker.stop(Duration.ofSeconds(5));
        }
        server.close();
    }

    @Test
    void successfulHandlerGetsTheTypedPayloadAndTheJobIsAcked() {
        server.reply(200, leasedJob(1, "ada@example.com")).replyEmpty(204);
        List<WelcomeEmail> handled = new CopyOnWriteArrayList<>();

        worker = fastWorker().onJob(WelcomeEmail.class, handled::add).build();
        worker.start();

        eventually(() -> worker.jobsCompleted() == 1);
        assertThat(handled).containsExactly(new WelcomeEmail("ada@example.com"));
        eventually(() -> sent(id(1) + "/ack"));
        assertThat(request(id(1) + "/ack").body()).contains(TOKEN);
    }

    @Test
    void throwingHandlerNacksTheJobAndTheWorkerKeepsGoing() {
        server.reply(200, leasedJob(1, "first@example.com")).replyEmpty(204)
                .reply(200, leasedJob(2, "second@example.com")).replyEmpty(204);

        worker = fastWorker().onJob(WelcomeEmail.class, email -> {
            if (email.to().startsWith("first")) {
                throw new IllegalStateException("mail server down");
            }
        }).build();
        worker.start();

        eventually(() -> sent(id(1) + "/nack") && sent(id(2) + "/ack"));
        assertThat(worker.jobsFailed()).isEqualTo(1);
        assertThat(worker.jobsCompleted()).isEqualTo(1);
        assertThat(worker.isRunning()).isTrue();
    }

    @Test
    void stopLetsTheRunningJobFinishAndSettleFirst() throws Exception {
        server.reply(200, leasedJob(1, "ada@example.com")).replyEmpty(204);
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);

        worker = fastWorker().onJob(job -> {
            handlerStarted.countDown();
            releaseHandler.await();
        }).build();
        worker.start();
        assertThat(handlerStarted.await(5, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Boolean> stopped = CompletableFuture.supplyAsync(() -> worker.stop(Duration.ofSeconds(5)));
        Thread.sleep(200);
        assertThat(stopped).isNotDone();

        releaseHandler.countDown();

        assertThat(stopped.get(5, TimeUnit.SECONDS)).isTrue();
        assertThat(worker.isRunning()).isFalse();
        assertThat(lastPath()).endsWith("/ack");
    }

    @Test
    void stoppingAnIdleWorkerIsImmediateEvenWithALongPollInterval() {
        worker = KawaWorker.builder(kawa, "emails").pollInterval(Duration.ofMinutes(5)).onJob(job -> { }).build();
        worker.start();
        eventually(() -> !server.requests().isEmpty());

        long startedAt = System.nanoTime();
        assertThat(worker.stop(Duration.ofSeconds(5))).isTrue();

        assertThat(Duration.ofNanos(System.nanoTime() - startedAt)).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void aLostLeaseOrAnUnreachableServerDoesNotKillTheWorker() {
        server.replyRaw(503, "text/plain", "overloaded")
                .reply(200, leasedJob(1, "first@example.com"))
                .replyProblem(409, "Lease not held", "Job is not leased with this token.")
                .reply(200, leasedJob(2, "second@example.com")).replyEmpty(204);

        worker = fastWorker().onJob(job -> { }).build();
        worker.start();

        eventually(() -> worker.jobsCompleted() == 2 && sent(id(2) + "/ack"));
        assertThat(worker.isRunning()).isTrue();
    }

    @Test
    void aWorkerNeedsAHandlerAndStartsOnlyOnce() {
        assertThatThrownBy(() -> KawaWorker.builder(kawa, "emails").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("onJob");

        worker = fastWorker().onJob(job -> { }).build();
        worker.start();
        assertThatThrownBy(worker::start).isInstanceOf(IllegalStateException.class);
    }

    private KawaWorker.Builder fastWorker() {
        return KawaWorker.builder(kawa, "emails")
                .pollInterval(Duration.ofMillis(20))
                .errorBackoff(Duration.ofMillis(20));
    }

    private String lastPath() {
        return server.lastRequest().path();
    }

    // a running worker keeps polling after it settles a job, so look for the request anywhere, not just last
    private boolean sent(String pathSuffix) {
        return server.requests().stream().anyMatch(request -> request.path().endsWith(pathSuffix));
    }

    private FakeKawaServer.Recorded request(String pathSuffix) {
        return server.requests().stream().filter(request -> request.path().endsWith(pathSuffix)).findFirst().orElseThrow();
    }

    private static String id(int n) {
        return "7f3c2b1a-0000-4000-8000-00000000000" + n;
    }

    private static String leasedJob(int n, String to) {
        return """
                {"id":"%s","queue":"emails","payload":{"to":"%s"},"status":"LEASED","attempts":1,"maxAttempts":5,
                 "priority":0,"availableAt":"2026-10-24T10:00:00Z","leaseToken":"%s",
                 "leaseExpiresAt":"2026-10-24T10:00:30Z","createdAt":"2026-10-24T10:00:00Z","updatedAt":"2026-10-24T10:00:00Z"}
                """.formatted(id(n), to, TOKEN);
    }

    private static void eventually(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5 seconds");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
