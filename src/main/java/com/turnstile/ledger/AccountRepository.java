package com.turnstile.ledger;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private static final RowMapper<Account> ACCOUNT_MAPPER = (rs, rowNum) -> new Account(
            rs.getObject("id", UUID.class),
            AccountKind.valueOf(rs.getString("kind")),
            rs.getObject("owner_id", UUID.class),
            rs.getString("currency").trim());

    private final JdbcTemplate jdbc;

    public AccountRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Returns the account for this owner, creating it on first use.
     *
     * <p>Concurrency-safe without a lock: {@code ON CONFLICT DO NOTHING} lets two
     * simultaneous first-time buyers race harmlessly, and whichever loses simply
     * reads the row the winner created. The alternative - check, then insert -
     * would let both create an account, and a customer with two accounts is a
     * customer whose balance is wrong.
     */
    public Account findOrCreate(AccountKind kind, UUID ownerId, String currency) {
        jdbc.update("""
                INSERT INTO accounts (id, kind, owner_id, currency)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (kind, owner_id, currency) DO NOTHING
                """, UUID.randomUUID(), kind.name(), ownerId, currency);

        return find(kind, ownerId, currency).orElseThrow(() ->
                new IllegalStateException("Account vanished immediately after upsert: "
                        + kind + "/" + ownerId));
    }

    public Optional<Account> find(AccountKind kind, UUID ownerId, String currency) {
        return jdbc.query("""
                SELECT * FROM accounts
                 WHERE kind = ? AND owner_id = ? AND currency = ?
                """, ACCOUNT_MAPPER, kind.name(), ownerId, currency)
                .stream()
                .findFirst();
    }

    /**
     * Balance derived from the entries, via the {@code account_balances} view.
     * There is no stored balance to disagree with the entries.
     */
    public long balanceCents(UUID accountId) {
        Long balance = jdbc.queryForObject(
                "SELECT balance_cents FROM account_balances WHERE account_id = ?",
                Long.class, accountId);
        return balance == null ? 0L : balance;
    }
}
