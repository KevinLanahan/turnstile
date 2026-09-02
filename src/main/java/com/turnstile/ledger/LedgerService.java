package com.turnstile.ledger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Posts money movements as balanced double-entry transfers.
 *
 * <p>Nothing here trusts itself. The application checks that a transfer balances
 * before writing it, and the database checks again at commit via a deferred
 * constraint trigger. The second check is the one that matters: it is the reason
 * a bug in this class cannot produce a quietly wrong balance, only a failed
 * transaction.
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    private final LedgerRepository ledger;
    private final AccountRepository accounts;

    public LedgerService(LedgerRepository ledger, AccountRepository accounts) {
        this.ledger = ledger;
        this.accounts = accounts;
    }

    /**
     * Records a purchase: money leaves the customer, the same money arrives in the
     * event's revenue account.
     *
     * <p>Idempotent on {@code idempotencyKey}. Replaying a payment returns the
     * transfer that already exists rather than charging a second time - the single
     * most important property a payment path can have.
     */
    @Transactional
    public Transfer recordPurchase(UUID userId, UUID eventId, long amountCents,
                                   String currency, String idempotencyKey, String memo) {
        Optional<Transfer> replay = ledger.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            log.debug("Idempotent replay of transfer for key {}", idempotencyKey);
            return replay.get();
        }

        Account customer = accounts.findOrCreate(AccountKind.CUSTOMER, userId, currency);
        Account revenue = accounts.findOrCreate(AccountKind.EVENT_REVENUE, eventId, currency);

        Transfer transfer = new Transfer(
                UUID.randomUUID(), TransferKind.PURCHASE, idempotencyKey, null, memo, null);

        post(transfer, List.of(
                Posting.debit(customer.id(), amountCents, currency),
                Posting.credit(revenue.id(), amountCents, currency)));

        return transfer;
    }

    /**
     * Reverses an earlier transfer by posting its mirror image.
     *
     * <p>The original entries are never touched. After a refund the ledger shows
     * both the sale and the reversal, which is what makes it an audit trail rather
     * than a current-state table. The schema permits at most one reversal per
     * transfer, so a double refund is rejected by the database.
     */
    @Transactional
    public Transfer refund(UUID originalTransferId, String idempotencyKey, String memo) {
        Optional<Transfer> replay = ledger.findByIdempotencyKey(idempotencyKey);
        if (replay.isPresent()) {
            return replay.get();
        }

        Transfer original = ledger.findById(originalTransferId).orElseThrow(() ->
                new IllegalArgumentException("No transfer " + originalTransferId + " to refund"));

        List<Posting> mirrored = ledger.findPostings(originalTransferId).stream()
                .map(p -> new Posting(p.accountId(), -p.amountCents(), p.currency()))
                .toList();

        Transfer reversal = new Transfer(
                UUID.randomUUID(), TransferKind.REFUND, idempotencyKey, original.id(), memo, null);

        post(reversal, mirrored);
        return reversal;
    }

    private void post(Transfer transfer, List<Posting> postings) {
        long imbalance = postings.stream().mapToLong(Posting::amountCents).sum();
        if (imbalance != 0) {
            // Belt and braces. The database would refuse this at commit anyway, but
            // failing here produces a far more useful stack trace than a constraint
            // violation surfacing from a trigger three layers down.
            throw new IllegalArgumentException(
                    "Refusing to post an unbalanced transfer: off by " + imbalance + " cents");
        }

        ledger.insertTransfer(transfer);
        ledger.insertPostings(transfer.id(), postings);
        log.debug("Posted {} transfer {} with {} postings",
                transfer.kind(), transfer.id(), postings.size());
    }

    public long balanceOf(AccountKind kind, UUID ownerId, String currency) {
        return accounts.find(kind, ownerId, currency)
                .map(a -> accounts.balanceCents(a.id()))
                .orElse(0L);
    }
}
