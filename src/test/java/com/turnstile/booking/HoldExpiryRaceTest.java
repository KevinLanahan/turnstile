package com.turnstile.booking;

import com.turnstile.common.HoldNoLongerActiveException;
import com.turnstile.inventory.Hold;
import com.turnstile.inventory.HoldExpirer;
import com.turnstile.inventory.HoldRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The nastiest correctness problem in the project: a payment confirming at the
 * exact millisecond the expiry sweeper decides the hold is dead.
 *
 * <p>Get this wrong in the obvious way and you either charge a card and then hand
 * the seat to someone else, or release a seat that has just been paid for. Both
 * are the kind of bug that produces a support ticket rather than a stack trace.
 *
 * <h2>What is actually being asserted</h2>
 *
 * <p>Note what is NOT asserted: that exactly one side always wins. There are
 * <b>three</b> legal outcomes, and the third one is easy to miss.
 *
 * <ol>
 *   <li><b>Confirm wins</b> - hold CONFIRMED, seat BOOKED.</li>
 *   <li><b>Expiry wins</b> - hold EXPIRED, seat AVAILABLE.</li>
 *   <li><b>Neither wins</b> - the sweeper ran a hair too early (hold not yet due,
 *       matches nothing) and the confirmation arrived a hair too late (hold now
 *       past its deadline, matches nothing). The hold is untouched and stays
 *       ACTIVE, and the next sweep collects it. Nothing is lost or sold twice.</li>
 * </ol>
 *
 * <p>The invariant that must never break is that hold state and seat status agree
 * with each other. A CONFIRMED hold whose seat is AVAILABLE, or an EXPIRED hold
 * whose seat is BOOKED, means money and inventory have diverged.
 */
class HoldExpiryRaceTest extends AbstractIntegrationTest {

    /** Confirmation should comfortably win: the hold is nowhere near due. */
    private static final int CONFIRM_FAVOURED = 40;
    /** Expiry should comfortably win: the hold is already overdue. */
    private static final int EXPIRY_FAVOURED = 40;
    /** A genuine coin flip: the deadline lands in the middle of both operations. */
    private static final int DEAD_HEAT = 120;

    @Autowired
    private InventoryService inventory;

    @Autowired
    private BookingService booking;

    @Autowired
    private HoldExpirer expirer;

    @Autowired
    private HoldRepository holds;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID eventId;

