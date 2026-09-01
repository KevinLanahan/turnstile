package com.turnstile.inventory;

import java.util.UUID;

public record Seat(
        UUID id,
        UUID eventId,
        String section,
        String rowLabel,
        int seatNumber,
        long priceCents,
        SeatStatus status,
        UUID holdId,
        long version
) {
}
