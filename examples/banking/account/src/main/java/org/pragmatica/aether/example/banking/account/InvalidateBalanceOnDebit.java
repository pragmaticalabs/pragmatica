package org.pragmatica.aether.example.banking.account;

import org.pragmatica.aether.resource.interceptor.CacheMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Invalidates the balance cache when a debit operation succeeds.
/// Uses WRITE_AROUND strategy: call method, then remove stale cached balance.
@ResourceQualifier(type = CacheMethodInterceptor.class, config = "cache.account.debit")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InvalidateBalanceOnDebit {}
