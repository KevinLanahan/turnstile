package com.turnstile.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads committed events out of the outbox and publishes them.
 *
 * <p>Runs outside any business transaction, on its own schedule. It is the piece
 * that turns "the event definitely exists in our database" into "the event has
 * definitely reached the outside world at least once".
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final EventPublisher publisher;
    private final int batchSize;

    /**
     * Consecutive publish failures. The failure mode this class is built for is
     * "the broker is down", which means failures arrive continuously rather than
     * once - and a stack trace per failed event per retry pass would take the log
     * pipeline down alongside the broker.
     */
    private final AtomicLong consecutiveFailures = new AtomicLong();

    public OutboxRelay(OutboxRepository outbox,
                       EventPublisher publisher,
                       @Value("${turnstile.outbox.batch-size:100}") int batchSize) {
        this.outbox = outbox;
        this.publisher = publisher;
        this.batchSize = batchSize;
    }

    /**
     * @return how many events were successfully published
     */
    public int relay() {
        List<OutboxEvent> pending = outbox.findUnpublished(batchSize);
        if (pending.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEvent event : pending) {
            try {
                publisher.publish(event);

                // If the process dies right here - published but not yet marked -
                // this event is delivered again after restart. That is at-least-once,
                // and it is why the consumer records what it has already handled.
                outbox.markPublished(event.id());
                consecutiveFailures.set(0);
                published++;
            } catch (RuntimeException ex) {
                // Leave published_at NULL so it is retried on the next pass. Ordering
                // is preserved because the query is ORDER BY id, though a permanently
                // failing event will block the ones behind it - in production this is
                // where a dead-letter threshold on `attempts` belongs.
                outbox.recordFailure(event.id(), ex.toString());
                noteFailure(event, ex);
                break;
            }
        }
        return published;
    }

    /**
     * Full detail on the first failure, then one summary line per hundred. Enough
     * to diagnose, not enough to drown in.
     */
    private void noteFailure(OutboxEvent event, RuntimeException ex) {
        long failures = consecutiveFailures.incrementAndGet();
        if (failures == 1) {
            log.warn("Failed to publish outbox event {} (attempt {}); will retry. "
                    + "Further failures will be summarised.", event.id(), event.attempts() + 1, ex);
        } else if (failures % 100 == 0) {
            log.warn("Outbox delivery still failing: {} consecutive failures, {} events pending.",
                    failures, outbox.unpublishedCount());
        }
    }

    /** Consecutive publish failures; zero means delivery is healthy. */
    public long consecutiveFailures() {
        return consecutiveFailures.get();
    }
}
