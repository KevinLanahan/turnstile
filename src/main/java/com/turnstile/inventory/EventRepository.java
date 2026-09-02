package com.turnstile.inventory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class EventRepository {

    private static final RowMapper<Event> EVENT_MAPPER = (rs, rowNum) -> new Event(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("venue"),
            rs.getTimestamp("starts_at").toInstant(),
            rs.getTimestamp("sales_open_at").toInstant());

    private final JdbcTemplate jdbc;

    public EventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Event> findAll() {
        return jdbc.query("SELECT * FROM events ORDER BY starts_at", EVENT_MAPPER);
    }

    public Optional<Event> findById(UUID eventId) {
        return jdbc.query("SELECT * FROM events WHERE id = ?", EVENT_MAPPER, eventId)
                .stream()
                .findFirst();
    }

    public long countSeats(UUID eventId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM seats WHERE event_id = ?", Long.class, eventId);
        return n == null ? 0 : n;
    }

    public long countAvailableSeats(UUID eventId) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM seats WHERE event_id = ? AND status = 'AVAILABLE'", Long.class, eventId);
        return n == null ? 0 : n;
    }
}
