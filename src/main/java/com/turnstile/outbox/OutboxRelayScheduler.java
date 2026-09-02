package com.turnstile.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives {@link OutboxRelay} in a running service. Disabled in tests, which call
 * the relay directly so that delivery timing is deterministic rather than a race
 * against a background timer.
 */
@Component
@ConditionalOnProperty(name = "turnstile.outbox.enabled", havingValue = "true", matchIfMissing = true)
class OutboxRelayScheduler {

    private final OutboxRelay relay;

    OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${turnstile.outbox.interval:PT2S}")
    void relay() {
        this.relay.relay();
    }
}
