package dev.kawa.job;

public enum JobStatus {
    PENDING(false),
    LEASED(false),
    DONE(true),
    DEAD(true);

    private final boolean terminal;

    JobStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
