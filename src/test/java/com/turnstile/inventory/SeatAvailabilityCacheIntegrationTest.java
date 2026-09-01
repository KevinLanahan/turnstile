package com.turnstile.inventory;

import com.turnstile.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Lua claim script's semantics, against a real Redis.
 *
 * <p>{@link #aRetryOfTheSameRequestIsAReplay()} is the test that justifies using a
 * script at all. A bare {@code SET NX} would be atomic and would pass every other
 * assertion in this class - and would fail that one, rejecting a client from its
 * own hold on retry. Telling "someone else has this seat" apart from "this is the
 * same request again" requires reading the current value, comparing it, and
 * conditionally setting, without another client interleaving. That is what
 * running the whole thing as one script on Redis's single command loop buys.
 */
class SeatAvailabilityCacheIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TTL = Duration.ofMinutes(5);

    @Autowired
    private SeatAvailabilityCache cache;

    @Test
    @DisplayName("an unmarked seat is claimable")
    void anUnmarkedSeatIsClaimable() {
        assertThat(cache.tryClaim(UUID.randomUUID(), "key-1", TTL)).isEqualTo(ClaimVerdict.CLAIMED);
    }

    @Test
    @DisplayName("a different request is shed without touching Postgres")
    void aDifferentRequestIsShed() {
        UUID seatId = UUID.randomUUID();
        assertThat(cache.tryClaim(seatId, "key-1", TTL)).isEqualTo(ClaimVerdict.CLAIMED);

        assertThat(cache.tryClaim(seatId, "key-2", TTL))
                .as("someone else holds it - reject before spending a connection")
                .isEqualTo(ClaimVerdict.UNAVAILABLE);
    }

    @Test
    @DisplayName("a retry of the same request is a replay, not a rejection")
    void aRetryOfTheSameRequestIsAReplay() {
        UUID seatId = UUID.randomUUID();
        cache.tryClaim(seatId, "key-1", TTL);

        assertThat(cache.tryClaim(seatId, "key-1", TTL))
                .as("a client must never be locked out of its own hold by the cache")
                .isEqualTo(ClaimVerdict.REPLAY);
    }

    @Test
    @DisplayName("releasing your own mark frees the seat")
    void releasingYourOwnMarkFreesTheSeat() {
        UUID seatId = UUID.randomUUID();
        cache.tryClaim(seatId, "key-1", TTL);

        cache.release(seatId, "key-1");

        assertThat(cache.tryClaim(seatId, "key-2", TTL)).isEqualTo(ClaimVerdict.CLAIMED);
    }

    @Test
    @DisplayName("a stranger cannot release someone else's mark")
    void aStrangerCannotReleaseSomeoneElsesMark() {
        UUID seatId = UUID.randomUUID();
        cache.tryClaim(seatId, "key-1", TTL);

        cache.release(seatId, "key-2");

        assertThat(cache.tryClaim(seatId, "key-3", TTL))
                .as("compare-and-delete: GET then DEL would have deleted a stranger's mark")
                .isEqualTo(ClaimVerdict.UNAVAILABLE);
    }
}
