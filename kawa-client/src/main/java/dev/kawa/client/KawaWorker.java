package dev.kawa.client;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static java.lang.System.Logger.Level.INFO;
import static java.lang.System.Logger.Level.WARNING;

/**
 * Runs your code for every job in a queue: takes a job, calls your handler, acks it when the handler
 * returns and nacks it when the handler throws. One job at a time, on one background thread.
 *
 * <pre>{@code
 * KawaWorker worker = KawaWorker.builder(kawa, "emails")
 *         .onJob(WelcomeEmail.class, email -> mailer.send(email))
 *         .build();
 * worker.start();
 * Runtime.getRuntime().addShutdownHook(new Thread(worker::stop));
 * }</pre>
 *
 * Delivery is at-least-once: a job can reach your handler twice (for example if the worker dies after
 * the handler finished but before the ack), so handlers should be safe to repeat. There is no automatic
 * lease renewal: pick a lease longer than your slowest job, or call {@link KawaClient#extend} yourself.
 */
public final class KawaWorker {

    private static final System.Logger LOG = System.getLogger(KawaWorker.class.getName());

    private final KawaClient kawa;
    private final String queue;
    private final JobHandler handler;
    private final Duration lease;
    private final Duration pollInterval;
    private final Duration errorBackoff;

    // stop() opens this latch; the loop checks it between jobs and waits on it while idle,
    // so stopping wakes an idle worker at once but never interrupts a running handler
    private final CountDownLatch stopSignal = new CountDownLatch(1);
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile Thread thread;

    private KawaWorker(Builder builder) {
        this.kawa = builder.kawa;
        this.queue = builder.queue;
        this.handler = builder.handler;
        this.lease = builder.lease;
        this.pollInterval = builder.pollInterval;
        this.errorBackoff = builder.errorBackoff;
    }

    public static Builder builder(KawaClient kawa, String queue) {
        return new Builder(kawa, queue);
    }

    /** Starts processing on a background thread named {@code kawa-worker-<queue>}. Can be called once. */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            throw new IllegalStateException("This worker was already started; create a new one to run again.");
        }
        Thread worker = new Thread(this::run, "kawa-worker-" + queue);
        thread = worker;
        worker.start();
        LOG.log(INFO, "Kawa worker started on queue ''{0}''", queue);
    }

    /** Stops after the current job (if any) is finished and settled, waiting up to 30 seconds. */
    public boolean stop() {
        return stop(Duration.ofSeconds(30));
    }

    /**
     * Asks the worker to stop after its current job and waits up to {@code timeout} for that.
     * Returns true once the worker has stopped.
     */
    public boolean stop(Duration timeout) {
        stopSignal.countDown();
        Thread worker = thread;
        if (worker == null) {
            return true;
        }
        try {
            worker.join(timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !worker.isAlive();
    }

    public boolean isRunning() {
        Thread worker = thread;
        return worker != null && worker.isAlive();
    }

    /** Jobs whose handler returned normally. */
    public long jobsCompleted() {
        return completed.get();
    }

    /** Jobs whose handler threw. */
    public long jobsFailed() {
        return failed.get();
    }

    private void run() {
        while (!stopRequested()) {
            Optional<LeasedJob> next;
            try {
                next = kawa.take(queue, lease);
            } catch (KawaException e) {
                LOG.log(WARNING, "Could not take a job from ''" + queue + "''; retrying in " + errorBackoff, e);
                pause(errorBackoff);
                continue;
            }
            if (next.isPresent()) {
                process(next.get());
            } else {
                pause(pollInterval);
            }
        }
        LOG.log(INFO, "Kawa worker on queue ''{0}'' stopped", queue);
    }

    private void process(LeasedJob job) {
        try {
            handler.handle(job);
        } catch (Exception e) {
            failed.incrementAndGet();
            LOG.log(WARNING, "Handler failed for " + job + (job.isLastAttempt() ? "; no attempts left, the job will be DEAD" : "; it will be retried"), e);
            settle(job, "nack", () -> kawa.nack(job));
            return;
        }
        completed.incrementAndGet();
        settle(job, "ack", () -> kawa.ack(job));
    }

    private void settle(LeasedJob job, String action, Runnable call) {
        try {
            call.run();
        } catch (LeaseLostException e) {
            LOG.log(WARNING, "Lease lost before " + action + " of " + job + "; another worker holds it now");
        } catch (KawaException e) {
            // the lease will expire and the job will come back, which is what at-least-once delivery means
            LOG.log(WARNING, "Could not " + action + " " + job + "; it will be retried after its lease expires", e);
        }
    }

    private boolean stopRequested() {
        return stopSignal.getCount() == 0;
    }

    private void pause(Duration duration) {
        try {
            stopSignal.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // someone interrupted the worker thread directly: treat it as a request to stop
            Thread.currentThread().interrupt();
            stopSignal.countDown();
        }
    }

    public static final class Builder {

        private final KawaClient kawa;
        private final String queue;
        private JobHandler handler;
        private Duration lease = Duration.ofSeconds(30);
        private Duration pollInterval = Duration.ofSeconds(1);
        private Duration errorBackoff = Duration.ofSeconds(5);

        private Builder(KawaClient kawa, String queue) {
            this.kawa = Objects.requireNonNull(kawa, "kawa");
            this.queue = Objects.requireNonNull(queue, "queue");
        }

        /** Your code, receiving the whole job (id, attempt number, payload, ...). */
        public Builder onJob(JobHandler handler) {
            this.handler = Objects.requireNonNull(handler, "handler");
            return this;
        }

        /** Your code, receiving just the payload converted to {@code type}. */
        public <T> Builder onJob(Class<T> type, PayloadHandler<T> handler) {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(handler, "handler");
            this.handler = job -> handler.handle(job.payload(type));
            return this;
        }

        /** How long each job is leased for. Longer than your slowest job. Default 30 seconds. */
        public Builder lease(Duration lease) {
            this.lease = Objects.requireNonNull(lease, "lease");
            return this;
        }

        /** How long to wait before asking again when the queue is empty. Default 1 second. */
        public Builder pollInterval(Duration pollInterval) {
            this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
            return this;
        }

        /** How long to wait after Kawa could not be reached. Default 5 seconds. */
        public Builder errorBackoff(Duration errorBackoff) {
            this.errorBackoff = Objects.requireNonNull(errorBackoff, "errorBackoff");
            return this;
        }

        public KawaWorker build() {
            if (handler == null) {
                throw new IllegalStateException("Set a handler with onJob(...) before build().");
            }
            return new KawaWorker(this);
        }
    }
}
