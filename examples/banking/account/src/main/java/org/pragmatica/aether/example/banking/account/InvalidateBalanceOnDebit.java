package org.pragmatica.aether.example.banking.account;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.interceptor.CacheMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;


/// Invalidates the balance cache when a debit operation succeeds.
/// Uses WRITE_AROUND strategy: call method, then remove stale cached balance.
/// `cache.account.debit`'s `cache_name` MUST match [WithCache]'s `cache.account.getBalance` one -
/// they must resolve to the same shared cache namespace, or this evicts nothing this class reads (#278).
@ResourceQualifier(type = CacheMethodInterceptor.class, config = "cache.account.debit")
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface InvalidateBalanceOnDebit {}
