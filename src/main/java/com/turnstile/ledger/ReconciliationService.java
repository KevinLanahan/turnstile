package com.turnstile.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Independently re-derives what should be true and complains when it is not.
 *
 * <p>Every invariant here is already enforced somewhere - by a constraint, a
 * trigger, or a transaction boundary. Checking them again is not redundancy for
 * its own sake. Enforcement can be bypassed (a migration, a manual fix at 2am, a
 * TRUNCATE, a bug in a future feature that writes these tables directly), and the
 * failure mode of a ledger is silence: nothing crashes, the numbers are simply
 * wrong, and nobody finds out until someone counts.
 *
 * <p>This is the thing that counts. Banks run exactly this, nightly, and treat a
 * non-empty report as an incident.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final JdbcTemplate jdbc;

    public ReconciliationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ReconciliationReport reconcile() {
        List<String> findings = new ArrayList<>();

        checkGlobalBalance(findings);
        checkEveryTransferBalances(findings);
        checkBookedSeatsMatchSales(findings);
        checkEventRevenueMatchesSeatsSold(findings);

        ReconciliationReport report = new ReconciliationReport(Instant.now(), List.copyOf(findings));
        if (report.isClean()) {
            log.info("Reconciliation clean");
        } else {
            log.error("Reconciliation found {} problem(s): {}", findings.size(), findings);
        }
        return report;
    }

    /** Across every entry ever written, the signed total must be zero. */
    private void checkGlobalBalance(List<String> findings) {
        Long imbalance = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_entries", Long.class);
        if (imbalance != null && imbalance != 0) {
            findings.add("Global ledger imbalance: " + imbalance + " cents. "
                    + "Money was created or destroyed.");
        }
    }

    /** And each individual transfer must balance, per currency. */
    private void checkEveryTransferBalances(List<String> findings) {
        List<String> broken = jdbc.query("""
                SELECT transfer_id, currency, SUM(amount_cents) AS imbalance
                  FROM ledger_entries
                 GROUP BY transfer_id, currency
                HAVING SUM(amount_cents) <> 0
                """,
                (rs, rowNum) -> "Transfer " + rs.getString("transfer_id")
                        + " is off by " + rs.getLong("imbalance")
                        + " " + rs.getString("currency").trim());
        findings.addAll(broken);
    }

    /**
     * Every booked seat should correspond to exactly one purchase that has not been
     * reversed. More seats than sales means someone got a seat for free; more sales
     * than seats means someone paid for nothing.
     */
    private void checkBookedSeatsMatchSales(List<String> findings) {
        Long bookedSeats = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE status = 'BOOKED'", Long.class);

        Long liveSales = jdbc.queryForObject("""
                SELECT count(*)
                  FROM transfers p
                 WHERE p.kind = 'PURCHASE'
                   AND NOT EXISTS (SELECT 1 FROM transfers r WHERE r.reverses_id = p.id)
                """, Long.class);

        if (bookedSeats != null && liveSales != null && !bookedSeats.equals(liveSales)) {
            findings.add("Booked seats (" + bookedSeats + ") does not match unreversed sales ("
                    + liveSales + "). "
                    + (bookedSeats > liveSales
                        ? "Seats were given away without payment."
                        : "Customers paid for seats they do not hold."));
        }
    }

    /**
     * Per event, the revenue account balance should equal the list price of the
     * seats currently sold. This is the check that catches a wrong AMOUNT, which
     * the count-based check above cannot see.
     */
    private void checkEventRevenueMatchesSeatsSold(List<String> findings) {
        List<String> mismatches = jdbc.query("""
                SELECT s.event_id,
                       COALESCE(SUM(s.price_cents), 0) AS seat_total,
                       COALESCE(b.balance_cents, 0)    AS ledger_total
                  FROM seats s
                  LEFT JOIN account_balances b
                         ON b.owner_id = s.event_id AND b.kind = 'EVENT_REVENUE'
                 WHERE s.status = 'BOOKED'
                 GROUP BY s.event_id, b.balance_cents
                HAVING COALESCE(SUM(s.price_cents), 0) <> COALESCE(b.balance_cents, 0)
                """,
                (rs, rowNum) -> "Event " + rs.getString("event_id")
                        + ": seats sold total " + rs.getLong("seat_total")
                        + " cents but ledger revenue is " + rs.getLong("ledger_total") + " cents");
        findings.addAll(mismatches);
    }
}
