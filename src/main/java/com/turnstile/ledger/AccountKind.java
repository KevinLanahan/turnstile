package com.turnstile.ledger;

public enum AccountKind {
    /** One per buyer. Goes negative as they spend. */
    CUSTOMER,
    /** One per event. Accumulates ticket revenue. */
    EVENT_REVENUE
}
