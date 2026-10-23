package dev.kawa.job;

import dev.kawa.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentTakeTest extends IntegrationTest {

    private static final int JOBS = 1000;
    private static final int WORKERS = 10;
    private static final String QUEUE = "load";

    @Autowired
    JobRepository repository;

    @Test
    void tenWorkersDrainAThousandJobsWithEachJobProcessedExactlyOnce() throws Exception {
        Set<UUID> enqueued = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < JOBS; i++) {
            enqueued.add(repository.enqueue(QUEUE, "{\"n\":" + i + "}", 3));
        }

        Map<UUID, AtomicInteger> timesProcessed = new ConcurrentHashMap<>();
        Map<String, AtomicInteger> jobsPerWorker = new ConcurrentHashMap<>();
        CountDownLatch startTogether = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(WORKERS);
        List<Future<?>> workers = new ArrayList<>();
        for (int w = 0; w < WORKERS; w++) {
            String workerName = "worker-" + w;
            workers.add(pool.submit(() -> {
                startTogether.await();
                while (true) {
                    Optional<Job> next = repository.take(QUEUE, Duration.ofSeconds(30));
                    if (next.isEmpty()) {
                        return null;
                    }
                    Job job = next.get();
                    timesProcessed.computeIfAbsent(job.id(), id -> new AtomicInteger()).incrementAndGet();
                    jobsPerWorker.computeIfAbsent(workerName, name -> new AtomicInteger()).incrementAndGet();
                    if (!repository.ack(job.id(), job.leaseToken())) {
                        throw new AssertionError("ack rejected for " + job.id());
                    }
                }
            }));
        }

        startTogether.countDown();
        for (Future<?> worker : workers) {
            worker.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(timesProcessed.keySet()).isEqualTo(enqueued);
        assertThat(timesProcessed.values()).allMatch(count -> count.get() == 1);
        assertThat(jobsPerWorker).hasSizeGreaterThan(1);
        assertThat(countByStatus(JobStatus.DONE)).isEqualTo(JOBS);
        assertThat(countByStatus(JobStatus.PENDING) + countByStatus(JobStatus.LEASED)).isZero();
    }

    private long countByStatus(JobStatus status) {
        return jdbcClient.sql("SELECT count(*) FROM jobs WHERE status = :status")
                .param("status", status.name())
                .query(Long.class)
                .single();
    }
}
