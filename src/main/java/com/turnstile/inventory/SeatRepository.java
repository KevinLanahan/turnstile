package com.turnstile.inventory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class SeatRepository {

    private static final RowMapper<Seat> SEAT_MAPPER = (rs, rowNum) -> new Seat(
            rs.getObject("id", UUID.class),
            rs.getObject("event_id", UUID.class),
            rs.getString("section"),
            rs.getString("row_label"),
            rs.getInt("seat_number"),
            rs.getLong("price_cents"),
            SeatStatus.valueOf(rs.getString("status")),
            rs.getObject("hold_id", UUID.class),
            rs.getLong("version"));

    private final JdbcTemplate jdbc;

    public SeatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The single most important query in this codebase.
     *
     * <p>This is an atomic conditional update, not a read-then-write. Under
     * contention Postgres (READ COMMITTED) serialises the writers on the row
     * lock: the first transaction to arrive updates the row, and every other
     * transaction blocks until it commits, then RE-EVALUATES the WHERE clause
     * against the new row version. By then {@code status} is no longer
     * 'AVAILABLE', so they match zero rows and lose cleanly.
     *
     * <p>There is deliberately no {@code SELECT ... FOR UPDATE} and no
     * distributed lock. A lock held outside the database can be lost to a
     * network partition or a GC pause while its holder still believes it owns
     * the resource; a row lock inside the transaction that does the write
     * cannot. Correctness lives in the database.
     *
     * @return 1 if this caller claimed the seat, 0 if someone else got there first
     */
    public int tryHold(UUID seatId, UUID holdId) {
        return jdbc.update("""
                UPDATE seats
                   SET status     = 'HELD',
                       hold_id    = ?,
                       version    = version + 1,
                       updated_at = now()
                 WHERE id     = ?
                   AND status = 'AVAILABLE'
                """, holdId, seatId);
    }

    /**
     * Returns a held seat to the pool. Guarded on the owning hold id so a
     * stale sweeper cannot release a seat that has since been claimed by
     * somebody else.
     *
     * @return 1 if released, 0 if the seat was no longer held by this hold
     */
    public int release(UUID seatId, UUID holdId) {
        return jdbc.update("""
                UPDATE seats
                   SET status     = 'AVAILABLE',
                       hold_id    = NULL,
                       version    = version + 1,
                       updated_at = now()
                 WHERE id      = ?
                   AND hold_id = ?
                   AND status  = 'HELD'
                """, seatId, holdId);
    }

    public Optional<Seat> findById(UUID seatId) {
        return jdbc.query("SELECT * FROM seats WHERE id = ?", SEAT_MAPPER, seatId)
                .stream()
                .findFirst();
    }

    public List<Seat> findAvailableByEvent(UUID eventId) {
        return jdbc.query("""
                SELECT * FROM seats
                 WHERE event_id = ? AND status = 'AVAILABLE'
                 ORDER BY section, row_label, seat_number
                """, SEAT_MAPPER, eventId);
    }
}
