package dev.kawa.client;

/**
 * Like {@link JobHandler}, but receives just the payload, already converted to your type.
 */
@FunctionalInterface
public interface PayloadHandler<T> {

    void handle(T payload) throws Exception;
}
