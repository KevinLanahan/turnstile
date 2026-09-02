package com.turnstile.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

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
                published++;
            } catch (RuntimeException ex) {
                // Leave published_at NULL so it is retried on the next pass. Ordering
                // is preserved because the query is ORDER BY id, though a permanently
                // failing event will block the ones behind it - in production this is
                // where a dead-letter threshold on `attempts` belongs.
                outbox.recordFailure(event.id(), ex.toString());
                log.warn("Failed to publish outbox event {} (attempt {}), will retry",
                        event.id(), event.attempts() + 1, ex);
                break;
            }
        }
        return published;
    }
}
