package dev.kawa.examples;

import dev.kawa.client.KawaClient;
import dev.kawa.client.KawaWorker;

import java.time.Duration;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;

/**
 * The worker side: runs for every thumbnail job until stopped with Ctrl+C. Start several, each with a
 * different name, and they share the queue without ever getting the same job.
 */
public class Worker {

    public static void main(String[] args) {
        // one line per log record, without stack traces, so the output stays readable
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tT [kawa] %5$s%n");
        String name = args.length > 0 ? args[0] : "worker";
        KawaClient kawa = KawaClient.create(System.getenv().getOrDefault("KAWA_URL", "http://localhost:8080"));

        KawaWorker worker = KawaWorker.builder(kawa, "thumbnails")
                .lease(Duration.ofSeconds(10))
                .onJob(job -> {
                    Thumbnail thumbnail = job.payload(Thumbnail.class);
                    log(name, "resizing " + thumbnail.image() + " to " + thumbnail.width() + "px"
                            + "  (priority " + job.priority() + ", attempt " + job.attempts() + "/" + job.maxAttempts() + ")");
                    Thread.sleep(2_000);
                    if (thumbnail.image().startsWith("corrupted")) {
                        // throwing nacks the job: Kawa retries it later, or marks it DEAD on the last attempt
                        throw new IllegalStateException(thumbnail.image() + " is not a valid image");
                    }
                    log(name, "done     " + thumbnail.image());
                })
                .build();

        worker.start();
        // Ctrl+C: finish the current job first, then stop
        Runtime.getRuntime().addShutdownHook(new Thread(worker::stop));
        log(name, "waiting for jobs on 'thumbnails' (Ctrl+C to stop)");
    }

    private static void log(String worker, String message) {
        System.out.println(LocalTime.now().truncatedTo(ChronoUnit.SECONDS) + " [" + worker + "] " + message);
    }
}
