package com.turnstile.inventory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The timer that drives {@link HoldExpirySweeper} in a running service.
 *
 * <p>Switched off in tests via {@code turnstile.hold.sweep.enabled=false}. A
 * background sweeper firing in the middle of a test that is deliberately
 * constructing an expiry race would produce failures that look like concurrency
 * bugs and are actually just the test fighting itself.
 */
@Component
@ConditionalOnProperty(name = "turnstile.hold.sweep.enabled", havingValue = "true", matchIfMissing = true)
class HoldExpiryScheduler {

    private final HoldExpirySweeper sweeper;

    HoldExpiryScheduler(HoldExpirySweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Scheduled(fixedDelayString = "${turnstile.hold.sweep.interval:PT10S}")
    void sweep() {
        sweeper.sweep();
    }
}
