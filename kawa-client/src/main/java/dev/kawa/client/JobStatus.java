package dev.kawa.client;

public enum JobStatus {
    PENDING,
    LEASED,
    DONE,
    DEAD;

    public boolean isTerminal() {
        return this == DONE || this == DEAD;
    }
}
