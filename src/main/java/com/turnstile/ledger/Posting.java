package com.turnstile.ledger;

import java.util.UUID;

/**
 * One leg of a transfer: an amount landing in an account.
 *
 * <p>Signed cents. Negative leaves the account, positive arrives. A transfer is
 * a list of these whose amounts sum to zero.
 */
public record Posting(UUID accountId, long amountCents, String currency) {

    public static Posting debit(UUID accountId, long amountCents, String currency) {
        return new Posting(accountId, -Math.abs(amountCents), currency);
    }

    public static Posting credit(UUID accountId, long amountCents, String currency) {
        return new Posting(accountId, Math.abs(amountCents), currency);
    }
}
