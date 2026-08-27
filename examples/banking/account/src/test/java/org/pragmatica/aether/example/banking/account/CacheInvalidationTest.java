package org.pragmatica.aether.example.banking.account;

import java.math.BigDecimal;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.example.banking.shared.Account;
import org.pragmatica.aether.example.banking.shared.AccountId;
import org.pragmatica.aether.example.banking.shared.Balance;
import org.pragmatica.aether.example.banking.shared.Currency;
import org.pragmatica.aether.example.banking.shared.Money;
import org.pragmatica.aether.resource.interceptor.CacheConfig;
import org.pragmatica.aether.resource.interceptor.CacheInterceptorFactory;
import org.pragmatica.aether.resource.interceptor.CacheMethodInterceptor;
import org.pragmatica.aether.slice.ProvisioningContext;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.ProviderBasedConfigService;
import org.pragmatica.config.source.TomlConfigSource;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// #278 end-to-end proof that `resources.toml`'s three `[cache.account.*]` sections share one
/// cache namespace, exercised through the REAL TOML-binder -> [CacheInterceptorFactory] ->
/// [CacheMethodInterceptor] path -- not the plain-record construction [AccountServiceTest] uses,
/// which bypasses interceptor wiring entirely and so could never catch this bug.
///
/// [CacheInterceptorFactory] keys its shared-backend registry by `cacheName`. If [WithCache]
/// (`cache.account.getBalance`) and [InvalidateBalanceOnCredit] / [InvalidateBalanceOnDebit]
/// (`cache.account.credit` / `cache.account.debit`) ever diverge on `cache_name`, credit/debit's
/// WRITE_AROUND invalidation evicts from a cache getBalance never reads, and a stale balance is
/// served forever. `mismatchedCacheName_leavesStaleBalance_becauseInvalidationTargetsWrongCache`
/// reproduces that failure mode against a deliberately mismatched TOML to prove the two passing
/// tests above it are not vacuous.
class CacheInvalidationTest {
    private AccountService accountService;
    private Fn1<Promise<Balance>, AccountId> cachedGetBalance;
    private Fn1<Promise<Unit>, AccountMutation> interceptedCredit;
    private Fn1<Promise<Unit>, AccountMutation> interceptedDebit;

    private record AccountMutation(AccountId accountId, Money amount) {}

    @BeforeEach
    void setup() throws Exception {
        accountService = AccountService.accountService(InMemoryAccountPersistence.inMemoryAccountPersistence());

        var configService = configServiceFromShippedResourcesToml();
        var factory = new CacheInterceptorFactory();

        var getBalanceConfig = configService.config("cache.account.getBalance", CacheConfig.class).unwrap();
        var creditConfig = configService.config("cache.account.credit", CacheConfig.class).unwrap();
        var debitConfig = configService.config("cache.account.debit", CacheConfig.class).unwrap();

        var getBalanceInterceptor = provision(factory, getBalanceConfig, Fn1.<AccountId>id());
        var creditInterceptor = provision(factory, creditConfig, AccountMutation::accountId);
        var debitInterceptor = provision(factory, debitConfig, AccountMutation::accountId);

        cachedGetBalance = getBalanceInterceptor.intercept(accountService::getBalance);
        interceptedCredit = creditInterceptor.intercept(mutation -> accountService.credit(mutation.accountId(), mutation.amount()));
        interceptedDebit = debitInterceptor.intercept(mutation -> accountService.debit(mutation.accountId(), mutation.amount()));
    }

    @Test
    void creditInvalidation_clearsSharedCache_soSubsequentGetBalanceReflectsCreditedAmount() {
        var account = openUsdAccount(accountService);

        apply(interceptedCredit, new AccountMutation(account.id(), usd("100.00")));
        assertBalance(cachedGetBalance, account.id(), "100.00");

        // Bypass the interceptor: mutate persistence directly without invalidating the cache.
        accountService.credit(account.id(), usd("50.00")).await().onFailureRun(() -> fail("Expected success"));
        assertBalance(cachedGetBalance, account.id(), "100.00"); // still cached/stale -- proves a real cache hit occurred

        apply(interceptedCredit, new AccountMutation(account.id(), usd("25.00")));
        assertBalance(cachedGetBalance, account.id(), "175.00"); // 50 (direct) + 25 (intercepted) on top of 100
    }

