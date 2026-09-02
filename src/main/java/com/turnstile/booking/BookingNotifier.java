package com.turnstile.booking;

import com.turnstile.outbox.ConsumedEventRepository;
import com.turnstile.outbox.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A downstream consumer of booking events - stands in for the thing that would
 * send a confirmation email or a ticket.
 *
 * <p>It exists to demonstrate the half of the outbox pattern people forget. The
 * relay guarantees at-least-once delivery, which means this WILL occasionally be
 * handed an event it has already seen. Sending a customer two confirmation emails
 * is the mild version of that failure; charging them twice is the version that
 * matters.
 *
 * <p>The defence is to claim the event id and the side effect in the SAME
 * transaction. If the side effect fails, the claim rolls back and the event is
 * retried. If the claim conflicts, the side effect never runs. There is no
 * ordering of the two that leaks.
 */
@Component
public class BookingNotifier {

    private static final Logger log = LoggerFactory.getLogger(BookingNotifier.class);
    private static final String CONSUMER = "booking-notifier";

    private final ConsumedEventRepository consumed;
    private final AtomicLong notificationsSent = new AtomicLong();

    public BookingNotifier(ConsumedEventRepository consumed) {
        this.consumed = consumed;
    }

    /**
     * @return true if this delivery did the work, false if it was a duplicate
     */
    @Transactional
    public boolean handle(OutboxEvent event) {
        if (!consumed.claim(CONSUMER, event.id())) {
            log.debug("Ignoring redelivery of outbox event {}", event.id());
            return false;
        }

        // The "side effect". In a real service: send the ticket.
        notificationsSent.incrementAndGet();
        log.info("Confirmation sent for {} (event {})", event.aggregateId(), event.id());
        return true;
    }

    public long notificationsSent() {
        return notificationsSent.get();
    }
}
