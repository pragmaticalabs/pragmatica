package org.pragmatica.aether.example.banking.account;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;


/// In-memory [AccountPersistence] for testing, mirroring the InMemoryUrlPersistence double used by
/// url-shortener-v2.
///
/// The point of this double is that it reproduces the SQL *semantics* the slice depends on, not just
/// the storage: `creditBalance` and `debitBalance` return an empty [Option] under exactly the
/// conditions the real `WHERE` clauses reject a row -- unknown account, currency mismatch, and (for
/// debit only) a balance that would go negative. If those conditions ever drift apart from
/// `schema/V001__create_tables.sql`, the tests below stop pinning the real behaviour.
final class InMemoryAccountPersistence implements AccountPersistence {
    private final Map<String, AccountRow> accounts = new ConcurrentHashMap<>();
    private final Map<String, BalanceRow> balances = new ConcurrentHashMap<>();

    static InMemoryAccountPersistence inMemoryAccountPersistence() {
        return new InMemoryAccountPersistence();
    }

    @Override
    public Promise<AccountRow> insertAccount(String accountId,
                                             String holderName,
                                             String email,
                                             String currency,
                                             String status) {
        var row = new AccountRow(accountId, holderName, email, currency, status, Instant.now());

        accounts.put(accountId, row);

        return Promise.success(row);
    }

    @Override
    public Promise<Unit> insertZeroBalance(String accountId, String currency) {
        balances.put(accountId,
                     new BalanceRow(accountId, BigDecimal.ZERO, BigDecimal.ZERO, currency));

        return Promise.unitPromise();
    }

    @Override
    public Promise<Option<AccountRow>> findAccount(String accountId) {
        return Promise.success(option(accounts.get(accountId)));
    }

    @Override
    public Promise<Option<BalanceRow>> findBalance(String accountId) {
        return Promise.success(option(balances.get(accountId)));
    }

    @Override
    public Promise<Option<AccountRow>> updateStatus(String status, String accountId) {
        return Promise.success(option(accounts.get(accountId)).map(row -> withStatus(row, status))
                                                              .onPresent(row -> accounts.put(accountId, row)));
    }

    @Override
    public Promise<Option<BalanceRow>> creditBalance(BigDecimal amount, String accountId, String currency) {
        return Promise.success(applyDelta(accountId, currency, amount, false));
    }

    @Override
    public Promise<Option<BalanceRow>> debitBalance(BigDecimal amount, String accountId, String currency) {
        return Promise.success(applyDelta(accountId, currency, amount.negate(), true));
    }

    private Option<BalanceRow> applyDelta(String accountId, String currency, BigDecimal delta, boolean rejectNegative) {
        return option(balances.get(accountId)).filter(row -> row.currency().equals(currency))
                                              .map(row -> withAmount(row, row.amount().add(delta)))
                                              .filter(row -> !rejectNegative || row.amount().signum() >= 0)
                                              .onPresent(row -> balances.put(accountId, row));
    }

    private static AccountRow withStatus(AccountRow row, String status) {
        return new AccountRow(row.accountId(),
                              row.holderName(),
                              row.email(),
                              row.currency(),
                              status,
                              row.createdAt());
    }

    private static BalanceRow withAmount(BalanceRow row, BigDecimal amount) {
        return new BalanceRow(row.accountId(), amount, row.pending(), row.currency());
    }
}
