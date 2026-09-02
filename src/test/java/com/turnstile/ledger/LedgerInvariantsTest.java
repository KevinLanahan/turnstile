package com.turnstile.ledger;

import com.turnstile.support.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three rules the ledger schema exists to make unbreakable.
 *
 * <p>Every one of these is enforced by Postgres, not by Java. That distinction is
 * the whole argument: application code is where bugs live, and this is the part of
 * the system where a bug is measured in dollars rather than in stack traces. A
 * balance that is wrong and committed is far worse than a transaction that failed.
 */
class LedgerInvariantsTest extends AbstractIntegrationTest {

    private static final String USD = "USD";

    @Autowired
    private LedgerService ledger;

    @Autowired
    private LedgerRepository ledgerRepo;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    private TransactionTemplate tx;

    @BeforeEach
    void clearLedger() {
        // TRUNCATE, not DELETE - the append-only trigger below blocks DELETE by
        // design. Worth knowing that TRUNCATE does not fire row-level DELETE
        // triggers, so it slips past that guarantee; in a real deployment the
        // application role would simply not hold TRUNCATE on this table.
        jdbc.execute("TRUNCATE ledger_entries, transfers, accounts CASCADE");
        tx = new TransactionTemplate(txManager);
    }

    // ---------------------------------------------------------------- rule 1

    @Test
    @DisplayName("a purchase debits the customer and credits the event by the same amount")
    void aPurchaseMovesMoneyWithoutCreatingIt() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        ledger.recordPurchase(userId, eventId, 12_500L, USD, "key-1", "Seat A1");

