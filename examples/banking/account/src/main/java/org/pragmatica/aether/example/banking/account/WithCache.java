package org.pragmatica.aether.example.banking.account;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.interceptor.CacheMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Cache interceptor for slice methods.
///
/// Wraps method invocations with a caching layer. When a method is annotated
/// with `@WithCache`, its results are cached using the `@Key`-annotated parameter
/// as the cache key.
///
/// Configuration in resources.toml. `cache_name`, `strategy`, and `mode` are all required -
/// CacheConfig has no binder-visible default (#278), so an omitted field fails loud rather than
/// silently defaulting. `cache_name` must match [InvalidateBalanceOnCredit] / [InvalidateBalanceOnDebit]'s
/// own `cache_name`, or invalidation evicts a different cache than this one populates:
/// ```
/// [cache.account.getBalance]
/// cache_name = "account-balance"
/// strategy = "CACHE_ASIDE"
/// ttl_seconds = 300
/// max_entries = 10000
/// mode = "LOCAL"
/// ```
@ResourceQualifier(type = CacheMethodInterceptor.class, config = "cache.account.getBalance")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WithCache {}