    @BeforeEach
    void seedEvent() {
        jdbc.execute("TRUNCATE holds, seats, events CASCADE");
        eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO events (id, name, venue, starts_at, sales_open_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                eventId, "Expiry Race", "The Void",
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now()));
    }

    @Test
    @DisplayName("confirm and expire race: never both, never inconsistent")
    void confirmAndExpireNeverBothWin() throws Exception {
        int confirmWins = 0;
        int expiryWins = 0;
        int draws = 0;

        List<Double> deadlineOffsetsSeconds = new ArrayList<>();
        for (int i = 0; i < CONFIRM_FAVOURED; i++) {
            deadlineOffsetsSeconds.add(0.500);
        }
        for (int i = 0; i < EXPIRY_FAVOURED; i++) {
            deadlineOffsetsSeconds.add(-0.500);
        }
        for (int i = 0; i < DEAD_HEAT; i++) {
            deadlineOffsetsSeconds.add(0.0);
        }

        for (double offsetSeconds : deadlineOffsetsSeconds) {
            Outcome outcome = raceOnce(offsetSeconds);
            switch (outcome) {
                case CONFIRM_WON -> confirmWins++;
                case EXPIRY_WON -> expiryWins++;
                case NEITHER_WON -> draws++;
            }
        }

        System.out.printf("%n  confirm won: %d   expiry won: %d   neither: %d%n",
                confirmWins, expiryWins, draws);

        // Both directions must actually be reachable, or the test is only ever
        // exercising one branch and quietly proving nothing about the other.
        assertThat(confirmWins).as("confirmation must be able to win the race").isPositive();
        assertThat(expiryWins).as("expiry must be able to win the race").isPositive();
    }

    private enum Outcome { CONFIRM_WON, EXPIRY_WON, NEITHER_WON }

    /**
     * Sets a hold's deadline relative to the DATABASE clock, then fires confirm and
     * expire simultaneously and checks the wreckage.
     *
     * <p>The deadline is computed with Postgres' {@code now()} rather than the JVM's
     * clock on purpose. Both transitions test {@code expires_at} against {@code now()}
     * inside the database, so anchoring the setup to the same clock is what makes a
     * zero offset a real dead heat instead of an accident of clock skew.
     */
    private Outcome raceOnce(double deadlineOffsetSeconds) throws Exception {
        UUID seatId = newSeat();
        Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());

        // created_at moves with expires_at. A row created now but expiring a second
        // ago is not a hold any real code path can produce, and the schema's
        // ck_hold_expiry_after_creation constraint correctly refuses it.
        jdbc.update("""
                UPDATE holds
                   SET created_at = now() + make_interval(secs => ?),
                       expires_at = now() + make_interval(secs => ?)
                 WHERE id = ?
                """,
                deadlineOffsetSeconds - 1.0, deadlineOffsetSeconds, hold.id());
        Hold armed = holds.findById(hold.id()).orElseThrow();

        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger expired = new AtomicInteger();
        List<Throwable> unexpected = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?> confirmTask = executor.submit(() -> {
                try {
                    startGate.await();
                    booking.confirm(armed.id());
                    confirmed.incrementAndGet();
                } catch (HoldNoLongerActiveException expectedLoss) {
                    // Lost the race. Correct outcome.
                } catch (Throwable t) {
                    synchronized (unexpected) {
                        unexpected.add(t);
                    }
                }
            });

            Future<?> expireTask = executor.submit(() -> {
                try {
                    startGate.await();
                    if (expirer.expire(armed)) {
                        expired.incrementAndGet();
                    }
                } catch (Throwable t) {
                    synchronized (unexpected) {
                        unexpected.add(t);
                    }
                }
            });

            startGate.countDown();
            confirmTask.get(30, TimeUnit.SECONDS);
            expireTask.get(30, TimeUnit.SECONDS);
        }

        assertThat(unexpected).as("neither side may fail in a way we did not model").isEmpty();

        boolean confirmWon = confirmed.get() == 1;
        boolean expiryWon = expired.get() == 1;

        assertThat(confirmWon && expiryWon)
                .as("a seat cannot be both sold and returned to the pool")
                .isFalse();

        String holdState = jdbc.queryForObject(
                "SELECT state FROM holds WHERE id = ?", String.class, armed.id());
        String seatStatus = jdbc.queryForObject(
                "SELECT status FROM seats WHERE id = ?", String.class, seatId);

        if (confirmWon) {
            assertThat(holdState).isEqualTo("CONFIRMED");
            assertThat(seatStatus)
                    .as("a confirmed hold must leave its seat sold")
                    .isEqualTo("BOOKED");
            return Outcome.CONFIRM_WON;
        }
        if (expiryWon) {
            assertThat(holdState).isEqualTo("EXPIRED");
            assertThat(seatStatus)
                    .as("an expired hold must return its seat to the pool")
                    .isEqualTo("AVAILABLE");
            return Outcome.EXPIRY_WON;
        }

        // The third outcome: the sweeper was a hair early and the confirmation a
        // hair late. Nothing changed, and the next sweep will collect it.
        assertThat(holdState)
                .as("if neither side won, the hold must be untouched and still sweepable")
                .isEqualTo("ACTIVE");
        assertThat(seatStatus).isEqualTo("HELD");
        return Outcome.NEITHER_WON;
    }

    private UUID newSeat() {
        UUID seatId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO seats (id, event_id, section, row_label, seat_number, price_cents, status)
                VALUES (?, ?, ?, ?, ?, ?, 'AVAILABLE')
                """,
                seatId, eventId, "GA", "A", Math.abs(seatId.hashCode()), 12_500L);
        return seatId;
    }
}
