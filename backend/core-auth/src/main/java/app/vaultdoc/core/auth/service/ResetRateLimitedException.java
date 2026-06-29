package app.vaultdoc.core.auth.service;

import java.time.Duration;

public class ResetRateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public ResetRateLimitedException(Duration retryAfter) {
        super("Try again in " + retryAfter.getSeconds() + "s");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() { return retryAfter; }
}
