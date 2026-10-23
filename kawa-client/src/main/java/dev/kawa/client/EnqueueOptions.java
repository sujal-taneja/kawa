package dev.kawa.client;

import java.time.Duration;
import java.time.Instant;

/**
 * Optional settings for {@link KawaClient#enqueue(String, Object, EnqueueOptions)}. Anything left unset
 * uses the server's default. Values are validated by the server, which is the single source of truth.
 */
public final class EnqueueOptions {

    private static final EnqueueOptions NONE = builder().build();

    private final Integer priority;
    private final Integer maxAttempts;
    private final Duration delay;
    private final Instant runAt;
    private final String idempotencyKey;

    private EnqueueOptions(Builder builder) {
        this.priority = builder.priority;
        this.maxAttempts = builder.maxAttempts;
        this.delay = builder.delay;
        this.runAt = builder.runAt;
        this.idempotencyKey = builder.idempotencyKey;
    }

    public static EnqueueOptions none() {
        return NONE;
    }

    public static Builder builder() {
        return new Builder();
    }

    Integer priority() {
        return priority;
    }

    Integer maxAttempts() {
        return maxAttempts;
    }

    Duration delay() {
        return delay;
    }

    Instant runAt() {
        return runAt;
    }

    String idempotencyKey() {
        return idempotencyKey;
    }

    public static final class Builder {

        private Integer priority;
        private Integer maxAttempts;
        private Duration delay;
        private Instant runAt;
        private String idempotencyKey;

        private Builder() {
        }

        /** 0 (default) to 9 (most urgent). */
        public Builder priority(int priority) {
            this.priority = priority;
            return this;
        }

        public Builder maxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
            return this;
        }

        /** Run the job after this delay (whole seconds). Use either delay or runAt. */
        public Builder delay(Duration delay) {
            this.delay = delay;
            return this;
        }

        /** Run the job at this instant. Use either delay or runAt. */
        public Builder runAt(Instant runAt) {
            this.runAt = runAt;
            return this;
        }

        /** Makes the enqueue safe to retry: the same key and payload never create a second job. */
        public Builder idempotencyKey(String idempotencyKey) {
            this.idempotencyKey = idempotencyKey;
            return this;
        }

        public EnqueueOptions build() {
            return new EnqueueOptions(this);
        }
    }
}
