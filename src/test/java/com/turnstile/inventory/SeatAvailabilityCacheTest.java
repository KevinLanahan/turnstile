package com.turnstile.inventory;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Fail-open behaviour, verified against a Redis that genuinely is not there.
 *
 * <p>This is the property that makes it safe to put a cache in front of the
 * database. A cache that failed CLOSED would convert a Redis outage into a total
 * outage: every seat would look unavailable, and the system would reject
 * legitimate traffic while a perfectly healthy database sat idle.
 *
 * <p>No mocking framework here, deliberately. A mocked template throwing a
 * hand-picked exception mostly proves that a {@code catch} block was written. A
 * real Lettuce client pointed at a closed port exercises the failure Lettuce
 * actually produces, which is the thing production will hand us.
 */
class SeatAvailabilityCacheTest {

    private final UUID seatId = UUID.randomUUID();
    private final Duration ttl = Duration.ofMinutes(5);

    private LettuceConnectionFactory deadFactory;
    private SeatAvailabilityCache cache;

    @BeforeEach
    void pointAtNothing() throws IOException {
        // Claim a port, then immediately release it. Connections to it are refused
        // fast, which is a far more realistic failure than a fabricated exception.
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(250))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofMillis(250))
                                .build())
                        .build())
                .build();

        deadFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("localhost", closedPort), clientConfig);
        deadFactory.afterPropertiesSet();

        cache = new SeatAvailabilityCache(new StringRedisTemplate(deadFactory));
    }

    @AfterEach
    void tearDown() {
        if (deadFactory != null) {
            deadFactory.destroy();
        }
    }

    @Test
    @DisplayName("an unreachable Redis falls through to Postgres rather than rejecting")
    void unreachableRedisFailsOpen() {
        assertThat(cache.tryClaim(seatId, "some-key", ttl))
                .as("a Redis outage must cost throughput, never correctness")
                .isEqualTo(ClaimVerdict.CLAIMED);
    }

    @Test
    @DisplayName("release never propagates a Redis failure to the caller")
    void releaseSwallowsFailures() {
        // The mark carries a TTL, so an unreleased mark self-heals. The caller is
        // usually already handling a more interesting exception and must not have
        // it replaced by this one.
        assertThatCode(() -> cache.release(seatId, "some-key")).doesNotThrowAnyException();
    }
}
