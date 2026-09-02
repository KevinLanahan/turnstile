package com.turnstile.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
        long id,
        UUID aggregateId,
        String eventType,
        String payload,
        Instant createdAt,
        int attempts
) {
}
