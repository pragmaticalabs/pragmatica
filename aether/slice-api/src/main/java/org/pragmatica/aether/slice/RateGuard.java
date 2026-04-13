package org.pragmatica.aether.slice;

import org.pragmatica.lang.Promise;

import java.util.function.Supplier;


/// Resource type for rate-limiting slice method invocations.
///
/// Provisioned via `@ResourceQualifier(type = RateGuard.class, config = "rate-limit.xxx")`.
/// Users create custom annotations per rate-limit policy:
///
/// ```java
/// @ResourceQualifier(type = RateGuard.class, config = "rate-limit.orders")
/// @Retention(RUNTIME) @Target(METHOD)
/// public @interface OrdersRateLimit {}
/// ```
///
/// Then annotate slice methods:
///
/// ```java
/// @OrdersRateLimit
/// Promise<OrderResult> processOrder(OrderRequest request);
/// ```
///
/// The runtime wraps annotated method invocations: if the rate limit is exceeded,
/// a `RateGuardError.LimitExceeded` is returned without invoking the method.
/// HTTP servers translate this to 429 Too Many Requests with appropriate headers.
///
/// Configuration in `aether.toml`:
/// ```toml
/// [rate-limit.orders]
/// requests_per_second = 100
/// burst = 20
/// type = "local"
/// ```
public interface RateGuard {
    /// Guard an operation. Returns the result if within limits,
    /// or RateGuardError.LimitExceeded if rate exceeded.
    <T> Promise<T> guard(Supplier<Promise<T>> operation);
}
