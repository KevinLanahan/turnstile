package com.turnstile.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Seeds one demo event so the app is usable the moment it boots.
 *
 * <p>Strictly {@code dev} profile. Production data does not appear by magic, and a
 * seeder that ran everywhere would be a genuinely dangerous thing to have on the
 * classpath.
 */
@Configuration
@Profile("dev")
class DevDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DevDataSeeder.class);

    private static final String[] ROWS = {"A", "B", "C", "D", "E"};
    private static final int SEATS_PER_ROW = 10;

    @Bean
    ApplicationRunner seedDemoEvent(JdbcTemplate jdbc) {
        return args -> {
            Long existing = jdbc.queryForObject("SELECT count(*) FROM events", Long.class);
            if (existing != null && existing > 0) {
                log.info("Dev data already present, leaving it alone");
                return;
            }

            UUID eventId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO events (id, name, venue, starts_at, sales_open_at)
                    VALUES (?, ?, ?, ?, ?)
                    """,
                    eventId,
                    "Flash Sale Tour - Opening Night",
                    "Madison Square Garden, New York",
                    Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
                    Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS)));

            List<Object[]> batch = new ArrayList<>();
            for (String row : ROWS) {
                for (int number = 1; number <= SEATS_PER_ROW; number++) {
                    // Front rows cost more, because of course they do.
                    long priceCents = 12_500L - (row.charAt(0) - 'A') * 1_500L;
                    batch.add(new Object[]{UUID.randomUUID(), eventId, "ORCH", row, number, priceCents});
                }
            }
            jdbc.batchUpdate("""
                    INSERT INTO seats (id, event_id, section, row_label, seat_number, price_cents, status)
                    VALUES (?, ?, ?, ?, ?, ?, 'AVAILABLE')
                    """, batch);

            log.info("Seeded demo event {} with {} seats", eventId, batch.size());
        };
    }
}
