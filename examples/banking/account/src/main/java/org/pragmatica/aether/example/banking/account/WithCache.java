package org.pragmatica.aether.example.banking.account;

import org.pragmatica.aether.resource.interceptor.CacheMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Cache interceptor for slice methods.
///
/// Wraps method invocations with a caching layer. When a method is annotated
/// with `@WithCache`, its results are cached using the `@Key`-annotated parameter
/// as the cache key.
///
/// Configuration in aether.toml:
/// ```
/// [cache.account.getBalance]
/// ttl_seconds = 300
/// max_entries = 10000
/// ```
@ResourceQualifier(type = CacheMethodInterceptor.class, config = "cache.account.getBalance")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface WithCache {}
