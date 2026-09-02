package com.turnstile.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConsumedEventRepository {

    private final JdbcTemplate jdbc;

    public ConsumedEventRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims an event for a consumer, exactly once.
     *
     * <p>{@code ON CONFLICT DO NOTHING} makes this a single atomic
     * test-and-set: the first caller inserts and gets 1 row, every redelivery
     * conflicts and gets 0. Checking "have I seen this?" and then inserting would
     * be two statements with a gap in between, and two threads handed the same
     * redelivered event would both slip through it.
     *
     * @return true if this consumer has not handled the event before
     */
    public boolean claim(String consumer, long eventId) {
        return jdbc.update("""
                INSERT INTO consumed_events (consumer, event_id)
                VALUES (?, ?)
                ON CONFLICT (consumer, event_id) DO NOTHING
                """, consumer, eventId) == 1;
    }
}
