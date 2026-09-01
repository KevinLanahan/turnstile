package com.turnstile.inventory;

import java.time.Instant;
import java.util.UUID;

public record Hold(
        UUID id,
        UUID seatId,
        UUID userId,
        HoldState state,
        String idempotencyKey,
        Instant createdAt,
        Instant expiresAt
) {
}
