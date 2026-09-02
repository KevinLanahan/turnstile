package com.turnstile.common;

import java.util.UUID;

public class EventNotFoundException extends RuntimeException {

    public EventNotFoundException(UUID eventId) {
        super("No event with id " + eventId);
    }
}
