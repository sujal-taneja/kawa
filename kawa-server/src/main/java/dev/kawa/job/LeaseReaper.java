package dev.kawa.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LeaseReaper {

    private static final Logger log = LoggerFactory.getLogger(LeaseReaper.class);

    private final JobRepository repository;

    public LeaseReaper(JobRepository repository) {
        this.repository = repository;
    }

    // safe to run on every Kawa instance at once: each expired row is flipped by a single atomic UPDATE
    @Scheduled(fixedDelayString = "${kawa.reaper.interval-ms}")
    public void reap() {
        int reaped = repository.reapExpiredLeases();
        if (reaped > 0) {
            log.info("Reaped {} expired lease(s)", reaped);
        }
    }
}
