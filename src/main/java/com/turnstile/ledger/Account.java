package com.turnstile.ledger;

import java.util.UUID;

public record Account(UUID id, AccountKind kind, UUID ownerId, String currency) {
}
