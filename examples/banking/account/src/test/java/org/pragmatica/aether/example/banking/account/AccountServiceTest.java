package org.pragmatica.aether.example.banking.account;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.example.banking.account.AccountService.AccountError;
import org.pragmatica.aether.example.banking.shared.Account;
import org.pragmatica.aether.example.banking.shared.AccountId;
import org.pragmatica.aether.example.banking.shared.Currency;
import org.pragmatica.aether.example.banking.shared.Money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Slice-level tests for [AccountService] against the [InMemoryAccountPersistence] double.
///
/// These do not need a database: the SQL in [AccountPersistence] is validated at compile time by the
/// `@PgSql` processor, so what is left to test here is the slice's own behaviour -- that it turns a
/// rejected conditional UPDATE back into the right typed error.
class AccountServiceTest {
    private AccountService accountService;

    @BeforeEach
    void setup() {
        accountService = AccountService.accountService(InMemoryAccountPersistence.inMemoryAccountPersistence());
    }

    @Nested
    class OpenAccount {
        @Test
        void openAccount_succeeds_forNewHolder() {
            accountService.openAccount("Alice", "alice@example.com", Currency.USD)
                          .await()
                          .onFailureRun(() -> fail("Expected success"))
                          .onSuccess(account -> {
                              assertThat(account.holderName()).isEqualTo("Alice");
                              assertThat(account.email()).isEqualTo("alice@example.com");
                              assertThat(account.currency()).isEqualTo(Currency.USD);
                              assertThat(account.isActive()).isTrue();
                          });
        }

        @Test
        void openAccount_startsAtZeroBalance() {
            var account = openUsdAccount();

            accountService.getBalance(account.id())
                          .await()
                          .onFailureRun(() -> fail("Expected success"))
                          .onSuccess(balance -> assertThat(balance.available()
                                                                  .amount()).isEqualByComparingTo(BigDecimal.ZERO));
        }
    }

    @Nested
    class Lookups {
        @Test
        void getAccount_returnsPersistedAccount_afterOpen() {
            var account = openUsdAccount();

            accountService.getAccount(account.id())
                          .await()
                          .onFailureRun(() -> fail("Expected success"))
                          .onSuccess(found -> assertThat(found.id()).isEqualTo(account.id()));
        }

        @Test
        void getAccount_fails_forUnknownId() {
            accountService.getAccount(AccountId.generate())
                          .await()
                          .onSuccessRun(() -> fail("Expected failure"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(AccountError.NotFound.class));
        }

        @Test
        void getBalance_fails_forUnknownId() {
            accountService.getBalance(AccountId.generate())
                          .await()
                          .onSuccessRun(() -> fail("Expected failure"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(AccountError.NotFound.class));
        }
    }

    @Nested
    class CreditAndDebit {
        @Test
        void credit_increasesAvailableBalance() {
            var account = openUsdAccount();

            creditOrFail(account, "100.00");

            assertAvailable(account, "100.00");
        }

        @Test
        void debit_decreasesAvailableBalance_whenFundsSuffice() {
            var account = openUsdAccount();

            creditOrFail(account, "100.00");

            accountService.debit(account.id(), usd("40.00"))
                          .await()
                          .onFailureRun(() -> fail("Expected success"));

            assertAvailable(account, "60.00");
        }

        @Test
        void debit_fails_withInsufficientFunds_whenBalanceTooLow() {
            var account = openUsdAccount();

            creditOrFail(account, "10.00");

            accountService.debit(account.id(), usd("40.00"))
                          .await()
                          .onSuccessRun(() -> fail("Expected failure"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(AccountError.InsufficientFunds.class));
        }

        @Test
        void debit_leavesBalanceUntouched_whenFundsInsufficient() {
            var account = openUsdAccount();

            creditOrFail(account, "10.00");

            accountService.debit(account.id(), usd("40.00")).await();

            assertAvailable(account, "10.00");
        }

        @Test
        void debit_fails_withNotFound_forUnknownAccount() {
            accountService.debit(AccountId.generate(), usd("1.00"))
                          .await()
                          .onSuccessRun(() -> fail("Expected failure"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(AccountError.NotFound.class));
        }

        @Test
        void credit_fails_withCurrencyMismatch_forForeignCurrency() {
            var account = openUsdAccount();

            accountService.credit(account.id(), money("5.00", Currency.EUR))
                          .await()
                          .onSuccessRun(() -> fail("Expected failure"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(Money.MoneyError.CurrencyMismatch.class));
        }
    }

    @Nested
    class CloseAccount {
        @Test
        void closeAccount_marksAccountInactive() {
            var account = openUsdAccount();

            accountService.closeAccount(account.id())
                          .await()
                          .onFailureRun(() -> fail("Expected success"));

            accountService.getAccount(account.id())
                          .await()
                          .onFailureRun(() -> fail("Expected success"))
                          .onSuccess(found -> assertThat(found.isActive()).isFalse());
        }

        @Test
        void closeAccount_fails_forAlreadyClosedAccount() {
            var account = openUsdAccount();

            accountService.closeAccount(account.id()).await();

            accountService.closeAccount(account.id())
                          .await()
                          .onSuccessRun(() -> fail("Expected failure"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(AccountError.NotActive.class));
        }

        @Test
        void closeAccount_fails_forUnknownAccount() {
            accountService.closeAccount(AccountId.generate())
                          .await()
                          .onSuccessRun(() -> fail("Expected failure"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(AccountError.NotFound.class));
        }
    }

    private Account openUsdAccount() {
        return accountService.openAccount("Alice", "alice@example.com", Currency.USD)
                             .await()
                             .unwrap();
    }

    private void creditOrFail(Account account, String amount) {
        accountService.credit(account.id(), usd(amount))
                      .await()
                      .onFailureRun(() -> fail("Expected success"));
    }

    private void assertAvailable(Account account, String expected) {
        accountService.getBalance(account.id())
                      .await()
                      .onFailureRun(() -> fail("Expected success"))
                      .onSuccess(balance -> assertThat(balance.available()
                                                              .amount()).isEqualByComparingTo(new BigDecimal(expected)));
    }

    private static Money usd(String amount) {
        return money(amount, Currency.USD);
    }

    private static Money money(String amount, Currency currency) {
        return Money.money(new BigDecimal(amount), currency).unwrap();
    }
}
