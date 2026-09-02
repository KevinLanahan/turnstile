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
 * Inventory and money must agree.
 *
 * <p>The property under test is that a booked seat and the transfer that paid for
 * it are written in the same transaction. Split them and a window opens in which a
 * customer is charged for a seat they did not get, or walks away with a seat
 * nobody was charged for. The window is small; at ticketing volumes, small windows
 * are daily occurrences.
 */
class BookingLedgerAtomicityTest extends AbstractIntegrationTest {

    private static final String USD = "USD";

    @Autowired
    private InventoryService inventory;

    @Autowired
    private BookingService booking;

    @Autowired
    private LedgerService ledger;

    @Autowired
    private LedgerRepository ledgerRepo;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID eventId;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE holds, seats, events CASCADE");
        jdbc.execute("TRUNCATE ledger_entries, transfers, accounts CASCADE");

        eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (id, name, venue, starts_at, sales_open_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                eventId, "Ledger Atomicity", "The Void",
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now()));
    }

    @Test
    @DisplayName("confirming a hold books the seat and posts the money together")
    void confirmingBooksTheSeatAndPostsTheMoney() {
        UUID seatId = seat("A", 1, 12_500L);
        UUID userId = UUID.randomUUID();

        Hold hold = inventory.hold(seatId, userId, UUID.randomUUID().toString());
        BookingService.Booking result = booking.confirm(hold.id());

        assertThat(seatStatus(seatId)).isEqualTo("BOOKED");
        assertThat(result.amountCents()).isEqualTo(12_500L);
        assertThat(ledger.balanceOf(AccountKind.CUSTOMER, userId, USD)).isEqualTo(-12_500L);
        assertThat(ledger.balanceOf(AccountKind.EVENT_REVENUE, eventId, USD)).isEqualTo(12_500L);
        assertThat(ledgerRepo.globalImbalanceCents()).isZero();
    }

    @Test
    @DisplayName("a lost race against the sweeper charges nobody")
    void aLostRaceChargesNobody() {
        UUID seatId = seat("A", 2, 9_900L);
        UUID userId = UUID.randomUUID();

        Hold hold = inventory.hold(seatId, userId, UUID.randomUUID().toString());

        // Kill the hold out from under the confirmation, exactly as the expiry
        // sweeper would.
        jdbc.update("UPDATE holds SET state = 'EXPIRED' WHERE id = ?", hold.id());

        try {
            booking.confirm(hold.id());
        } catch (RuntimeException expected) {
            // Losing is the point.
        }

        assertThat(ledger.balanceOf(AccountKind.CUSTOMER, userId, USD))
                .as("a customer who did not get a seat must not be charged for one")
                .isZero();
        Integer transfers = jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class);
        assertThat(transfers).as("no money moved at all").isZero();
    }

    @Test
    @DisplayName("every booked seat is backed by exactly one transfer")
    void everyBookedSeatIsBackedByExactlyOneTransfer() {
        for (int i = 1; i <= 25; i++) {
            UUID seatId = seat("B", i, 1_000L + i * 100L);
            Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
            booking.confirm(hold.id());
        }

        Integer booked = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE status = 'BOOKED'", Integer.class);
        Integer transfers = jdbc.queryForObject(
                "SELECT count(*) FROM transfers WHERE kind = 'PURCHASE'", Integer.class);

        // This is the reconciliation check that M4b will run on a schedule, asserted
        // here in miniature.
        assertThat(transfers).as("one sale per booked seat, no more and no fewer").isEqualTo(booked);

        Long revenue = jdbc.queryForObject("""
                SELECT COALESCE(SUM(price_cents), 0) FROM seats WHERE status = 'BOOKED'
                """, Long.class);
        assertThat(ledger.balanceOf(AccountKind.EVENT_REVENUE, eventId, USD))
                .as("ledger revenue must equal the sum of what was actually sold")
                .isEqualTo(revenue);
        assertThat(ledgerRepo.globalImbalanceCents()).isZero();
    }

    private UUID seat(String row, int number, long priceCents) {
        UUID seatId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO seats (id, event_id, section, row_label, seat_number, price_cents, status)
                VALUES (?, ?, 'ORCH', ?, ?, ?, 'AVAILABLE')
                """, seatId, eventId, row, number, priceCents);
        return seatId;
    }

    private String seatStatus(UUID seatId) {
        return jdbc.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, seatId);
    }
}
