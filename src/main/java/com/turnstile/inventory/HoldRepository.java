package com.turnstile.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class HoldRepository {

    private static final RowMapper<Hold> HOLD_MAPPER = (rs, rowNum) -> new Hold(
            rs.getObject("id", UUID.class),
            rs.getObject("seat_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            HoldState.valueOf(rs.getString("state")),
            rs.getString("idempotency_key"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("expires_at").toInstant());

    private final JdbcTemplate jdbc;

    public HoldRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Hold hold) {
        jdbc.update("""
                INSERT INTO holds (id, seat_id, user_id, state, idempotency_key, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                hold.id(),
                hold.seatId(),
                hold.userId(),
                hold.state().name(),
                hold.idempotencyKey(),
                Timestamp.from(hold.createdAt()),
                Timestamp.from(hold.expiresAt()));
    }

    /**
     * ACTIVE -> CONFIRMED, but only while the hold is still live.
     *
     * <p>This statement and {@link #tryExpire(UUID)} are the two halves of the
     * nastiest race in the system: a payment confirming at the exact millisecond
     * the sweeper decides the hold is dead. Both are conditional updates against
     * the SAME row, so Postgres serialises them on that row's lock, and whichever
     * arrives second re-evaluates {@code state = 'ACTIVE'} against the committed
     * result of the first and matches nothing.
     *
     * <p>The arbitration therefore happens on the hold, not on the seat. That is
     * deliberate: the seat is what both operations want to change, but the hold is
     * what they both have to agree about first.
     *
     * @return 1 if this caller confirmed the hold, 0 if it was expired, already
     *         confirmed, or released
     */
    public int tryConfirm(UUID holdId) {
        return jdbc.update("""
                UPDATE holds
                   SET state = 'CONFIRMED'
                 WHERE id         = ?
                   AND state      = 'ACTIVE'
                   AND expires_at > now()
                """, holdId);
    }

    /**
     * ACTIVE -> EXPIRED, but only once the hold is genuinely overdue.
     *
     * <p>Note that {@code now()} is evaluated by Postgres inside the transaction,
     * not by the application. Two application servers with skewed clocks would
     * otherwise disagree about whether a hold is dead, and the seat would be both
     * sold and released.
     *
     * @return 1 if this caller expired the hold, 0 if it was confirmed first or is
     *         not yet due
     */
    public int tryExpire(UUID holdId) {
        return jdbc.update("""
                UPDATE holds
                   SET state = 'EXPIRED'
                 WHERE id         = ?
                   AND state      = 'ACTIVE'
                   AND expires_at <= now()
                """, holdId);
    }

    /** Overdue holds awaiting expiry, oldest first. Served by idx_holds_state_expires. */
    public List<Hold> findOverdue(int limit) {
        return jdbc.query("""
                SELECT * FROM holds
                 WHERE state = 'ACTIVE' AND expires_at <= now()
                 ORDER BY expires_at
                 LIMIT ?
                """, HOLD_MAPPER, limit);
    }

    public Optional<Hold> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("SELECT * FROM holds WHERE idempotency_key = ?", HOLD_MAPPER, idempotencyKey)
                .stream()
                .findFirst();
    }

    public Optional<Hold> findById(UUID holdId) {
        return jdbc.query("SELECT * FROM holds WHERE id = ?", HOLD_MAPPER, holdId)
                .stream()
                .findFirst();
    }

    public int countActiveForSeat(UUID seatId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM holds WHERE seat_id = ? AND state = 'ACTIVE'",
                Integer.class, seatId);
        return count == null ? 0 : count;
    }
}
