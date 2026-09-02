package com.turnstile.outbox;

import com.turnstile.booking.BookingNotifier;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The transactional outbox, and specifically the two properties that make it
 * worth the trouble.
 *
 * <ol>
 *   <li>An event exists if and only if the change it describes was committed.
 *       Never announce something that did not happen.</li>
 *   <li>Delivery is at-least-once, so duplicates are inevitable and the consumer
 *       must make them harmless.</li>
 * </ol>
 */
class OutboxTest extends AbstractIntegrationTest {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private BookingService booking;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private BookingNotifier notifier;

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
                eventId, "Outbox", "The Void",
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now()));
    }

    @Test
    @DisplayName("a committed booking leaves exactly one unpublished event")
    void aCommittedBookingWritesAnEvent() {
        UUID seatId = seat(1, 12_500L);
        Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
        booking.confirm(hold.id());

        List<OutboxEvent> pending = outbox.findUnpublished(10);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).eventType()).isEqualTo("SeatBooked");
        assertThat(pending.get(0).aggregateId()).isEqualTo(seatId);
        assertThat(pending.get(0).payload()).contains(seatId.toString());
    }

    @Test
    @DisplayName("a failed booking leaves no event behind")
    void aFailedBookingWritesNoEvent() {
        UUID seatId = seat(2, 9_900L);
        Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());

        // Kill the hold so confirmation loses the race and the transaction rolls back.
        jdbc.update("UPDATE holds SET state = 'EXPIRED' WHERE id = ?", hold.id());
        assertThatThrownBy(() -> booking.confirm(hold.id())).isInstanceOf(RuntimeException.class);

        // THE POINT OF THE WHOLE PATTERN. A "write the row, then publish" design
        // would have already told the outside world about a booking that never
        // happened. Because the event lives in the same transaction, it died with it.
        assertThat(outbox.unpublishedCount())
                .as("no booking, no announcement")
                .isZero();
    }

    @Test
    @DisplayName("the relay publishes pending events and does not republish them")
    void relayPublishesOnce() {
        bookSeats(3);

        assertThat(relay.relay()).isEqualTo(3);
        assertThat(outbox.unpublishedCount()).isZero();

        assertThat(relay.relay())
                .as("a second pass has nothing left to do")
                .isZero();
    }

    @Test
    @DisplayName("redelivery does not double-process, because the consumer claims the id")
    void redeliveryIsHarmless() {
        bookSeats(1);
        OutboxEvent event = outbox.findUnpublished(1).get(0);

        assertThat(notifier.handle(event))
                .as("first delivery does the work")
                .isTrue();

        long afterFirst = notifier.notificationsSent();

        // Simulate the relay crashing after publishing but before marking the row -
        // the exact window that makes delivery at-least-once rather than exactly-once.
        assertThat(notifier.handle(event))
                .as("redelivery is recognised and ignored")
                .isFalse();
        assertThat(notifier.handle(event)).isFalse();

        assertThat(notifier.notificationsSent())
                .as("the customer gets one confirmation, not three")
                .isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("a publisher failure leaves the event pending for retry")
    void publisherFailureLeavesEventPending() {
        bookSeats(1);
        OutboxEvent event = outbox.findUnpublished(1).get(0);

        outbox.recordFailure(event.id(), "simulated broker outage");

        assertThat(outbox.unpublishedCount())
                .as("a failed publish must not be silently dropped")
                .isEqualTo(1);
        Integer attempts = jdbc.queryForObject(
                "SELECT attempts FROM outbox WHERE id = ?", Integer.class, event.id());
        assertThat(attempts).isEqualTo(1);

        // And it still goes out once the broker recovers.
        assertThat(relay.relay()).isEqualTo(1);
        assertThat(outbox.unpublishedCount()).isZero();
    }

    private void bookSeats(int count) {
        for (int i = 0; i < count; i++) {
            UUID seatId = seat(100 + i, 5_000L);
            Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
            booking.confirm(hold.id());
        }
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
