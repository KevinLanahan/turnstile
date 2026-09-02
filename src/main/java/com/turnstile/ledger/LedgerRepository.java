package com.turnstile.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class LedgerRepository {

    private static final RowMapper<Transfer> TRANSFER_MAPPER = (rs, rowNum) -> new Transfer(
            rs.getObject("id", UUID.class),
            TransferKind.valueOf(rs.getString("kind")),
            rs.getString("idempotency_key"),
            rs.getObject("reverses_id", UUID.class),
            rs.getString("memo"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public LedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertTransfer(Transfer transfer) {
        jdbc.update("""
                INSERT INTO transfers (id, kind, idempotency_key, reverses_id, memo)
                VALUES (?, ?, ?, ?, ?)
                """,
                transfer.id(), transfer.kind().name(), transfer.idempotencyKey(),
                transfer.reversesId(), transfer.memo());
    }

    public void insertPostings(UUID transferId, List<Posting> postings) {
        List<Object[]> batch = postings.stream()
                .map(p -> new Object[]{transferId, p.accountId(), p.amountCents(), p.currency()})
                .toList();

        jdbc.batchUpdate("""
                INSERT INTO ledger_entries (transfer_id, account_id, amount_cents, currency)
                VALUES (?, ?, ?, ?)
                """, batch);
    }

    public Optional<Transfer> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.query("SELECT * FROM transfers WHERE idempotency_key = ?",
                        TRANSFER_MAPPER, idempotencyKey)
                .stream()
                .findFirst();
    }

    public Optional<Transfer> findById(UUID transferId) {
        return jdbc.query("SELECT * FROM transfers WHERE id = ?", TRANSFER_MAPPER, transferId)
                .stream()
                .findFirst();
    }

    public List<Posting> findPostings(UUID transferId) {
        return jdbc.query("""
                SELECT account_id, amount_cents, currency
                  FROM ledger_entries
                 WHERE transfer_id = ?
                 ORDER BY id
                """,
                (rs, rowNum) -> new Posting(
                        rs.getObject("account_id", UUID.class),
                        rs.getLong("amount_cents"),
                        rs.getString("currency").trim()),
                transferId);
    }

    /**
     * The global invariant: across every account and every entry ever written, the
     * signed total must be zero. Any other answer means money was created or
     * destroyed somewhere, and the ledger is no longer trustworthy.
     */
    public long globalImbalanceCents() {
        Long total = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_entries", Long.class);
        return total == null ? 0L : total;
    }
}
