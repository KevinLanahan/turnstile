package com.turnstile.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
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
