package org.pragmatica.aether.example.banking.account;

import org.pragmatica.aether.example.banking.account.AccountPersistence.AccountRow;
import org.pragmatica.aether.example.banking.account.AccountPersistence.BalanceRow;
import org.pragmatica.aether.example.banking.shared.Account;
import org.pragmatica.aether.example.banking.shared.Account.AccountStatus;
import org.pragmatica.aether.example.banking.shared.AccountId;
import org.pragmatica.aether.example.banking.shared.Balance;
import org.pragmatica.aether.example.banking.shared.Currency;
import org.pragmatica.aether.example.banking.shared.Money;
import org.pragmatica.aether.resource.aspect.Key;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Account management service.
///
/// Demonstrates:
///   - 1-param methods: getAccount, getBalance, closeAccount
///   - 2-param methods: credit, debit
///   - 3-param method: openAccount
///   - @Key annotation on getBalance for cache key extraction
///   - @WithCache interceptor for method-level caching
///   - Real PostgreSQL persistence through the compile-time validated [AccountPersistence] adapter:
///     every balance change is a single conditional UPDATE whose WHERE clause is the business rule,
///     so "insufficient funds" arrives as a rejected row rather than as a read-modify-write race
///   - Failures rejected by that guard are re-read once and classified into the typed AccountError
///     variants below, so the caller learns which rule stopped it
///
/// Does NOT demonstrate: transactions (see the note on [AccountPersistence]), the `pending` half of
/// a [Balance] (persisted and read back, never moved), account currency changes, or any authorization
/// check on who may credit or debit an account.
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
                     + ": requested " + requested
                     + ", available " + available;
            }
        }

        record UnknownAccountStatus(String status) implements AccountError {
            @Override
            public String message() {
                return "Stored account status is not recognized: " + status;
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
    @InvalidateBalanceOnCredit
    Promise<Unit> credit(@Key AccountId accountId, Money amount);

    /// Debit an account. 2-param method.
    @InvalidateBalanceOnDebit
    Promise<Unit> debit(@Key AccountId accountId, Money amount);

    // === Factory ===
    static AccountService accountService(AccountPersistence persistence) {
        return new accountService(persistence);
    }

    record accountService(AccountPersistence persistence) implements AccountService {
        @Override
        public Promise<Account> openAccount(String holderName, String email, Currency currency) {
            return persistence.insertAccount(AccountId.generate().value(),
                                             holderName,
                                             email,
                                             currency.code(),
                                             AccountStatus.ACTIVE.name())
                              .ensureWith(this::insertZeroBalance)
                              .flatMap(accountService::toAccount);
        }

        @Override
        public Promise<Account> getAccount(AccountId accountId) {
            return persistence.findAccount(accountId.value())
                              .flatMap(found -> requireAccount(accountId, found))
                              .flatMap(accountService::toAccount);
        }

        @Override
        public Promise<Balance> getBalance(AccountId accountId) {
            return persistence.findBalance(accountId.value())
                              .flatMap(found -> requireBalance(accountId, found))
                              .flatMap(accountService::toBalance);
        }

        @Override
        public Promise<Unit> closeAccount(AccountId accountId) {
            return getAccount(accountId).flatMap(account -> ensureActive(accountId, account))
                             .flatMap(() -> markClosed(accountId));
        }

        @Override
        public Promise<Unit> credit(AccountId accountId, Money amount) {
            return persistence.creditBalance(amount.amount(),
                                             accountId.value(),
                                             amount.currency().code())
                              .flatMap(updated -> confirmChange(accountId, amount, updated));
        }

        @Override
        public Promise<Unit> debit(AccountId accountId, Money amount) {
            return persistence.debitBalance(amount.amount(),
                                            accountId.value(),
                                            amount.currency().code())
                              .flatMap(updated -> confirmChange(accountId, amount, updated));
        }

        private Promise<Unit> insertZeroBalance(AccountRow row) {
            return persistence.insertZeroBalance(row.accountId(), row.currency());
        }

        private Promise<Unit> markClosed(AccountId accountId) {
            return persistence.updateStatus(AccountStatus.CLOSED.name(),
                                            accountId.value())
                              .flatMap(updated -> requireAccount(accountId, updated))
                              .mapToUnit();
        }

        /// The conditional UPDATE returns the new row when its guard held and nothing when it did not.
        /// "Nothing" is not yet a diagnosis, so only the rejected path pays for a read-back that says
        /// WHY -- missing account, wrong currency, or not enough money. The happy path stays at one
        /// statement.
        private Promise<Unit> confirmChange(AccountId accountId, Money amount, Option<BalanceRow> updated) {
            return updated.map(_ -> Promise.unitPromise())
                          .or(() -> explainRejection(accountId, amount));
        }

        private Promise<Unit> explainRejection(AccountId accountId, Money amount) {
            return persistence.findBalance(accountId.value())
                              .flatMap(found -> requireBalance(accountId, found))
                              .flatMap(accountService::toBalance)
                              .flatMap(balance -> classifyRejection(accountId, amount, balance));
        }

        private static Promise<Unit> classifyRejection(AccountId accountId, Money requested, Balance balance) {
            var available = balance.available();

            return isSameCurrency(available, requested)
                   ? new AccountError.InsufficientFunds(accountId, requested, available).promise()
                   : new Money.MoneyError.CurrencyMismatch(available.currency(), requested.currency()).promise();
        }

        private static boolean isSameCurrency(Money available, Money requested) {
            return available.currency()
                            .equals(requested.currency());
        }

        private static Promise<Account> ensureActive(AccountId accountId, Account account) {
            return account.isActive()
                   ? Promise.success(account)
                   : new AccountError.NotActive(accountId).promise();
        }

        private static Promise<AccountRow> requireAccount(AccountId accountId, Option<AccountRow> found) {
            return found.async(new AccountError.NotFound(accountId));
        }

        private static Promise<BalanceRow> requireBalance(AccountId accountId, Option<BalanceRow> found) {
            return found.async(new AccountError.NotFound(accountId));
        }

        private static Promise<Account> toAccount(AccountRow row) {
            return Result.all(AccountId.accountId(row.accountId()),
                              Currency.currency(row.currency()),
                              accountStatus(row.status()))
                         .map((id, currency, status) -> Account.account(id,
                                                                        row.holderName(),
                                                                        row.email(),
                                                                        currency,
                                                                        status,
                                                                        row.createdAt()))
                         .async();
        }

        private static Promise<Balance> toBalance(BalanceRow row) {
            return Currency.currency(row.currency())
                           .flatMap(currency -> toBalance(row, currency))
                           .async();
        }

        private static Result<Balance> toBalance(BalanceRow row, Currency currency) {
            return Result.all(Money.money(row.amount(),
                                          currency),
                              Money.money(row.pending(),
                                          currency))
                         .map(Balance::balance);
        }

        private static Result<AccountStatus> accountStatus(String raw) {
            return Result.lift(_ -> new AccountError.UnknownAccountStatus(raw), () -> AccountStatus.valueOf(raw));
        }
    }
}
