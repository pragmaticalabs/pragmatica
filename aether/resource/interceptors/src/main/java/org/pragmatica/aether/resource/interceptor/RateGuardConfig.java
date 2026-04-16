// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Verify.ensure;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Configuration for RateGuard resource.
///
/// Parsed from TOML config sections like:
/// ```toml
/// [rate-limit.orders]
/// requests_per_second = 100
/// burst = 20
/// type = "local"
/// ```
///
/// @param requestsPerSecond Base rate in requests per second
/// @param burst             Additional burst capacity above the base rate
/// @param type              Rate limiter type: "local" (per-node) or "distributed" (future, via DHT)
public record RateGuardConfig(int requestsPerSecond, int burst, String type) {
    public static Result<RateGuardConfig> rateGuardConfig(int requestsPerSecond, int burst) {
        return rateGuardConfig(requestsPerSecond, burst, "local");
    }

    public static Result<RateGuardConfig> rateGuardConfig(int requestsPerSecond, int burst, String type) {
        var validRate = ensure(requestsPerSecond, Verify.Is::positive);
        var validBurst = ensure(burst, Verify.Is::nonNegative);
        var validType = ensure(type, Verify.Is::notBlank);
        return all(validRate, validBurst, validType).map(RateGuardConfig::new);
    }

    public static Result<RateGuardConfig> rateGuardConfig() {
        return rateGuardConfig(100, 20);
    }

    public TimeSpan window() {
        return timeSpan(1).seconds();
    }
}
