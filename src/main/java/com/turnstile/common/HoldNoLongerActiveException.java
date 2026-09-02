package com.turnstile.common;

import java.util.UUID;

/**
 * The hold was expired, already confirmed, or released before this confirmation
 * arrived. Routine under load, not an error condition - it is exactly what the
 * caller should see when they lose the race against the expiry sweeper.
 */
public class HoldNoLongerActiveException extends RuntimeException {

    private final UUID holdId;

    public HoldNoLongerActiveException(UUID holdId) {
        super("Hold " + holdId + " is no longer active");
        this.holdId = holdId;
    }

    public UUID holdId() {
        return holdId;
    }
}
