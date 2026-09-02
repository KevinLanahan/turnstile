package com.turnstile.booking;

import com.turnstile.common.HoldNoLongerActiveException;
import com.turnstile.common.SeatUnavailableException;
import com.turnstile.inventory.Hold;
import com.turnstile.inventory.HoldExpirySweeper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hold state machine, one transition at a time and without concurrency.
 *
 * <p>{@link HoldExpiryRaceTest} covers what happens when two transitions collide.
 * This covers what each one is supposed to do on its own, which is what makes a
 * race-test failure interpretable: if these are green and that one is red, the bug
 * is in the interleaving rather than in the transitions themselves.
 */
class HoldLifecycleTest extends AbstractIntegrationTest {

    @Autowired
    private InventoryService inventory;

    @Autowired
    private BookingService booking;

    @Autowired
    private HoldExpirySweeper sweeper;

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
                eventId, "Lifecycle", "The Void",
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now()));

        jdbc.update("""
                INSERT INTO seats (id, event_id, section, row_label, seat_number, price_cents, status)
                VALUES (?, ?, ?, ?, ?, ?, 'AVAILABLE')
                """, seatId, eventId, "GA", "A", 1, 12_500L);
    }

    @Test
    @DisplayName("confirming a live hold books the seat")
    void confirmingALiveHoldBooksTheSeat() {
        Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());

        booking.confirm(hold.id());

        assertThat(holdState(hold.id())).isEqualTo("CONFIRMED");
        assertThat(seatStatus()).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("confirming twice fails the second time")
    void confirmingTwiceFailsTheSecondTime() {
        Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
        booking.confirm(hold.id());

        assertThatThrownBy(() -> booking.confirm(hold.id()))
                .isInstanceOf(HoldNoLongerActiveException.class);

        assertThat(seatStatus()).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("a booked seat cannot be held again")
    void aBookedSeatCannotBeHeldAgain() {
        Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
        booking.confirm(hold.id());

        assertThatThrownBy(() ->
                inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString()))
                .isInstanceOf(SeatUnavailableException.class);
    }

    @Test
    @DisplayName("the sweeper expires an overdue hold and returns the seat to the pool")
    void sweeperExpiresOverdueHoldAndFreesTheSeat() {
        Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
        expireDeadline(hold.id());

        assertThat(sweeper.sweep()).isEqualTo(1);

        assertThat(holdState(hold.id())).isEqualTo("EXPIRED");
        assertThat(seatStatus()).isEqualTo("AVAILABLE");
        assertThat(jdbc.queryForObject("SELECT hold_id FROM seats WHERE id = ?", UUID.class, seatId))
                .isNull();
    }

    @Test
    @DisplayName("a seat freed by expiry can be sold to someone else")
    void aSeatFreedByExpiryCanBeSoldToSomeoneElse() {
        Hold abandoned = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
        expireDeadline(abandoned.id());
        sweeper.sweep();

        // This is the point of expiry: an abandoned checkout must not remove
        // inventory from sale permanently.
        Hold second = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
        booking.confirm(second.id());

        assertThat(seatStatus()).isEqualTo("BOOKED");
    }

    @Test
    @DisplayName("the sweeper leaves live holds alone")
    void sweeperLeavesLiveHoldsAlone() {
        Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());

        assertThat(sweeper.sweep()).isZero();

        assertThat(holdState(hold.id())).isEqualTo("ACTIVE");
        assertThat(seatStatus()).isEqualTo("HELD");
    }

    @Test
    @DisplayName("the sweeper cannot expire a hold that was already confirmed")
    void sweeperCannotExpireAConfirmedHold() {
        Hold hold = inventory.hold(seatId, UUID.randomUUID(), UUID.randomUUID().toString());
        booking.confirm(hold.id());

        // Backdate a CONFIRMED hold past its deadline. The sweeper selects on
        // state='ACTIVE', so a sold seat can never be dragged back into the pool
        // by a late sweep.
        expireDeadline(hold.id());

        assertThat(sweeper.sweep()).isZero();
        assertThat(seatStatus()).isEqualTo("BOOKED");
    }

    /**
     * Ages a hold into the past so the sweeper will collect it.
     *
     * <p>Both timestamps move. Backdating only {@code expires_at} would produce a
     * row that was created now and expired a second ago, which violates
     * {@code ck_hold_expiry_after_creation} - and rightly so, because no real hold
     * has ever looked like that. An overdue hold is one that was created a while
     * ago and whose deadline has since passed.
     */
    private void expireDeadline(UUID holdId) {
        jdbc.update("""
                UPDATE holds
                   SET created_at = now() - make_interval(secs => 300),
                       expires_at = now() - make_interval(secs => 1)
                 WHERE id = ?
                """, holdId);
    }

    private String holdState(UUID holdId) {
        return jdbc.queryForObject("SELECT state FROM holds WHERE id = ?", String.class, holdId);
    }

    private String seatStatus() {
        return jdbc.queryForObject("SELECT status FROM seats WHERE id = ?", String.class, seatId);
    }
}
