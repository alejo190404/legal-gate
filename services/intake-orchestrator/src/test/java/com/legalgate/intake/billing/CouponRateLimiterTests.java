package com.legalgate.intake.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CouponRateLimiterTests {

    @Test
    void allowsUpToTheLimitThenBlocks() {
        CouponRateLimiter limiter = new CouponRateLimiter(2, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("org_1")).isTrue();
        assertThat(limiter.tryAcquire("org_1")).isTrue();
        assertThat(limiter.tryAcquire("org_1")).isFalse();
    }

    @Test
    void countsEachOrganizationSeparately() {
        CouponRateLimiter limiter = new CouponRateLimiter(1, Duration.ofMinutes(1));

        assertThat(limiter.tryAcquire("org_1")).isTrue();
        assertThat(limiter.tryAcquire("org_2")).isTrue();
        assertThat(limiter.tryAcquire("org_1")).isFalse();
    }

    @Test
    void refillsWhenTheWindowRolls() throws InterruptedException {
        CouponRateLimiter limiter = new CouponRateLimiter(1, Duration.ofMillis(50));

        assertThat(limiter.tryAcquire("org_1")).isTrue();
        assertThat(limiter.tryAcquire("org_1")).isFalse();

        Thread.sleep(60);

        assertThat(limiter.tryAcquire("org_1")).isTrue();
    }
}
