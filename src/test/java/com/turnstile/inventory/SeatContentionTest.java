package com.turnstile.inventory;

import com.turnstile.common.SeatUnavailableException;
import com.turnstile.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The test this whole project exists to pass.
 *
 * <p>500 threads go for one seat at the same instant. Exactly one may win.
 * Everyone else must lose cleanly - a 409, not a crash, not a deadlock, and
 * above all not a second winner.
 *
 * <p>If this test is green, the system does not oversell. If it is red,
 * nothing else in the repo matters.
 */
class SeatContentionTest extends AbstractIntegrationTest {

    private static final int CONTENDERS = 500;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID seatId;

    @BeforeEach
    void seedSingleSeatEvent() {
        jdbc.execute("TRUNCATE holds, seats, events CASCADE");

        UUID eventId = UUID.randomUUID();
        seatId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO events (id, name, venue, starts_at, sales_open_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                eventId,
                "Contention Test",
                "The Void",
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now()));

        jdbc.update("""
                INSERT INTO seats (id, event_id, section, row_label, seat_number, price_cents, status)
                VALUES (?, ?, ?, ?, ?, ?, 'AVAILABLE')
                """,
                seatId, eventId, "GA", "A", 1, 12_500L);
    }

    @Test
    @DisplayName("500 concurrent holds on one seat -> exactly one winner, zero oversell")
    void exactlyOneWinnerUnderContention() throws Exception {
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger wins = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Throwable> unexpected = new CopyOnWriteArrayList<>();

        // Java 21 virtual threads: 500 of them cost almost nothing, and every
        // one is genuinely parked on the database rather than on a thread pool
        // queue - which is the point. We want real simultaneity, not a queue.
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(CONTENDERS);

            for (int i = 0; i < CONTENDERS; i++) {
                futures.add(executor.submit(() -> {
                    try {
                        // Everyone lines up here, then goes at once.
                        startGate.await();
                        inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
                        wins.incrementAndGet();
                    } catch (SeatUnavailableException expected) {
                        conflicts.incrementAndGet();
                    } catch (Throwable surprise) {
                        unexpected.add(surprise);
                    }
                }));
            }

            startGate.countDown();

            for (Future<?> future : futures) {
                future.get(90, TimeUnit.SECONDS);
            }
        }

        // 1. Nothing blew up in a way we did not model.
        assertThat(unexpected)
                .as("losing a race must be a clean 409, never an exception we did not expect")
                .isEmpty();

        // 2. Exactly one winner.
        assertThat(wins.get())
                .as("exactly one thread may claim the seat")
                .isEqualTo(1);

        assertThat(conflicts.get())
                .as("every other thread must lose cleanly")
                .isEqualTo(CONTENDERS - 1);

        // 3. The application's own counters could be wrong. The database is the
        //    real judge, so ask it directly.
        String seatStatus = jdbc.queryForObject(
                "SELECT status FROM seats WHERE id = ?", String.class, seatId);
        assertThat(seatStatus).isEqualTo("HELD");

        Integer activeHolds = jdbc.queryForObject(
                "SELECT count(*) FROM holds WHERE seat_id = ? AND state = 'ACTIVE'",
                Integer.class, seatId);
        assertThat(activeHolds)
                .as("one seat, one live hold - enforced by uq_one_active_hold_per_seat")
                .isEqualTo(1);

        Integer totalHolds = jdbc.queryForObject(
                "SELECT count(*) FROM holds WHERE seat_id = ?", Integer.class, seatId);
        assertThat(totalHolds)
                .as("losing transactions must roll back completely, leaving no orphan hold rows")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("replaying the same idempotency key returns the same hold")
    void idempotentReplayReturnsSameHold() {
        String key = UUID.randomUUID().toString();
        UUID userId = UUID.randomUUID();

        Hold first = inventory.hold(seatId, userId, key);
        Hold second = inventory.hold(seatId, userId, key);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM holds WHERE seat_id = ?", Integer.class, seatId))
                .isEqualTo(1);
    }
}
