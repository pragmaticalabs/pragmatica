package org.pragmatica.aether.resource.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/// Marks a parameter or record component as the cache key for method interceptors.
///
/// When a slice method has interceptors (e.g., caching), the `@Key` annotation
/// identifies which parameter value should be used as the cache key.
///
/// For single-parameter methods, the parameter itself is the key.
/// For multi-parameter methods, exactly one parameter must be annotated with `@Key`.
///
/// Example with single-parameter method:
/// ```{@code
/// @WithCache
/// Promise<Balance> getBalance(@Key AccountId accountId);
/// }```
///
/// Example with multi-parameter method:
/// ```{@code
/// Promise<String> placeOrder(@Key String orderId, int quantity);
/// }```
///
/// Only one `@Key` annotation is allowed per method or per request record.
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
public @interface Key {}
