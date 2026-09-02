package org.pragmatica.aether.example.banking.transfer;

import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.pragmatica.aether.example.banking.account.AccountService;
import org.pragmatica.aether.example.banking.shared.Account;
import org.pragmatica.aether.example.banking.shared.AccountId;
import org.pragmatica.aether.example.banking.shared.Balance;
import org.pragmatica.aether.example.banking.shared.Currency;
import org.pragmatica.aether.example.banking.shared.Money;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;


/// Hand-rolled [AccountService] double for the transfer saga tests.
///
/// It exists to make individual legs of the saga fail on demand. `credit` is the interesting one:
/// the saga calls it once on the destination (the forward leg) and, when that fails, once on the
/// source (the compensating leg), so registering a failure per account id is enough to drive either
/// leg -- or both -- into failure without any test framework.
final class StubAccountService implements AccountService {
    private final Map<AccountId, Account> accounts = new ConcurrentHashMap<>();
    private final Map<AccountId, Cause> creditFailures = new ConcurrentHashMap<>();
    private final Map<AccountId, Cause> debitFailures = new ConcurrentHashMap<>();
    private final List<AccountId> creditedAccounts = new CopyOnWriteArrayList<>();

    static StubAccountService stubAccountService() {
        return new StubAccountService();
    }

    AccountId register(String holderName, Currency currency) {
        var account = Account.account(AccountId.generate(), holderName, holderName + "@example.com", currency);

        accounts.put(account.id(), account);

        return account.id();
    }

    void failCreditFor(AccountId accountId, Cause cause) {
        creditFailures.put(accountId, cause);
    }

    void failDebitFor(AccountId accountId, Cause cause) {
        debitFailures.put(accountId, cause);
    }

    /// Every account id `credit` was called on, in call order -- lets a test prove the compensating
    /// credit was actually attempted rather than merely recorded.
    List<AccountId> creditedAccounts() {
        return List.copyOf(creditedAccounts);
    }

    @Override
    public Promise<Account> openAccount(String holderName, String email, Currency currency) {
        var account = Account.account(AccountId.generate(), holderName, email, currency);

        accounts.put(account.id(), account);

        return Promise.success(account);
    }

    @Override
    public Promise<Account> getAccount(AccountId accountId) {
        return option(accounts.get(accountId)).async(new AccountError.NotFound(accountId));
    }

    @Override
    public Promise<Balance> getBalance(AccountId accountId) {
        return option(accounts.get(accountId)).map(account -> Balance.zero(account.currency()))
                                              .async(new AccountError.NotFound(accountId));
    }

    @Override
    public Promise<Unit> closeAccount(AccountId accountId) {
        return option(accounts.get(accountId)).map(account -> account.withStatus(Account.AccountStatus.CLOSED))
                                              .onPresent(account -> accounts.put(accountId, account))
                                              .map(_ -> Promise.unitPromise())
                                              .or(() -> new AccountError.NotFound(accountId).promise());
    }

    @Override
    public Promise<Unit> credit(AccountId accountId, Money amount) {
        creditedAccounts.add(accountId);

        return outcome(creditFailures, accountId);
    }

    @Override
    public Promise<Unit> debit(AccountId accountId, Money amount) {
        return outcome(debitFailures, accountId);
    }

    private static Promise<Unit> outcome(Map<AccountId, Cause> failures, AccountId accountId) {
        return option(failures.get(accountId)).map(Cause::<Unit>promise)
                                              .or(Promise::unitPromise);
    }

    static Money usd(String amount) {
        return Money.money(new BigDecimal(amount), Currency.USD).unwrap();
    }
}
