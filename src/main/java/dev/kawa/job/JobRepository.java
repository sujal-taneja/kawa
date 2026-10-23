package dev.kawa.job;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JobRepository {

    private final JdbcClient jdbcClient;

    public JobRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public UUID enqueue(String queue, String payloadJson, int maxAttempts) {
        return jdbcClient.sql("""
                        INSERT INTO jobs (queue, payload, max_attempts)
                        VALUES (:queue, :payload::jsonb, :maxAttempts)
                        RETURNING id
                        """)
                .param("queue", queue)
                .param("payload", payloadJson)
                .param("maxAttempts", maxAttempts)
                .query((rs, rowNum) -> (UUID) rs.getObject("id"))
                .single();
    }

    /**
     * The core trick: FOR UPDATE SKIP LOCKED lets many workers run this at once
     * without blocking each other — each one just skips rows another worker
     * already has locked, instead of queuing up behind them.
     */
    public Optional<Job> take(String queue, Duration leaseDuration) {
        UUID leaseToken = UUID.randomUUID();
        return jdbcClient.sql("""
                        UPDATE jobs
                        SET status = 'LEASED',
                            lease_token = :leaseToken,
                            lease_expires_at = now() + :leaseSeconds * INTERVAL '1 second',
                            attempts = attempts + 1,
                            updated_at = now()
                        WHERE id = (
                            SELECT id FROM jobs
                            WHERE queue = :queue
                              AND status = 'PENDING'
                              AND available_at <= now()
                            ORDER BY available_at
                            FOR UPDATE SKIP LOCKED
                            LIMIT 1
                        )
                        RETURNING *
                        """)
                .param("queue", queue)
                .param("leaseToken", leaseToken)
                .param("leaseSeconds", leaseDuration.toSeconds())
                .query(JobRepository::mapRow)
                .optional();
    }

    public boolean extend(UUID id, UUID leaseToken, Duration leaseDuration) {
        int updated = jdbcClient.sql("""
                        UPDATE jobs
                        SET lease_expires_at = now() + :leaseSeconds * INTERVAL '1 second',
                            updated_at = now()
                        WHERE id = :id AND lease_token = :leaseToken AND status = 'LEASED'
                        """)
                .param("id", id)
                .param("leaseToken", leaseToken)
                .param("leaseSeconds", leaseDuration.toSeconds())
                .update();
        return updated == 1;
    }

    public boolean ack(UUID id, UUID leaseToken) {
        int updated = jdbcClient.sql("""
                        UPDATE jobs
                        SET status = 'DONE', lease_token = NULL, lease_expires_at = NULL, updated_at = now()
                        WHERE id = :id AND lease_token = :leaseToken AND status = 'LEASED'
                        """)
                .param("id", id)
                .param("leaseToken", leaseToken)
                .update();
        return updated == 1;
    }

    /**
     * On failure: retry after a delay if attempts remain, otherwise DEAD (terminal, isTerminal() == true).
     */
    public boolean nack(UUID id, UUID leaseToken, Duration retryDelay) {
        int updated = jdbcClient.sql("""
                        UPDATE jobs
                        SET status = CASE WHEN attempts >= max_attempts THEN 'DEAD' ELSE 'PENDING' END,
                            available_at = now() + :retrySeconds * INTERVAL '1 second',
                            lease_token = NULL,
                            lease_expires_at = NULL,
                            updated_at = now()
                        WHERE id = :id AND lease_token = :leaseToken AND status = 'LEASED'
                        """)
                .param("id", id)
                .param("leaseToken", leaseToken)
                .param("retrySeconds", retryDelay.toSeconds())
                .update();
        return updated == 1;
    }

    public Optional<Job> findById(UUID id) {
        return jdbcClient.sql("SELECT * FROM jobs WHERE id = :id")
                .param("id", id)
                .query(JobRepository::mapRow)
                .optional();
    }

    /**
     * The reaper: a worker that holds a lease past lease_expires_at is presumed dead.
     * Put the job back to PENDING so another worker can pick it up.
     */
    public int reapExpiredLeases() {
        return jdbcClient.sql("""
                        UPDATE jobs
                        SET status = 'PENDING', lease_token = NULL, lease_expires_at = NULL, updated_at = now()
                        WHERE status = 'LEASED' AND lease_expires_at < now()
                        """)
                .update();
    }

    private static Job mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Job(
                (UUID) rs.getObject("id"),
                rs.getString("queue"),
                rs.getString("payload"),
                JobStatus.valueOf(rs.getString("status")),
                rs.getInt("attempts"),
                rs.getInt("max_attempts"),
                rs.getTimestamp("available_at").toInstant(),
                (UUID) rs.getObject("lease_token"),
                rs.getTimestamp("lease_expires_at") == null ? null : rs.getTimestamp("lease_expires_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }
}
