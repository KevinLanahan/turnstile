package com.turnstile.inventory;

import java.time.Instant;
import java.util.UUID;

public record Event(
        UUID id,
        String name,
        String venue,
        Instant startsAt,
        Instant salesOpenAt
) {
}
