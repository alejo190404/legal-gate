package com.legalgate.intake.billing;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Fixed-window limiter for coupon lookups. Every quote and checkout that carries a coupon code
 * hits the coupon table and answers "does this code exist", so without a cap a script can
 * enumerate the whole catalog from one logged-in organization.
 */
@Component
class CouponRateLimiter {
    static final int MAX_ATTEMPTS = 10;
    static final Duration WINDOW = Duration.ofMinutes(1);

    // ponytail: per-instance counters, and entries live until the org tries again. Bounded by the
    // number of organizations; move to a shared store when the orchestrator runs several replicas.
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final int maxAttempts;
    private final Duration window;

    CouponRateLimiter() {
        this(MAX_ATTEMPTS, WINDOW);
    }

    CouponRateLimiter(int maxAttempts, Duration window) {
        this.maxAttempts = maxAttempts;
        this.window = window;
    }

    boolean tryAcquire(String key) {
        Instant now = Instant.now();
        Window current = windows.compute(key, (ignored, previous) ->
                previous == null || !now.isBefore(previous.start().plus(window))
                        ? new Window(now, 1)
                        : new Window(previous.start(), previous.attempts() + 1));
        return current.attempts() <= maxAttempts;
    }

    private record Window(Instant start, int attempts) {
    }
}
