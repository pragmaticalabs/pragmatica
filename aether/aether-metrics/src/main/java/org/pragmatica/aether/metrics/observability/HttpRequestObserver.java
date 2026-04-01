package org.pragmatica.aether.metrics.observability;

import java.util.concurrent.TimeUnit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;

/// Records per-route HTTP request metrics using Micrometer.
///
/// Tracks:
///   - `aether_http_requests_total` counter with method, path, status tags
///   - `aether_http_request_duration_seconds` timer with method, path tags
///   - `aether_security_denials_total` counter with type, method, path tags
@SuppressWarnings("JBCT-RET-01") // Fire-and-forget metric recording — no result needed
public final class HttpRequestObserver {
    private final ObservabilityRegistry registry;

    private HttpRequestObserver(ObservabilityRegistry registry) {
        this.registry = registry;
    }

    public static HttpRequestObserver httpRequestObserver(ObservabilityRegistry registry) {
        return new HttpRequestObserver(registry);
    }

    /// Record a completed HTTP request with its duration and status category.
    /// The routePattern should be the template path (e.g., "/api/v1/users/{id}"), not the actual URI,
    /// to avoid cardinality explosion in metrics.
    public void recordRequest(String method, String routePattern, String statusCategory, long durationNanos) {
        requestCounter(method, routePattern, statusCategory).increment();
        requestTimer(method, routePattern).record(durationNanos, TimeUnit.NANOSECONDS);
    }

    /// Record a security denial event.
    /// The routePattern should be the template path, not the actual URI.
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
