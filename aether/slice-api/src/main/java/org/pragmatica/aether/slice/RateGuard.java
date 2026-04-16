// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
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
    <T> Promise<T> guard(Supplier<Promise<T>> operation);
}