        assertThat(ledger.balanceOf(AccountKind.CUSTOMER, userId, USD))
                .as("money left the customer")
                .isEqualTo(-12_500L);
        assertThat(ledger.balanceOf(AccountKind.EVENT_REVENUE, eventId, USD))
                .as("the same money arrived at the event")
                .isEqualTo(12_500L);
        assertThat(ledgerRepo.globalImbalanceCents())
                .as("nothing was created or destroyed")
                .isZero();
    }

    @Test
    @DisplayName("the database refuses to commit a transfer that does not balance")
    void anUnbalancedTransferCannotCommit() {
        UUID transferId = UUID.randomUUID();
        Account customer = accounts.findOrCreate(AccountKind.CUSTOMER, UUID.randomUUID(), USD);

        // Write a transfer and ONE leg of it - money leaving an account and arriving
        // nowhere. This is what a half-finished payment path produces, and it is the
        // single most dangerous thing that can happen to a ledger, because it looks
        // like a successful write.
        assertThatThrownBy(() ->
                tx.executeWithoutResult(status -> {
                    jdbc.update("""
                            INSERT INTO transfers (id, kind, idempotency_key)
                            VALUES (?, 'PURCHASE', ?)
                            """, transferId, "half-a-transfer");
                    jdbc.update("""
                            INSERT INTO ledger_entries (transfer_id, account_id, amount_cents, currency)
                            VALUES (?, ?, ?, ?)
                            """, transferId, customer.id(), -9_900L, USD);
                }))
                .as("the deferred constraint trigger must fire at COMMIT")
                .hasMessageContaining("does not balance");

        assertThat(ledgerRepo.globalImbalanceCents())
                .as("the failed transaction left nothing behind")
                .isZero();
        assertThat(ledgerRepo.findById(transferId))
                .as("the whole transaction rolled back, transfer row included")
                .isEmpty();
    }

    @Test
    @DisplayName("both legs may be written in separate statements, as long as they balance by commit")
    void deferredCheckAllowsMultiStatementTransfers() {
        UUID transferId = UUID.randomUUID();
        Account customer = accounts.findOrCreate(AccountKind.CUSTOMER, UUID.randomUUID(), USD);
        Account revenue = accounts.findOrCreate(AccountKind.EVENT_REVENUE, UUID.randomUUID(), USD);

        // This is why the trigger is DEFERRED rather than firing per statement:
        // real posting code writes legs one at a time, and the books are only
        // required to balance at the end.
        tx.executeWithoutResult(status -> {
            jdbc.update("INSERT INTO transfers (id, kind, idempotency_key) VALUES (?, 'PURCHASE', ?)",
                    transferId, "two-statements");
            jdbc.update("""
                    INSERT INTO ledger_entries (transfer_id, account_id, amount_cents, currency)
                    VALUES (?, ?, ?, ?)
                    """, transferId, customer.id(), -5_000L, USD);
            jdbc.update("""
                    INSERT INTO ledger_entries (transfer_id, account_id, amount_cents, currency)
                    VALUES (?, ?, ?, ?)
                    """, transferId, revenue.id(), 5_000L, USD);
        });

        assertThat(ledgerRepo.findPostings(transferId)).hasSize(2);
        assertThat(ledgerRepo.globalImbalanceCents()).isZero();
    }

    // ---------------------------------------------------------------- rule 2

    @Test
    @DisplayName("ledger entries cannot be edited")
    void entriesCannotBeUpdated() {
        UUID userId = UUID.randomUUID();
        ledger.recordPurchase(userId, UUID.randomUUID(), 4_200L, USD, "key-edit", null);

        assertThatThrownBy(() -> jdbc.update("UPDATE ledger_entries SET amount_cents = 1"))
                .as("a ledger you can edit is a spreadsheet, not a ledger")
                .hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("ledger entries cannot be deleted")
    void entriesCannotBeDeleted() {
        ledger.recordPurchase(UUID.randomUUID(), UUID.randomUUID(), 4_200L, USD, "key-del", null);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM ledger_entries"))
                .hasMessageContaining("append-only");
    }

    // ---------------------------------------------------------------- refunds

    @Test
    @DisplayName("a refund reverses the money without erasing the sale")
    void aRefundReversesWithoutErasing() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        Transfer purchase = ledger.recordPurchase(userId, eventId, 12_500L, USD, "buy-1", "Seat A1");
        ledger.refund(purchase.id(), "refund-1", "Customer changed their mind");

        assertThat(ledger.balanceOf(AccountKind.CUSTOMER, userId, USD))
                .as("the customer is made whole")
                .isZero();
        assertThat(ledger.balanceOf(AccountKind.EVENT_REVENUE, eventId, USD))
                .as("the event gives the money back")
                .isZero();

        // The audit trail is the point: both events remain visible.
        assertThat(ledgerRepo.findPostings(purchase.id()))
                .as("the original sale is still on the books, untouched")
                .hasSize(2);
        Integer transfers = jdbc.queryForObject("SELECT count(*) FROM transfers", Integer.class);
        assertThat(transfers).as("a sale and a reversal, not a deleted sale").isEqualTo(2);
    }

    @Test
    @DisplayName("a transfer cannot be refunded twice")
    void aTransferCannotBeRefundedTwice() {
        Transfer purchase = ledger.recordPurchase(
                UUID.randomUUID(), UUID.randomUUID(), 8_000L, USD, "buy-2", null);
        ledger.refund(purchase.id(), "refund-2a", null);

        // Different idempotency key, so this is a genuine second refund attempt
        // rather than a replay - and the partial unique index refuses it.
        assertThatThrownBy(() -> ledger.refund(purchase.id(), "refund-2b", null))
                .as("paying a customer back twice is money destroyed")
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_transfers_one_reversal");

        assertThat(ledgerRepo.globalImbalanceCents()).isZero();
    }

    // ------------------------------------------------------------ idempotency

    @Test
    @DisplayName("replaying a payment does not charge twice")
    void replayingAPaymentDoesNotChargeTwice() {
        UUID userId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        Transfer first = ledger.recordPurchase(userId, eventId, 12_500L, USD, "same-key", null);
        Transfer second = ledger.recordPurchase(userId, eventId, 12_500L, USD, "same-key", null);

        assertThat(second.id()).as("the same key yields the same transfer").isEqualTo(first.id());
        assertThat(ledger.balanceOf(AccountKind.CUSTOMER, userId, USD))
                .as("charged once, not twice")
                .isEqualTo(-12_500L);
    }

    // ------------------------------------------------------- global invariant

    @Test
    @DisplayName("debits equal credits across many purchases and refunds")
    void debitsEqualCreditsAcrossManyMovements() {
        UUID eventId = UUID.randomUUID();

        for (int i = 0; i < 50; i++) {
            UUID userId = UUID.randomUUID();
            Transfer purchase = ledger.recordPurchase(
                    userId, eventId, 1_000L + i * 37L, USD, "bulk-" + i, null);

            // Refund every third one, so the ledger holds a realistic mix.
            if (i % 3 == 0) {
                ledger.refund(purchase.id(), "bulk-refund-" + i, null);
            }
        }

        assertThat(ledgerRepo.globalImbalanceCents())
                .as("the only acceptable answer for a ledger's global sum")
                .isZero();
    }
}
