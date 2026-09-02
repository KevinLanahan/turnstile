package com.turnstile.ledger;

import java.time.Instant;
import java.util.UUID;

public record Transfer(
        UUID id,
        TransferKind kind,
        String idempotencyKey,
        UUID reversesId,
        String memo,
        Instant createdAt
) {
}
