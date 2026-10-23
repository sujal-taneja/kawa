package dev.kawa.client;

/**
 * Base class for every error the client raises. Unchecked, so callers catch only what they can act on.
 */
public class KawaException extends RuntimeException {

    public KawaException(String message) {
        super(message);
    }

    public KawaException(String message, Throwable cause) {
        super(message, cause);
    }
}
