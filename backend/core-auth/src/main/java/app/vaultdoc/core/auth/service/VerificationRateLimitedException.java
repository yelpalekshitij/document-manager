package app.vaultdoc.core.auth.service;

import java.time.Duration;

public class VerificationRateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public VerificationRateLimitedException(Duration retryAfter) {
        super("Try again in " + retryAfter.getSeconds() + "s");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
