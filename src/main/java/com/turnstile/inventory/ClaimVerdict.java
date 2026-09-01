package com.turnstile.inventory;

/**
 * What the Redis fast path thinks about a seat. Advisory only - Postgres decides.
 */
public enum ClaimVerdict {

    /** No one holds this seat as far as Redis knows. Proceed to Postgres. */
    CLAIMED,

    /**
     * Redis already holds a mark carrying this caller's own idempotency key, so
     * this is a retry of a request that previously got through. Proceed to
     * Postgres, which will return the existing hold rather than a duplicate.
     */
    REPLAY,

    /** Someone else holds it. Reject now, without spending a database connection. */
    UNAVAILABLE
}
