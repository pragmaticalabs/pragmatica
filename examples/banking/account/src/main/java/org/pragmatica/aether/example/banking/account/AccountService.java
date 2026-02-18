package org.pragmatica.aether.example.banking.account;

import org.pragmatica.aether.example.banking.shared.Account;
import org.pragmatica.aether.example.banking.shared.AccountId;
import org.pragmatica.aether.example.banking.shared.Balance;
import org.pragmatica.aether.example.banking.shared.Currency;
import org.pragmatica.aether.example.banking.shared.Money;
import org.pragmatica.aether.resource.aspect.Key;
import org.pragmatica.aether.resource.db.Database;
import org.pragmatica.aether.resource.db.DatabaseConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Account management service.
///
/// Demonstrates:
///   - 1-param methods: getAccount, getBalance, closeAccount
///   - 2-param methods: credit, debit
///   - 3-param method: openAccount
///   - @Key annotation on getBalance for cache key extraction
///   - @WithCache interceptor for method-level caching
///   - @Database resource dependency for factory injection
@Slice
public interface AccountService {

    // === Errors ===
    sealed interface AccountError extends Cause {
        record NotFound(AccountId accountId) implements AccountError {
            @Override
            public String message() {
                return "Account not found: " + accountId.value();
            }
        }

        record NotActive(AccountId accountId) implements AccountError {
            @Override
            public String message() {
                return "Account is not active: " + accountId.value();
            }
        }

        record InsufficientFunds(AccountId accountId, Money requested, Money available) implements AccountError {
            @Override
            public String message() {
                return "Insufficient funds in " + accountId.value()
                       + ": requested " + requested + ", available " + available;
            }
        }
    }

    // === Operations ===

    /// Open a new bank account. 3-param method.
    Promise<Account> openAccount(String holderName, String email, Currency currency);

    /// Get account details. 1-param method.
    Promise<Account> getAccount(AccountId accountId);

    /// Get account balance with caching. 1-param + @Key + @WithCache.
    @WithCache
    Promise<Balance> getBalance(@Key AccountId accountId);

    /// Close an account. 1-param method.
    Promise<Unit> closeAccount(AccountId accountId);

    /// Credit an account. 2-param method.
    Promise<Unit> credit(AccountId accountId, Money amount);

    /// Debit an account. 2-param method.
    Promise<Unit> debit(AccountId accountId, Money amount);

    // === Factory ===
    static AccountService accountService(@Database DatabaseConnector db) {
        return new accountService(db);
    }

    record accountService(DatabaseConnector db) implements AccountService {
        private static final Map<AccountId, Account> ACCOUNTS = new ConcurrentHashMap<>();
        private static final Map<AccountId, Balance> BALANCES = new ConcurrentHashMap<>();

        @Override
        public Promise<Account> openAccount(String holderName, String email, Currency currency) {
            var id = AccountId.generate();
            var account = Account.account(id, holderName, email, currency);
            ACCOUNTS.put(id, account);
            BALANCES.put(id, Balance.zero(currency));
            return Promise.success(account);
        }

        @Override
        public Promise<Account> getAccount(AccountId accountId) {
            var account = ACCOUNTS.get(accountId);
            return account != null
                   ? Promise.success(account)
                   : new AccountError.NotFound(accountId).promise();
        }

        @Override
        public Promise<Balance> getBalance(AccountId accountId) {
            var balance = BALANCES.get(accountId);
            return balance != null
                   ? Promise.success(balance)
                   : new AccountError.NotFound(accountId).promise();
        }

        @Override
        public Promise<Unit> closeAccount(AccountId accountId) {
            return getAccount(accountId)
                .flatMap(account -> {
                    if (!account.isActive()) {
                        return new AccountError.NotActive(accountId).promise();
                    }
                    ACCOUNTS.put(accountId, new Account(account.id(),
                                                         account.holderName(),
                                                         account.email(),
                                                         account.currency(),
                                                         Account.AccountStatus.CLOSED,
                                                         account.createdAt()));
                    return Promise.success(Unit.unit());
                });
        }

        @Override
        public Promise<Unit> credit(AccountId accountId, Money amount) {
            return getBalance(accountId)
                .flatMap(balance -> balance.available()
                                          .add(amount)
                                          .map(newAvailable -> Balance.balance(newAvailable, balance.pending()))
                                          .async())
                .map(newBalance -> {
                    BALANCES.put(accountId, newBalance);
                    return Unit.unit();
                });
        }

        @Override
        public Promise<Unit> debit(AccountId accountId, Money amount) {
            return getBalance(accountId)
                .flatMap(balance -> {
                    var available = balance.available();
                    if (available.amount().compareTo(amount.amount()) < 0) {
                        return new AccountError.InsufficientFunds(accountId, amount, available).promise();
                    }
                    return available.subtract(amount)
                                    .map(newAvailable -> Balance.balance(newAvailable, balance.pending()))
                                    .async();
                })
                .map(newBalance -> {
                    BALANCES.put(accountId, newBalance);
                    return Unit.unit();
                });
        }
    }
}
