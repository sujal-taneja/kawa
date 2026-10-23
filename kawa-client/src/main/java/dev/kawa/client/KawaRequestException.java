package dev.kawa.client;

/**
 * Kawa rejected the request. status is the HTTP status; the message is the server's explanation.
 */
public class KawaRequestException extends KawaException {

    private final int status;

    public KawaRequestException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
