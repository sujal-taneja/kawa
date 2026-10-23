import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal Kawa worker with no dependencies. Run it with:
 *
 *   java examples/DemoWorker.java [baseUrl] [queue] [name] [workSeconds]
 *
 * It takes a job, "works" on it for workSeconds, then acks it. Jobs whose payload
 * contains "fail": true are nacked instead, to show retries and dead-lettering.
 */
public class DemoWorker {

    private static final int LEASE_SECONDS = 10;
    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");
    private static final Pattern TOKEN = Pattern.compile("\"leaseToken\":\"([0-9a-f-]{36})\"");
    private static final Pattern ATTEMPTS = Pattern.compile("\"attempts\":(\\d+)");
    private static final Pattern PAYLOAD = Pattern.compile("\"payload\":(\\{.*?\\})");

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String queue;
    private final String name;
    private final int workSeconds;

    DemoWorker(String baseUrl, String queue, String name, int workSeconds) {
        this.baseUrl = baseUrl;
        this.queue = queue;
        this.name = name;
        this.workSeconds = workSeconds;
    }

    public static void main(String[] args) throws Exception {
        String baseUrl = args.length > 0 ? args[0] : "http://localhost:8080";
        String queue = args.length > 1 ? args[1] : "demo";
        String name = args.length > 2 ? args[2] : "worker-" + ProcessHandle.current().pid();
        int workSeconds = args.length > 3 ? Integer.parseInt(args[3]) : 3;
        new DemoWorker(baseUrl, queue, name, workSeconds).run();
    }

    void run() throws Exception {
        log("polling queue '%s' at %s", queue, baseUrl);
        while (true) {
            HttpResponse<String> taken = post("/api/v1/queues/" + queue + "/take?leaseSeconds=" + LEASE_SECONDS, null);
            if (taken.statusCode() == 204) {
                Thread.sleep(1000);
                continue;
            }
            if (taken.statusCode() != 200) {
                log("take failed: HTTP %d %s", taken.statusCode(), taken.body());
                Thread.sleep(2000);
                continue;
            }

            String body = taken.body();
            String id = extract(ID, body);
            String token = extract(TOKEN, body);
            String payload = extract(PAYLOAD, body);
            log("took %s (attempt %s) payload=%s", id, extract(ATTEMPTS, body), payload);

            Thread.sleep(workSeconds * 1000L);

            String lease = "{\"leaseToken\":\"" + token + "\"}";
            boolean shouldFail = payload.replace(" ", "").contains("\"fail\":true");
            HttpResponse<String> settled = post("/api/v1/jobs/" + id + (shouldFail ? "/nack" : "/ack"), lease);
            if (settled.statusCode() == 204) {
                log("%s %s", shouldFail ? "nacked" : "acked  ", id);
            } else {
                log("could not settle %s: HTTP %d (lease lost?)", id, settled.statusCode());
            }
        }
    }

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json");
        request.POST(json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json));
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String extract(Pattern pattern, String json) {
        Matcher matcher = pattern.matcher(json);
        return matcher.find() ? matcher.group(1) : "?";
    }

    private void log(String format, Object... args) {
        System.out.printf("%s [%s] %s%n", LocalTime.now().truncatedTo(ChronoUnit.SECONDS), name, String.format(format, args));
    }
}
