// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics.observability;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;


@SuppressWarnings("JBCT-RET-01") public final class HttpRequestObserver {
    private final ObservabilityRegistry registry;

    private HttpRequestObserver(ObservabilityRegistry registry) {
        this.registry = registry;
    }

    public static HttpRequestObserver httpRequestObserver(ObservabilityRegistry registry) {
        return new HttpRequestObserver(registry);
    }

    public void recordRequest(String method, String routePattern, String statusCategory, long durationNanos) {
        requestCounter(method, routePattern, statusCategory).increment();
        requestTimer(method, routePattern).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void recordSecurityDenial(String denialType, String method, String routePattern) {
        securityDenialCounter(denialType, method, routePattern).increment();
    }

    private Counter requestCounter(String method, String routePattern, String status) {
        return registry.counter("aether_http_requests_total", "method", method, "route", routePattern, "status", status);
    }

    private Timer requestTimer(String method, String routePattern) {
        return Timer.builder("aether_http_request_duration_seconds").tags("method", method, "route", routePattern)
                            .register(registry.registry());
    }

    private Counter securityDenialCounter(String type, String method, String routePattern) {
        return registry.counter("aether_security_denials_total", "type", type, "route", routePattern, "method", method);
    }
}
