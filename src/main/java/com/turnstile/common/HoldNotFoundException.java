package com.turnstile.common;

import java.util.UUID;

public class HoldNotFoundException extends RuntimeException {

    public HoldNotFoundException(UUID holdId) {
        super("No hold with id " + holdId);
    }
}
