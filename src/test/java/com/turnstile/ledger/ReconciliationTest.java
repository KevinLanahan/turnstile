package com.turnstile.ledger;

import com.turnstile.booking.BookingService;
import com.turnstile.inventory.Hold;
import com.turnstile.inventory.InventoryService;
import com.turnstile.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciliation, tested the only way it is worth testing: by breaking things on
 * purpose and checking that it notices.
 *
 * <p>A reconciliation job that has only ever been run against healthy data is not
 * evidence of anything. Every invariant it checks is already enforced elsewhere,
 * so the drift it exists to catch cannot be produced through the normal API - it
 * has to be injected with raw SQL, exactly as a bad migration or a 2am manual fix
 * would.
 */
class ReconciliationTest extends AbstractIntegrationTest {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private BookingService booking;

    @Autowired
    private ReconciliationService reconciliation;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID eventId;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE consumed_events, outbox CASCADE");
        jdbc.execute("TRUNCATE holds, seats, events CASCADE");
        jdbc.execute("TRUNCATE ledger_entries, transfers, accounts CASCADE");

        eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (id, name, venue, starts_at, sales_open_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                eventId, "Reconciliation", "The Void",
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now()));
    }

    @Test
    @DisplayName("healthy books reconcile clean")
    void healthyBooksReconcileClean() {
        for (int i = 1; i <= 10; i++) {
            buySeat(i, 1_000L * i);
        }

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(report.findings()).isEmpty();
        assertThat(report.isClean()).isTrue();
    }

    @Test
    @DisplayName("catches a seat given away without payment")
    void catchesASeatGivenAwayForFree() {
        buySeat(1, 12_500L);

        // Inject the drift: a seat marked sold with no money behind it. This is what
        // a well-meaning manual fix looks like.
        UUID freeSeat = seat(2, 9_900L);
        Hold hold = inventory.hold(freeSeat, UUID.randomUUID(), UUID.randomUUID().toString());
        jdbc.update("UPDATE seats SET status = 'BOOKED' WHERE id = ?", freeSeat);
        jdbc.update("UPDATE holds SET state = 'CONFIRMED' WHERE id = ?", hold.id());

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(report.isClean()).isFalse();
        assertThat(report.findings())
                .anyMatch(f -> f.contains("Seats were given away without payment"));
    }

    @Test
    @DisplayName("catches revenue that does not match what was sold")
    void catchesWrongAmount() {
        buySeat(1, 12_500L);

        // A booking recorded at the wrong price. The COUNT of sales still matches
        // the count of seats, so only the amount check can see this one - which is
        // exactly why both checks exist.
        jdbc.update("UPDATE seats SET price_cents = 20_000 WHERE status = 'BOOKED'");

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(report.isClean()).isFalse();
        assertThat(report.findings())
                .anyMatch(f -> f.contains("ledger revenue is"));
    }

    @Test
    @DisplayName("catches a global imbalance, if the balance trigger is ever bypassed")
    void catchesAGlobalImbalance() {
        buySeat(1, 12_500L);

        // Getting here at all requires deliberately dismantling the M4a defence,
        // and that is the whole point of the test.
        //
        // The first attempt at this test tried to insert a lone unbalanced entry
        // the ordinary way. Postgres refused: the deferred balance trigger caught
        // it at commit. Which is when the arithmetic became obvious - if every
        // transfer balances, the global sum is necessarily zero. A global imbalance
        // is UNREACHABLE while that trigger holds.
        //
        // So why keep the check in the reconciler? Because triggers are not
        // permanent. They get disabled for a bulk load, skipped by logical
        // replication, or dropped by a migration nobody reviewed carefully. The
        // check exists for the day the guarantee is not there, so the test
        // simulates precisely that rather than pretending the drift can arise on
        // its own.
        jdbc.execute("ALTER TABLE ledger_entries DISABLE TRIGGER trg_ledger_entries_must_balance");
        try {
            UUID orphanTransfer = UUID.randomUUID();
            jdbc.update("INSERT INTO transfers (id, kind, idempotency_key) VALUES (?, 'PURCHASE', ?)",
                    orphanTransfer, "injected-drift");
            jdbc.update("""
                    INSERT INTO ledger_entries (transfer_id, account_id, amount_cents, currency)
                    SELECT ?, id, 50000, 'USD' FROM accounts LIMIT 1
                    """, orphanTransfer);
        } finally {
            // Restore it no matter what. The container is shared across every test
            // class in the JVM, and leaving this off would silently disarm the most
            // important guarantee in the schema for everything that runs afterwards.
            jdbc.execute("ALTER TABLE ledger_entries ENABLE TRIGGER trg_ledger_entries_must_balance");
        }

        ReconciliationReport report = reconciliation.reconcile();

        assertThat(report.isClean()).isFalse();
        assertThat(report.findings())
                .as("money appearing from nowhere is the finding that matters most")
                .anyMatch(f -> f.contains("Global ledger imbalance"));
        assertThat(report.findings())
                .anyMatch(f -> f.contains("is off by"));
    }

    private void buySeat(int number, long priceCents) {
        UUID seatId = seat(number, priceCents);
        Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
        booking.confirm(hold.id());
    }

    private UUID seat(int number, long priceCents) {
        UUID seatId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO seats (id, event_id, section, row_label, seat_number, price_cents, status)
                VALUES (?, ?, 'ORCH', 'A', ?, ?, 'AVAILABLE')
                """, seatId, eventId, number, priceCents);
        return seatId;
    }
}
