package dev.kawa.client;

public class JobNotFoundException extends KawaRequestException {

    public JobNotFoundException(String message) {
        super(404, message);
    }
}
