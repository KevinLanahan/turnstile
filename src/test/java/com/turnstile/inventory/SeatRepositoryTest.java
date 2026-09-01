package com.turnstile.inventory;

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
 * Contract tests for the atomic conditional claim, with no concurrency involved.
 *
 * <p>Why this exists as a separate class: {@link SeatContentionTest} proves the
 * SYSTEM never oversells, but it cannot prove WHICH layer prevents it. Delete the
 * {@code AND status = 'AVAILABLE'} guard from {@link SeatRepository#tryHold} and
 * that test still passes, because the {@code uq_one_active_hold_per_seat} index
 * quietly rejects the surplus holds and the service maps those rejections to the
 * same 409 the test is counting. Defence in depth is doing its job - and hiding
 * the bug.
 *
 * <p>These tests call the repository directly, so the unique index is never in a
 * position to cover for a missing guard. If {@code tryHold} stops being
 * conditional, {@link #claimingAHeldSeatMatchesNothing()} goes red immediately.
 */
class SeatRepositoryTest extends AbstractIntegrationTest {

    @Autowired
    private SeatRepository seats;

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
                eventId, "Repository Contract", "The Void",
                Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                Timestamp.from(Instant.now()));

        jdbc.update("""
                INSERT INTO seats (id, event_id, section, row_label, seat_number, price_cents, status)
                VALUES (?, ?, ?, ?, ?, ?, 'AVAILABLE')
                """,
                seatId, eventId, "GA", "A", 1, 12_500L);
    }

    @Test
    @DisplayName("claiming an available seat matches exactly one row")
    void claimingAnAvailableSeatMatchesOneRow() {
        assertThat(seats.tryHold(seatId, UUID.randomUUID())).isEqualTo(1);

        Seat seat = seats.findById(seatId).orElseThrow();
        assertThat(seat.status()).isEqualTo(SeatStatus.HELD);
        assertThat(seat.version()).isEqualTo(1L);
    }

    @Test
    @DisplayName("claiming a seat someone else already holds matches nothing")
    void claimingAHeldSeatMatchesNothing() {
        UUID firstHold = UUID.randomUUID();
        UUID secondHold = UUID.randomUUID();

        assertThat(seats.tryHold(seatId, firstHold))
                .as("the first caller claims the seat")
                .isEqualTo(1);

        // THE ASSERTION THAT MATTERS.
        // This is the guard in the WHERE clause, tested in isolation. No unique
        // index, no transaction rollback, nothing else that could mask its absence.
        assertThat(seats.tryHold(seatId, secondHold))
                .as("a seat that is not AVAILABLE must match zero rows")
                .isEqualTo(0);

        Seat seat = seats.findById(seatId).orElseThrow();
        assertThat(seat.holdId())
                .as("the losing caller must not overwrite the winner's hold")
                .isEqualTo(firstHold);
        assertThat(seat.version())
                .as("a failed claim must not bump the row version")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("release returns the seat to the pool")
    void releaseReturnsSeatToPool() {
        UUID holdId = UUID.randomUUID();
        seats.tryHold(seatId, holdId);

        assertThat(seats.release(seatId, holdId)).isEqualTo(1);

        Seat seat = seats.findById(seatId).orElseThrow();
        assertThat(seat.status()).isEqualTo(SeatStatus.AVAILABLE);
        assertThat(seat.holdId()).isNull();
    }

    @Test
    @DisplayName("a stale hold cannot release a seat it no longer owns")
    void staleHoldCannotReleaseSeat() {
        UUID currentHold = UUID.randomUUID();
        UUID staleHold = UUID.randomUUID();
        seats.tryHold(seatId, currentHold);

        assertThat(seats.release(seatId, staleHold))
                .as("an expiry sweeper holding an outdated hold id must be a no-op")
                .isEqualTo(0);

        assertThat(seats.findById(seatId).orElseThrow().status()).isEqualTo(SeatStatus.HELD);
    }
}
