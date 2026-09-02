package com.turnstile.outbox;

/**
 * Where outbox events go once they leave this service.
 *
 * <p>In a deployed system this is a Kafka producer, an SNS topic, or an HTTP call
 * to a downstream service. The interface exists so that swapping the destination
 * does not touch the relay, and so tests can assert on what was published without
 * running a broker.
 */
public interface EventPublisher {

    /**
     * @throws RuntimeException if delivery failed; the relay will retry the event
     */
    void publish(OutboxEvent event);
}
