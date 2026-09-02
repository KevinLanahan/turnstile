package com.turnstile.ledger;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs reconciliation on a schedule. Nightly in production; disabled in tests,
 * which invoke it directly.
 */
@Component
@ConditionalOnProperty(name = "turnstile.reconciliation.enabled", havingValue = "true", matchIfMissing = true)
class ReconciliationScheduler {

    private final ReconciliationService reconciliation;

    ReconciliationScheduler(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(fixedDelayString = "${turnstile.reconciliation.interval:PT1H}")
    void reconcile() {
        reconciliation.reconcile();
    }
}
