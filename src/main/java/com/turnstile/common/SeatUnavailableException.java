package com.turnstile.common;

import java.util.UUID;

/**
 * Thrown when a caller loses the race for a seat. This is an expected,
 * routine outcome under contention - not an error condition. It maps to
 * HTTP 409, and in a flash sale it will be the overwhelming majority of
 * responses by design.
 */
public class SeatUnavailableException extends RuntimeException {

    private final UUID seatId;

    public SeatUnavailableException(UUID seatId) {
        super("Seat " + seatId + " is not available");
        this.seatId = seatId;
    }

    public UUID seatId() {
        return seatId;
    }
}
