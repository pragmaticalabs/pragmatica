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


public record RateGuardConfig(int requestsPerSecond, int burst, String type) {
    /// Per-field defaults, read by the config binder by name (`DEFAULT_<COMPONENT>`, #822) so a section may omit
    /// them, as the resource reference promises. Not a whole-record `DEFAULT`: see
    /// `InterceptorConfigDefaultAllowlistTest`.
    public static final int DEFAULT_REQUESTS_PER_SECOND = 100;
    public static final int DEFAULT_BURST = 20;
    public static final String DEFAULT_TYPE = "local";

    public static Result<RateGuardConfig> rateGuardConfig(int requestsPerSecond, int burst) {
        return rateGuardConfig(requestsPerSecond, burst, DEFAULT_TYPE);
    }

    public static Result<RateGuardConfig> rateGuardConfig(int requestsPerSecond, int burst, String type) {
        var validRate = ensure(requestsPerSecond, Verify.Is::positive);
        var validBurst = ensure(burst, Verify.Is::nonNegative);
        var validType = ensure(type, Verify.Is::notBlank);

        return all(validRate, validBurst, validType).map(RateGuardConfig::new);
    }

    public static Result<RateGuardConfig> rateGuardConfig() {
        return rateGuardConfig(DEFAULT_REQUESTS_PER_SECOND, DEFAULT_BURST);
    }

    public TimeSpan window() {
        return timeSpan(1).seconds();
    }
}
