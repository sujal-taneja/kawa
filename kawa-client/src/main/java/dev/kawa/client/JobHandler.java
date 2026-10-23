package dev.kawa.client;

/**
 * Your code for one job. Return normally and the job is acked; throw and it is nacked
 * (retried later, or DEAD when out of attempts).
 */
@FunctionalInterface
public interface JobHandler {

    void handle(LeasedJob job) throws Exception;
}
