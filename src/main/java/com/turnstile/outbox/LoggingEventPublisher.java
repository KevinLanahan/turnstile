package com.turnstile.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Default destination: the log. Swapping this for a Kafka producer is the change
 * described in DESIGN.md as a stretch goal - it touches this class and nothing else,
 * which is the reason the relay talks to an interface.
 *
 * <p>Note: no {@code @ConditionalOnMissingBean} here. That annotation is built for
 * {@code @Bean} methods in auto-configuration classes, where Spring controls the
 * ordering; on a component-scanned class the condition is evaluated during scanning
 * in an order nobody controls. An alternative publisher should replace this one with
 * {@code @Primary}, not by hoping a condition evaluates in the right sequence.
 */
@Component
public class LoggingEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingEventPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info("PUBLISH {} #{} aggregate={} payload={}",
                event.eventType(), event.id(), event.aggregateId(), event.payload());
    }
}
