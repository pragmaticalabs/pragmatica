package org.pragmatica.aether.example.banking.account;

import org.pragmatica.aether.example.banking.shared.Account;
import org.pragmatica.aether.example.banking.shared.AccountId;
import org.pragmatica.aether.example.banking.shared.Balance;
import org.pragmatica.aether.example.banking.shared.Currency;
import org.pragmatica.aether.example.banking.shared.Money;
import org.pragmatica.aether.resource.aspect.Key;
import org.pragmatica.aether.resource.db.Sql;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Option.option;

/// Account management service.
///
/// Demonstrates:
///   - 1-param methods: getAccount, getBalance, closeAccount
///   - 2-param methods: credit, debit
///   - 3-param method: openAccount
///   - @Key annotation on getBalance for cache key extraction
///   - @WithCache interceptor for method-level caching
///   - @Sql resource dependency for factory injection
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
    static AccountService accountService(@Sql SqlConnector db) {
        return new accountService(db, new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
    }

    record accountService(SqlConnector db,
                          Map<AccountId, Account> accounts,
                          Map<AccountId, Balance> balances) implements AccountService {

        @Override
        public Promise<Account> openAccount(String holderName, String email, Currency currency) {
            var id = AccountId.generate();
            var account = Account.account(id, holderName, email, currency);
            accounts.put(id, account);
            balances.put(id, Balance.zero(currency));
            return Promise.success(account);
        }

        @Override
        public Promise<Account> getAccount(AccountId accountId) {
            return option(accounts.get(accountId))
                .map(Promise::success)
                .or(() -> new AccountError.NotFound(accountId).promise());
        }

        @Override
        public Promise<Balance> getBalance(AccountId accountId) {
            return option(balances.get(accountId))
                .map(Promise::success)
                .or(() -> new AccountError.NotFound(accountId).promise());
        }

        @Override
        public Promise<Unit> closeAccount(AccountId accountId) {
            return getAccount(accountId)
                .flatMap(account -> ensureActive(accountId, account))
                .map(account -> markClosed(accountId, account));
        }

        @Override
        public Promise<Unit> credit(AccountId accountId, Money amount) {
            return getBalance(accountId)
                .flatMap(balance -> addToBalance(balance, amount))
                .map(newBalance -> storeBalance(accountId, newBalance));
        }

        @Override
        public Promise<Unit> debit(AccountId accountId, Money amount) {
            return getBalance(accountId)
                .flatMap(balance -> ensureSufficientFunds(accountId, balance, amount))
                .flatMap(balance -> subtractFromBalance(balance, amount))
                .map(newBalance -> storeBalance(accountId, newBalance));
        }

        private static Promise<Account> ensureActive(AccountId accountId, Account account) {
            return account.isActive()
                   ? Promise.success(account)
                   : new AccountError.NotActive(accountId).promise();
        }

        private Unit markClosed(AccountId accountId, Account account) {
            accounts.compute(accountId, (_, _) -> account.withStatus(Account.AccountStatus.CLOSED));
            return Unit.unit();
        }

        private static Promise<Balance> addToBalance(Balance balance, Money amount) {
            return balance.available()
                          .add(amount)
                          .map(newAvailable -> Balance.balance(newAvailable, balance.pending()))
                          .async();
        }

        private static Promise<Balance> ensureSufficientFunds(AccountId accountId, Balance balance, Money amount) {
            var available = balance.available();
            return available.amount().compareTo(amount.amount()) >= 0
                   ? Promise.success(balance)
                   : new AccountError.InsufficientFunds(accountId, amount, available).promise();
        }

        private static Promise<Balance> subtractFromBalance(Balance balance, Money amount) {
            return balance.available()
                          .subtract(amount)
                          .map(newAvailable -> Balance.balance(newAvailable, balance.pending()))
                          .async();
        }

        private Unit storeBalance(AccountId accountId, Balance newBalance) {
            balances.put(accountId, newBalance);
            return Unit.unit();
        }
    }
}
