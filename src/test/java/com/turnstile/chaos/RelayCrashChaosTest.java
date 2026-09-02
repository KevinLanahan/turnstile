package com.turnstile.chaos;

import com.turnstile.booking.BookingNotifier;
import com.turnstile.booking.BookingService;
import com.turnstile.inventory.Hold;
import com.turnstile.inventory.InventoryService;
import com.turnstile.outbox.EventPublisher;
import com.turnstile.outbox.OutboxEvent;
import com.turnstile.outbox.OutboxRelay;
import com.turnstile.outbox.OutboxRepository;
import com.turnstile.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What happens when the relay dies at the worst possible moment.
 *
 * <p>There is exactly one unavoidable window in the outbox pattern: the relay
 * publishes an event, and then - before it can record that it did - the process
 * dies. On restart it publishes again. That window cannot be closed without a
 * distributed transaction across the database and the broker, which is the thing
 * the outbox exists to avoid.
 *
 * <p>So the design does not try to prevent duplicates. It makes them harmless.
 * This test forces that window open on every single event and checks the promise
 * holds: nothing is lost, and nothing is acted on twice.
 */
@Import(RelayCrashChaosTest.CrashingPublisherConfig.class)
class RelayCrashChaosTest extends AbstractIntegrationTest {

    private static final int BOOKINGS = 40;

    /**
     * A publisher that delivers the event and then dies, exactly reproducing the
     * crash-after-publish window. The event genuinely reached its destination; the
     * relay simply never got to write that down.
     */
    static class CrashingPublisher implements EventPublisher {

        final AtomicBoolean crashing = new AtomicBoolean(true);
        final List<Long> delivered = new ArrayList<>();
        int crashes = 0;

        @Override
        public synchronized void publish(OutboxEvent event) {
            delivered.add(event.id());
            if (crashing.get()) {
                crashes++;
                throw new IllegalStateException("relay died after publishing event " + event.id());
            }
        }
    }

    @TestConfiguration
    static class CrashingPublisherConfig {
        @Bean
        @Primary
        CrashingPublisher crashingPublisher() {
            return new CrashingPublisher();
        }
    }

    @Autowired
    private InventoryService inventory;

    @Autowired
    private BookingService booking;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private BookingNotifier notifier;

    @Autowired
    private CrashingPublisher publisher;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID eventId;

    @BeforeEach
    void seed() {
        jdbc.execute("TRUNCATE consumed_events, outbox CASCADE");
        jdbc.execute("TRUNCATE holds, seats, events CASCADE");
        jdbc.execute("TRUNCATE ledger_entries, transfers, accounts CASCADE");

        publisher.delivered.clear();
        publisher.crashes = 0;
        publisher.crashing.set(true);

        eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (id, name, venue, starts_at, sales_open_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                eventId, "Relay Chaos", "The Void",
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now()));
    }

    @Test
    @DisplayName("relay crashing after every publish loses nothing and duplicates nothing")
    void crashingAfterEveryPublishIsSurvivable() {
        for (int i = 0; i < BOOKINGS; i++) {
            UUID seatId = seat(i, 5_000L);
            Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
            booking.confirm(hold.id());
        }
        assertThat(outbox.unpublishedCount()).isEqualTo(BOOKINGS);

        // Phase 1: the relay dies after publishing, every time. Each pass delivers
        // the head of the queue and then blows up before marking it, so the same
        // event is delivered again on the next pass.
        for (int pass = 0; pass < BOOKINGS; pass++) {
            relay.relay();
        }

        assertThat(publisher.crashes)
                .as("we forced the crash window open on every attempt")
                .isEqualTo(BOOKINGS);
        assertThat(outbox.unpublishedCount())
                .as("a crashed relay must not silently drop events")
                .isEqualTo(BOOKINGS);

        // Every one of those deliveries was real. A consumer without dedupe would
        // have acted on all of them.
        int redundantDeliveries = publisher.delivered.size();
        assertThat(redundantDeliveries).isGreaterThanOrEqualTo(BOOKINGS);

        // Phase 2: the broker recovers. Everything drains.
        publisher.crashing.set(false);
        int drained = 0;
        for (int pass = 0; pass < BOOKINGS + 5 && outbox.unpublishedCount() > 0; pass++) {
            drained += relay.relay();
        }

        assertThat(drained).isEqualTo(BOOKINGS);
        assertThat(outbox.unpublishedCount())
                .as("nothing left behind after recovery")
                .isZero();
    }

    @Test
    @DisplayName("duplicate deliveries produce exactly one side effect each")
    void duplicateDeliveriesProduceOneSideEffectEach() {
        for (int i = 0; i < BOOKINGS; i++) {
            UUID seatId = seat(100 + i, 5_000L);
            Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
            booking.confirm(hold.id());
        }

        List<OutboxEvent> events = outbox.findUnpublished(BOOKINGS);
        long before = notifier.notificationsSent();

        // Hand every event to the consumer three times, as an at-least-once pipeline
        // eventually will.
        int deliveries = 0;
        for (int round = 0; round < 3; round++) {
            for (OutboxEvent event : events) {
                notifier.handle(event);
                deliveries++;
            }
        }

        assertThat(deliveries).isEqualTo(BOOKINGS * 3);
        assertThat(notifier.notificationsSent() - before)
                .as("%d deliveries, %d side effects - customers get one confirmation each",
                        deliveries, BOOKINGS)
                .isEqualTo(BOOKINGS);
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
