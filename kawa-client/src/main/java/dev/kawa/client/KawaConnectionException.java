package dev.kawa.client;

/**
 * Kawa could not be reached, or the connection failed mid-request. Safe to retry for enqueue only when
 * an idempotency key is set; otherwise a retry may create a second job.
 */
public class KawaConnectionException extends KawaException {

    public KawaConnectionException(String message, Throwable cause) {
        super(message, cause);
    }
}
