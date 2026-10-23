package dev.kawa.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KawaClientTest {

    record WelcomeEmail(String to) {
    }

    private static final String JOB_ID = "7f3c2b1a-0000-4000-8000-000000000001";
    private static final String TOKEN = "aaaaaaaa-0000-4000-8000-000000000002";
    private static final String LEASED_JOB = """
            {"id":"%s","queue":"emails","idempotencyKey":null,"payload":{"to":"ada@example.com"},
             "status":"LEASED","attempts":2,"maxAttempts":3,"priority":9,
             "availableAt":"2026-10-24T10:00:00Z","leaseToken":"%s","leaseExpiresAt":"2026-10-24T10:00:30Z",
             "createdAt":"2026-10-24T09:59:00Z","updatedAt":"2026-10-24T10:00:00Z","someFutureField":true}
            """.formatted(JOB_ID, TOKEN);

    private FakeKawaServer server;
    private KawaClient kawa;

    @BeforeEach
    void start() throws IOException {
        server = new FakeKawaServer();
        kawa = KawaClient.create(server.url());
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void enqueueSendsThePayloadAsJsonAndReturnsTheId() {
        server.reply(201, "{\"id\":\"" + JOB_ID + "\"}");

        UUID id = kawa.enqueue("emails", new WelcomeEmail("ada@example.com"));

        assertThat(id).isEqualTo(UUID.fromString(JOB_ID));
        FakeKawaServer.Recorded request = server.lastRequest();
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/api/v1/queues/emails/jobs");
        assertThat(request.header("Content-Type")).isEqualTo("application/json");
        assertThat(json(request.body())).isEqualTo(json("{\"payload\":{\"to\":\"ada@example.com\"}}"));
        assertThat(request.header("Idempotency-Key")).isNull();
    }

    @Test
    void enqueueSendsEveryOptionThatIsSet() {
        server.reply(201, "{\"id\":\"" + JOB_ID + "\"}");

        kawa.enqueue("emails", new WelcomeEmail("ada@example.com"), EnqueueOptions.builder()
                .priority(9)
                .maxAttempts(3)
                .delay(Duration.ofMinutes(10))
                .idempotencyKey("signup-42")
                .build());

        FakeKawaServer.Recorded request = server.lastRequest();
        assertThat(json(request.body())).isEqualTo(json("""
                {"payload":{"to":"ada@example.com"},"priority":9,"maxAttempts":3,"delaySeconds":600}
                """));
        assertThat(request.header("Idempotency-Key")).isEqualTo("signup-42");
    }

    @Test
    void enqueueCanScheduleAnInstant() {
        server.reply(201, "{\"id\":\"" + JOB_ID + "\"}");

        kawa.enqueue("emails", new WelcomeEmail("a@b.com"),
                EnqueueOptions.builder().runAt(Instant.parse("2026-12-01T09:00:00Z")).build());

        assertThat(json(server.lastRequest().body()).get("runAt").asString()).isEqualTo("2026-12-01T09:00:00Z");
    }

    @Test
    void anIdempotentReplayReturnsTheOriginalId() {
        server.reply(200, "{\"id\":\"" + JOB_ID + "\"}");

        UUID id = kawa.enqueue("emails", new WelcomeEmail("a@b.com"),
                EnqueueOptions.builder().idempotencyKey("signup-42").build());

        assertThat(id).isEqualTo(UUID.fromString(JOB_ID));
    }

    @Test
    void takeReturnsTheLeasedJobWithATypedPayload() {
        server.reply(200, LEASED_JOB);

        LeasedJob job = kawa.take("emails", Duration.ofSeconds(30)).orElseThrow();

        assertThat(server.lastRequest().path()).isEqualTo("/api/v1/queues/emails/take");
        assertThat(server.lastRequest().query()).isEqualTo("leaseSeconds=30");
        assertThat(job.id()).isEqualTo(UUID.fromString(JOB_ID));
        assertThat(job.attempts()).isEqualTo(2);
        assertThat(job.priority()).isEqualTo(9);
        assertThat(job.isLastAttempt()).isFalse();
        assertThat(job.leaseExpiresAt()).isEqualTo(Instant.parse("2026-10-24T10:00:30Z"));
        assertThat(job.payload(WelcomeEmail.class)).isEqualTo(new WelcomeEmail("ada@example.com"));
    }

    @Test
    void takeOnAnEmptyQueueIsEmpty() {
        server.replyEmpty(204);

        assertThat(kawa.take("emails", Duration.ofSeconds(30))).isEmpty();
    }

    @Test
    void ackNackAndExtendSendTheLeaseTokenFromTheJob() {
        server.reply(200, LEASED_JOB).replyEmpty(204).replyEmpty(204).replyEmpty(204).replyEmpty(204);
        LeasedJob job = kawa.take("emails", Duration.ofSeconds(30)).orElseThrow();

        kawa.extend(job, Duration.ofSeconds(60));
        assertThat(server.lastRequest().path()).isEqualTo("/api/v1/jobs/" + JOB_ID + "/extend");
        assertThat(json(server.lastRequest().body())).isEqualTo(json("{\"leaseToken\":\"" + TOKEN + "\",\"seconds\":60}"));

        kawa.nack(job);
        assertThat(server.lastRequest().path()).isEqualTo("/api/v1/jobs/" + JOB_ID + "/nack");
        assertThat(json(server.lastRequest().body())).isEqualTo(json("{\"leaseToken\":\"" + TOKEN + "\"}"));

        kawa.nack(job, Duration.ofSeconds(15));
        assertThat(json(server.lastRequest().body())).isEqualTo(json("{\"leaseToken\":\"" + TOKEN + "\",\"seconds\":15}"));

        kawa.ack(job);
        assertThat(server.lastRequest().path()).isEqualTo("/api/v1/jobs/" + JOB_ID + "/ack");
        assertThat(json(server.lastRequest().body())).isEqualTo(json("{\"leaseToken\":\"" + TOKEN + "\"}"));
    }

    @Test
    void getReturnsAJobSnapshot() {
        server.reply(200, LEASED_JOB.replace("\"LEASED\"", "\"DONE\""));

        Job job = kawa.get(UUID.fromString(JOB_ID));

        assertThat(server.lastRequest().method()).isEqualTo("GET");
        assertThat(job.status()).isEqualTo(JobStatus.DONE);
        assertThat(job.status().isTerminal()).isTrue();
        assertThat(job.payload(WelcomeEmail.class).to()).isEqualTo("ada@example.com");
    }

    @Test
    void errorStatusesBecomeTypedExceptionsCarryingTheServersExplanation() {
        server.reply(200, LEASED_JOB)
                .replyProblem(409, "Lease not held", "Job is not leased with this token.")
                .replyProblem(404, "Job not found", "No job exists with id x.")
                .replyProblem(422, "Idempotency key reused", "Key k was already used.")
                .replyProblem(400, "Invalid request", "priority must be between 0 and 9.");
        LeasedJob job = kawa.take("emails", Duration.ofSeconds(30)).orElseThrow();

        assertThatThrownBy(() -> kawa.ack(job))
                .isInstanceOf(LeaseLostException.class)
                .hasMessage("Job is not leased with this token.");
        assertThatThrownBy(() -> kawa.get(UUID.randomUUID()))
                .isInstanceOf(JobNotFoundException.class)
                .hasMessage("No job exists with id x.");
        assertThatThrownBy(() -> kawa.enqueue("emails", "x", EnqueueOptions.builder().idempotencyKey("k").build()))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThatThrownBy(() -> kawa.enqueue("emails", "x", EnqueueOptions.builder().priority(42).build()))
                .isInstanceOf(KawaRequestException.class)
                .hasMessage("priority must be between 0 and 9.")
                .extracting(e -> ((KawaRequestException) e).status()).isEqualTo(400);
    }

    @Test
    void anErrorThatIsNotAProblemDocumentStillProducesAUsefulMessage() {
        server.replyRaw(502, "text/html", "<html>Bad Gateway</html>");

        assertThatThrownBy(() -> kawa.get(UUID.randomUUID()))
                .isInstanceOf(KawaRequestException.class)
                .hasMessage("Kawa answered HTTP 502.");
    }

    @Test
    void queueNamesAreEncodedIntoThePath() {
        server.replyEmpty(204);

        kawa.take("weekly reports/eu", Duration.ofSeconds(30));

        assertThat(server.lastRequest().path()).isEqualTo("/api/v1/queues/weekly%20reports%2Feu/take");
    }

    @Test
    void anUnreachableServerIsAConnectionError() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        KawaClient nowhere = KawaClient.create("http://localhost:" + closedPort);

        assertThatThrownBy(() -> nowhere.take("emails", Duration.ofSeconds(30)))
                .isInstanceOf(KawaConnectionException.class)
                .hasMessageContaining("Could not reach Kawa");
    }

    @Test
    void aTrailingSlashInTheServerUrlIsFine() {
        KawaClient slashed = KawaClient.create(server.url() + "/");
        server.replyEmpty(204);

        Optional<LeasedJob> none = slashed.take("emails", Duration.ofSeconds(30));

        assertThat(none).isEmpty();
        assertThat(server.lastRequest().path()).isEqualTo("/api/v1/queues/emails/take");
    }

    private static JsonNode json(String text) {
        return Json.MAPPER.readTree(text);
    }
}
