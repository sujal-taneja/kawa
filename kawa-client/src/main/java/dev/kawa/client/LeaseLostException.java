package dev.kawa.client;

/**
 * The job is no longer leased with this token: the lease expired and the job was handed to another
 * worker, or it was already acked or nacked. Stop working on it; whoever holds it now will finish it.
 */
public class LeaseLostException extends KawaRequestException {

    public LeaseLostException(String message) {
        super(409, message);
    }
}