    @Test
    void debitInvalidation_clearsSharedCache_soSubsequentGetBalanceReflectsDebitedAmount() {
        var account = openUsdAccount(accountService);

        apply(interceptedCredit, new AccountMutation(account.id(), usd("100.00")));
        assertBalance(cachedGetBalance, account.id(), "100.00");

        // Bypass the interceptor: mutate persistence directly without invalidating the cache.
        accountService.credit(account.id(), usd("50.00")).await().onFailureRun(() -> fail("Expected success"));
        assertBalance(cachedGetBalance, account.id(), "100.00"); // still cached/stale -- proves a real cache hit occurred

        apply(interceptedDebit, new AccountMutation(account.id(), usd("30.00")));
        assertBalance(cachedGetBalance, account.id(), "120.00"); // 100 + 50 (direct) - 30 (intercepted)
    }

    /// Reproduces the exact failure mode the identical `cache_name`s in `resources.toml` prevent:
    /// wired against a MISMATCHED TOML (deliberately different `cache_name` per section), credit's
    /// WRITE_AROUND invalidation targets a disconnected CacheBackend, so `getBalance` keeps serving
    /// the value cached before the credit -- forever. This proves the two tests above are pinning a
    /// real mechanism, not passing vacuously regardless of whether `cache_name` matches.
    @Test
    void mismatchedCacheName_leavesStaleBalance_becauseInvalidationTargetsWrongCache() throws Exception {
        var mismatchedToml = """
                [cache.getBalance]
                cache_name = "balance-a"
                strategy = "CACHE_ASIDE"
                ttl_seconds = 300
                max_entries = 10000
                mode = "LOCAL"

                [cache.credit]
                cache_name = "balance-b"
                strategy = "WRITE_AROUND"
                ttl_seconds = 300
                max_entries = 10000
                mode = "LOCAL"
                """;
        var provider = ConfigurationProvider.builder()
                                            .withSource(TomlConfigSource.tomlConfigSource(mismatchedToml).unwrap())
                                            .build();
        var configService = ProviderBasedConfigService.providerBasedConfigService(provider);
        var factory = new CacheInterceptorFactory();

        var getBalanceConfig = configService.config("cache.getBalance", CacheConfig.class).unwrap();
        var creditConfig = configService.config("cache.credit", CacheConfig.class).unwrap();

        var localService = AccountService.accountService(InMemoryAccountPersistence.inMemoryAccountPersistence());
        var localGetBalance = provision(factory, getBalanceConfig, Fn1.<AccountId>id()).intercept(localService::getBalance);
        var localCredit = provision(factory, creditConfig, AccountMutation::accountId)
                .intercept((AccountMutation mutation) -> localService.credit(mutation.accountId(), mutation.amount()));

        var account = openUsdAccount(localService);

        apply(localCredit, new AccountMutation(account.id(), usd("100.00")));
        assertBalance(localGetBalance, account.id(), "100.00");

        apply(localCredit, new AccountMutation(account.id(), usd("25.00")));

        // Invalidation hit "balance-b", getBalance's cache is "balance-a": still stale at 100.00,
        // even though persistence has already moved to 125.00.
        assertBalance(localGetBalance, account.id(), "100.00");
    }

    private static <T> CacheMethodInterceptor provision(CacheInterceptorFactory factory, CacheConfig config, Fn1<AccountId, T> keyExtractor) {
        var context = ProvisioningContext.provisioningContext().withKeyExtractor(keyExtractor);

        return factory.provision(config, context)
                      .await()
                      .onFailureRun(() -> fail("Expected interceptor provisioning to succeed"))
                      .unwrap();
    }

    private static <T> void apply(Fn1<Promise<T>, AccountMutation> fn, AccountMutation mutation) {
        fn.apply(mutation).await().onFailureRun(() -> fail("Expected success"));
    }

    private static void assertBalance(Fn1<Promise<Balance>, AccountId> fn, AccountId accountId, String expected) {
        fn.apply(accountId)
          .await()
          .onFailureRun(() -> fail("Expected success"))
          .onSuccess(balance -> assertThat(balance.available().amount()).isEqualByComparingTo(new BigDecimal(expected)));
    }

    private static ConfigService configServiceFromShippedResourcesToml() throws Exception {
        var resourceUrl = CacheInvalidationTest.class.getClassLoader().getResource("resources.toml");
        assertThat(resourceUrl).as("resources.toml must be on the test classpath").isNotNull();

        var provider = ConfigurationProvider.builder()
                                            .withTomlFile(Path.of(resourceUrl.toURI()))
                                            .build();

        return ProviderBasedConfigService.providerBasedConfigService(provider);
    }

    private static Account openUsdAccount(AccountService service) {
        return service.openAccount("Alice", "alice@example.com", Currency.USD).await().unwrap();
    }

    private static Money usd(String amount) {
        return Money.money(new BigDecimal(amount), Currency.USD).unwrap();
    }
}
