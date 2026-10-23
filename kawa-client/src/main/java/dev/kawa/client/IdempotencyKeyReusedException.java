package dev.kawa.client;

/**
 * The idempotency key was already used for a job with a different payload.
 */
public class IdempotencyKeyReusedException extends KawaRequestException {

    public IdempotencyKeyReusedException(String message) {
        super(422, message);
    }
}
