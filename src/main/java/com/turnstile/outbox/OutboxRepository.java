package com.turnstile.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private static final RowMapper<OutboxEvent> EVENT_MAPPER = (rs, rowNum) -> new OutboxEvent(
            rs.getLong("id"),
            rs.getObject("aggregate_id", UUID.class),
            rs.getString("event_type"),
            rs.getString("payload"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getInt("attempts"));

    private final JdbcTemplate jdbc;

    public OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Appends an event. Intended to be called from inside the caller's existing
     * transaction - that is the entire point. If the business change rolls back,
     * this row goes with it, and nothing is ever announced that did not happen.
     */
    public void append(UUID aggregateId, String eventType, String jsonPayload) {
        jdbc.update("""
                INSERT INTO outbox (aggregate_id, event_type, payload)
                VALUES (?, ?, ?::jsonb)
                """, aggregateId, eventType, jsonPayload);
    }

    public List<OutboxEvent> findUnpublished(int limit) {
        return jdbc.query("""
                SELECT * FROM outbox
                 WHERE published_at IS NULL
                 ORDER BY id
                 LIMIT ?
                """, EVENT_MAPPER, limit);
    }

    /**
     * Marks an event delivered.
     *
     * <p>This deliberately happens AFTER publishing, in a separate statement, which
     * is precisely where at-least-once delivery comes from: a crash between the
     * publish and this update means the event is published again on restart. That
     * window cannot be closed - closing it would require a distributed transaction
     * across the database and the message broker - so the consumer is made
     * idempotent instead.
     */
    public void markPublished(long eventId) {
        jdbc.update("UPDATE outbox SET published_at = now() WHERE id = ?", eventId);
    }

    public void recordFailure(long eventId, String error) {
        jdbc.update("""
                UPDATE outbox
                   SET attempts = attempts + 1,
                       last_error = ?
                 WHERE id = ?
                """, error == null ? null : error.substring(0, Math.min(error.length(), 500)), eventId);
    }

    public long unpublishedCount() {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM outbox WHERE published_at IS NULL", Long.class);
        return n == null ? 0 : n;
    }
}
