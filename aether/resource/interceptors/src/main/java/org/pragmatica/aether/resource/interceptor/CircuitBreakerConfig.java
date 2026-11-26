// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.interceptor;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.all;
import static org.pragmatica.lang.Verify.ensure;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// One `[circuit-breaker.<name>]` section. SCOPE: the breaker is per node and per provision. Each node hosting the
/// slice keeps its own failure count and state, so `failure_threshold` is a per-node threshold: a cluster of N nodes
/// hosting the slice trips after up to `failure_threshold x N` failures cluster-wide, and nodes open and close
/// independently.
///
/// METERS (#280 R27), registered when the node has a metrics registry and the slice is attributed, tagged `slice` and
/// `section` (a second section of the same slice has its own series; nothing is tagged per method, since one breaker
/// serves every method naming the section):
/// - `aether.interceptor.circuit_breaker.state` gauge: 0 closed, 1 open, 2 half-open, read live from the breaker.
/// - `aether.interceptor.circuit_breaker.transitions` counter tagged `from`, `to`: every state change, counted by the
///   breaker's own listener at the moment it makes the change (a change on the scheduler thread, open to half-open,
///   included); no change is missed, merged or invented.
/// - `aether.interceptor.circuit_breaker.rejected` counter: calls refused while open; the method did not run.
///
/// The meters are removed when the slice unloads.
public record CircuitBreakerConfig(int failureThreshold, TimeSpan resetTimeout, int testAttempts) {
    // Tunable, not identity-bearing - safe to expose for TOML-config binder defaulting (#278).
    public static final CircuitBreakerConfig DEFAULT = new CircuitBreakerConfig(5, timeSpan(30).seconds(), 3);

    public static CircuitBreakerConfig circuitBreakerConfig() {
        return DEFAULT;
    }

    public static Result<CircuitBreakerConfig> circuitBreakerConfig(int failureThreshold) {
        return ensure(failureThreshold, Verify.Is::positive).map(threshold -> new CircuitBreakerConfig(threshold,
                                                                                                       timeSpan(30).seconds(),
                                                                                                       3));
    }

    public static Result<CircuitBreakerConfig> circuitBreakerConfig(int failureThreshold,
                                                                    TimeSpan resetTimeout,
                                                                    int testAttempts) {
        var validThreshold = ensure(failureThreshold, Verify.Is::positive);
        var validTimeout = ensure(resetTimeout, Verify.Is::notNull);
        var validAttempts = ensure(testAttempts, Verify.Is::positive);

        return all(validThreshold, validTimeout, validAttempts).map(CircuitBreakerConfig::new);
    }
}
