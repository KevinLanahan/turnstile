package com.turnstile.inventory;

import com.turnstile.common.SeatUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final SeatRepository seats;
    private final HoldRepository holds;
    private final Duration holdTtl;

    public InventoryService(SeatRepository seats,
                            HoldRepository holds,
                            @Value("${turnstile.hold.ttl:PT5M}") Duration holdTtl) {
        this.seats = seats;
        this.holds = holds;
        this.holdTtl = holdTtl;
    }

    /**
     * Claims a seat for a user, or fails cleanly if somebody else got it first.
     *
     * <p>Ordering here is deliberate. The seat claim happens BEFORE the hold row
     * is written, so the row lock on {@code seats} is what serialises concurrent
     * callers. If the claim fails we throw, the transaction rolls back, and no
     * hold row is left behind.
     *
     * @throws SeatUnavailableException if the seat was not AVAILABLE
     */
    @Transactional
    public Hold hold(UUID seatId, UUID userId, String idempotencyKey) {
        // 1. Idempotent replay: the same key always yields the same hold.
        //
        //    KNOWN GAP (M1): two *simultaneous* requests carrying the same key can
        //    both miss this read. The loser is caught by the unique index below and
        //    currently surfaces as a 409 rather than as a replay. Postgres aborts the
        //    transaction on constraint violation, so we cannot simply re-read here -
        //    the real fix is INSERT ... ON CONFLICT DO NOTHING RETURNING, which lands
        //    in M4 alongside payment idempotency.
        Optional<Hold> replay = holds.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            log.debug("Idempotent replay for key {} -> hold {}", idempotencyKey, replay.get().id());
            return replay.get();
        }

        UUID holdId = UUID.randomUUID();

        // 2. The atomic claim. This is the whole ballgame.
        int claimed = seats.tryHold(seatId, holdId);
        if (claimed == 0) {
            throw new SeatUnavailableException(seatId);
        }

        // 3. Record the hold. The partial unique index on (seat_id) WHERE state='ACTIVE'
        //    is a backstop: if it ever fires, the claim above and the holds table have
        //    drifted apart, and we would rather fail the request than oversell.
        Instant now = Instant.now();
        Hold hold = new Hold(holdId, seatId, userId, HoldState.ACTIVE, idempotencyKey, now, now.plus(holdTtl));
        try {
            holds.insert(hold);
        } catch (DuplicateKeyException ex) {
            log.warn("Hold insert rejected by a unique constraint for seat {} (key={})", seatId, idempotencyKey, ex);
            throw new SeatUnavailableException(seatId);
        }

        log.debug("Seat {} held by user {} until {}", seatId, userId, hold.expiresAt());
        return hold;
    }
}
