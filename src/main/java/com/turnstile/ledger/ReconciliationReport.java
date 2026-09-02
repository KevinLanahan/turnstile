package com.turnstile.ledger;

import java.time.Instant;
import java.util.List;

/**
 * What reconciliation found. An empty {@code findings} list is the only good result.
 */
public record ReconciliationReport(Instant ranAt, List<String> findings) {

    public boolean isClean() {
        return findings.isEmpty();
    }
}
