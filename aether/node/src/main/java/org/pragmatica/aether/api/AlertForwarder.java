// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

import org.pragmatica.aether.config.AlertConfig;
import org.pragmatica.aether.config.AlertConfig.WebhookConfig;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.JitterUtil;
import org.pragmatica.lang.utils.OwnerLifetime;
import org.pragmatica.messaging.MessageReceiver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Implements [AsyncCloseable] because, when webhooks are enabled, it owns a JDK `HttpClient` whose selector
/// thread otherwise outlives the node (#1097's class).
///
/// Retries are scheduled on an [OwnerLifetime] that [#close()] ends (#3754), so a retry armed before the close never
/// runs after it. A send already in flight at the close finishes without a WARN, a retry or a further POST: its
/// continuation re-reads the lifetime (the second check, class N: this node is the only sender of its webhooks and an
/// alert POST after close is harmless).
///
/// A `forward` never dangles across a close: a retry the close cancelled has its promise resolved as
/// [AlertForwarderError.Stopped] by [#close()], which `forward` reports as a failure naming the stop. A delivery given up
/// after `retry_count` still resolves `forward` successfully (#3536).
@SuppressWarnings("JBCT-RET-01")
public class AlertForwarder implements AsyncCloseable {
    private static final Logger log = LoggerFactory.getLogger(AlertForwarder.class);
    private static final long RETRY_BASE_MS = 200L;
    private static final long RETRY_CAP_MS = 30_000L;

    private final WebhookConfig config;
    private final Option<HttpOperations> httpOps;
    private final boolean enabled;
    private final OwnerLifetime lifetime;
    /// The promise of every retry armed and not yet resolved, with its webhook URL: what [#close()] resolves as stopped.
    private final Map<Promise<Unit>, String> pendingRetries = new ConcurrentHashMap<>();

    private AlertForwarder(AlertConfig alertConfig, OwnerLifetime lifetime, Option<HttpOperations> httpOps) {
        this.config = alertConfig.webhook();
        this.enabled = alertConfig.enabled() && config.enabled();
        this.lifetime = lifetime;
        this.httpOps = httpOps;
        if (enabled && !config.urls().isEmpty()) {
            log.info("AlertForwarder initialized with {} webhook URLs",
                     config.urls().size());
        } else {
            log.info("AlertForwarder disabled");
        }
    }

    public static AlertForwarder alertForwarder(AlertConfig config) {
        return alertForwarder(config, OwnerLifetime.ownerLifetime());
    }

    /// The forwarder with its retries scheduled on `lifetime`; a test passes a lifetime over a fake seam.
    static AlertForwarder alertForwarder(AlertConfig config, OwnerLifetime lifetime) {
        return alertForwarder(config, lifetime, webhookClient(config));
    }

    /// The forwarder with its retries on `lifetime` and its sends through `httpOps` (empty: nothing is sent).
    static AlertForwarder alertForwarder(AlertConfig config, OwnerLifetime lifetime, Option<HttpOperations> httpOps) {
        return new AlertForwarder(config, lifetime, httpOps);
    }

    private static Option<HttpOperations> webhookClient(AlertConfig alertConfig) {
        var webhook = alertConfig.webhook();

        return alertConfig.enabled() && webhook.enabled() && !webhook.urls()
                                                                     .isEmpty()
               ? Option.some(JdkHttpOperations.jdkHttpOperations(Duration.ofMillis(webhook.timeout().millis()),
                                                                 HttpClient.Redirect.NORMAL,
                                                                 Option.none()))
               : Option.none();
    }

    /// Ends the retry lifetime, resolves every retry it cancelled as stopped, then releases the webhook client, when there is
    /// one. Idempotent: the lifetime's end, the resolve and the client's shutdown are.
    @Override
    public Promise<Unit> close() {
        lifetime.end();
        abandonPendingRetries();

        return httpOps.filter(AsyncCloseable.class::isInstance)
                      .map(AsyncCloseable.class::cast)
                      .map(AsyncCloseable::close)
                      .or(Promise.unitPromise());
    }

    public Promise<Unit> forward(AlertEvent event) {
        if (!enabled || httpOps.isEmpty()) {
            return Promise.success(Unit.unit());
        }

        var payload = toJson(event);

        log.debug("Forwarding alert to {} webhooks: {}",
                  config.urls().size(),
                  event.alertId());

        return Promise.allOf(config.urls().stream().map(url -> sendToWebhook(url, payload)).toList()).flatMap(AlertForwarder::stoppedOrDone);
    }

    private static Promise<Unit> stoppedOrDone(List<Result<Unit>> results) {
        return results.stream()
                      .flatMap(result -> result.fold(AlertForwarder::stoppedCause,
                                                     _ -> Stream.<Cause> empty()))
                      .findFirst()
                      .map(Cause::<Unit> promise)
                      .orElseGet(Promise::unitPromise);
    }

    private static Stream<Cause> stoppedCause(Cause cause) {
        return cause instanceof AlertForwarderError.Stopped
               ? Stream.of(cause)
               : Stream.empty();
    }

    @MessageReceiver
    public void onSliceFailureAlert(AlertEvent.SliceFailureAlert alert) {
        forward(alert).onFailure(cause -> log.error("Failed to forward slice failure alert: {}", cause.message()));
    }

    @MessageReceiver
    public void onThresholdAlert(AlertEvent.ThresholdAlert alert) {
        forward(alert).onFailure(cause -> log.error("Failed to forward threshold alert: {}", cause.message()));
    }

    private Promise<Unit> sendToWebhook(String url, String payload) {
        return httpOps.fold(() -> Promise.success(Unit.unit()),
                            ops -> sendWithRetry(ops, url, payload, 0));
    }

    private Promise<Unit> sendWithRetry(HttpOperations ops, String url, String payload, int attempt) {
        return doSend(ops, url, payload).fold(result -> settle(ops, url, payload, attempt, result));
    }

    /// The second check (#3754, class N): a send that was in flight when [#close()] ended the lifetime ends here, before any
    /// WARN or retry, instead of reporting on a forwarder that has stopped.
    private Promise<Unit> settle(HttpOperations ops, String url, String payload, int attempt, Result<Integer> result) {
        return lifetime.isEnded()
               ? AlertForwarderError.Stopped.stopped(url).promise()
               : result.fold(cause -> handleTransportFailure(ops, url, payload, attempt, cause),
                             statusCode -> handleStatusCode(ops, url, payload, attempt, statusCode));
    }

    /// A send that failed before any status was read (connection refused or reset, DNS failure, timeout) is retried
    /// under the same `retry_count` and backoff as a retryable status (#3536): a receiver that is restarting is
    /// the case retry exists for.
    private Promise<Unit> handleTransportFailure(HttpOperations ops,
                                                 String url,
                                                 String payload,
                                                 int attempt,
                                                 Cause cause) {
        log.warn("Webhook {} unreachable: {}", url, cause.message());

        return retryOrFail(ops, url, payload, attempt, cause.message());
    }

    private Promise<Integer> doSend(HttpOperations ops, String url, String payload) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(url))
                                 .timeout(Duration.ofMillis(config.timeout().millis()))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(payload))
                                 .build();

        return ops.sendString(request)
                  .map(org.pragmatica.http.HttpResult::statusCode);
    }

    private Promise<Unit> handleStatusCode(HttpOperations ops,
                                           String url,
                                           String payload,
                                           int attempt,
                                           int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            log.debug("Alert forwarded to {}", url);

            return Promise.success(Unit.unit());
        }

        log.warn("Webhook {} returned status {}", url, statusCode);

        return retryOrFail(ops, url, payload, attempt, "HTTP " + statusCode);
    }

    private Promise<Unit> retryOrFail(HttpOperations ops, String url, String payload, int attempt, String error) {
        if (attempt < config.retryCount()) {
            var delayMs = jitteredBackoff(attempt);

            log.debug("Retrying webhook {} (attempt {}/{}) after {}ms", url, attempt + 1, config.retryCount(), delayMs);
            var promise = Promise.<Unit> promise();

            pendingRetries.put(promise, url);
            promise.onResult(_ -> pendingRetries.remove(promise));
            lifetime.schedule(_ -> sendWithRetry(ops, url, payload, attempt + 1).onResult(promise::resolve),
                              TimeSpan.timeSpan(delayMs).millis());
            // A close between the first read of the flag and the put above swept an empty map: this re-read abandons the
            // retry it missed.
            if (lifetime.isEnded()) {
                abandonPendingRetries();
            }

            return promise;
        }

        log.error("Failed to send to webhook {} after {} attempts: {}", url, config.retryCount(), error);

        return AlertForwarderError.WebhookError.webhookError(url, error).promise();
    }

    private void abandonPendingRetries() {
        pendingRetries.forEach((promise, url) -> promise.resolve(AlertForwarderError.Stopped.stopped(url).result()));
    }

    static long jitteredBackoff(int attempt) {
        var base = Math.min(RETRY_BASE_MS * (1L << attempt), RETRY_CAP_MS);

        return JitterUtil.applyJitter(base, JitterUtil.MIN_FACTOR_DEFAULT, JitterUtil.MAX_FACTOR_DEFAULT);
    }

    private String toJson(AlertEvent event) {
        var sb = new StringBuilder();

        sb.append("{");
        sb.append("\"alertId\":\"").append(event.alertId()).append("\",");
        sb.append("\"timestamp\":").append(event.timestamp()).append(",");
        sb.append("\"severity\":\"").append(event.severity()).append("\",");
        switch (event) {
            case AlertEvent.SliceFailureAlert sfa -> appendSliceFailureFields(sb, sfa);
            case AlertEvent.ThresholdAlert ta -> appendThresholdFields(sb, ta);
            case AlertEvent.AlertResolved ar -> appendResolvedFields(sb, ar);
            case AlertEvent.NodeHealthAlert nha -> appendNodeHealthFields(sb, nha);
        }

        sb.append("}");

        return sb.toString();
    }

    private void appendSliceFailureFields(StringBuilder sb, AlertEvent.SliceFailureAlert sfa) {
        sb.append("\"type\":\"SLICE_ALL_INSTANCES_FAILED\",");
        sb.append("\"artifact\":\"").append(sfa.artifact().asString()).append("\",");
        sb.append("\"method\":\"").append(sfa.method().name()).append("\",");
        sb.append("\"requestId\":\"").append(sfa.requestId()).append("\",");
        sb.append("\"attemptedNodes\":[");
        var first = true;

        for (var nodeId : sfa.attemptedNodes()) {
            if (!first) sb.append(",");

            sb.append("\"").append(nodeId.id()).append("\"");
            first = false;
        }

        sb.append("],");
        sb.append("\"lastError\":\"").append(escapeJson(sfa.lastError())).append("\"");
    }

    /// #926: node-health alerts forward to the configured webhooks like every other alert kind. This is
    /// the one alerting surface that leaves the cluster entirely, so it is the surface least affected by
    /// the failure being reported — a webhook POST needs no leader, no quorum and no healthy peer.
    private void appendNodeHealthFields(StringBuilder sb, AlertEvent.NodeHealthAlert nha) {
        sb.append("\"type\":\"NODE_FAILED\",");
        sb.append("\"nodeId\":\"").append(nha.nodeId().id()).append("\",");
        sb.append("\"observedBy\":\"").append(nha.observedBy().id()).append("\",");
        sb.append("\"reason\":\"").append(escapeJson(nha.reason())).append("\"");
    }

    private void appendThresholdFields(StringBuilder sb, AlertEvent.ThresholdAlert ta) {
        sb.append("\"type\":\"THRESHOLD_EXCEEDED\",");
        sb.append("\"metric\":\"").append(ta.metric()).append("\",");
        sb.append("\"nodeId\":\"").append(ta.nodeId().id()).append("\",");
        sb.append("\"value\":").append(ta.value()).append(",");
        sb.append("\"threshold\":").append(ta.threshold());
    }

    private void appendResolvedFields(StringBuilder sb, AlertEvent.AlertResolved ar) {
        sb.append("\"type\":\"ALERT_RESOLVED\",");
        sb.append("\"resolvedBy\":\"").append(escapeJson(ar.resolvedBy())).append("\"");
    }

    private String escapeJson(String s) {
        return Option.option(s)
                     .map(str -> str.replace("\\", "\\\\")
                                    .replace("\"", "\\\"")
                                    .replace("\n", "\\n")
                                    .replace("\r", "\\r")
                                    .replace("\t", "\\t"))
                     .or("");
    }

    public void shutdown() {
        log.info("AlertForwarder shutdown");
    }

    public sealed interface AlertForwarderError extends Cause {
        /// The forwarder was closed while this send was in flight.
        record Stopped(String url) implements AlertForwarderError {
            public static Stopped stopped(String url) {
                return new Stopped(url);
            }

            @Override
            public String message() {
                return "Webhook " + url + " not sent: forwarder closed";
            }
        }

        record WebhookError(String url, String error) implements AlertForwarderError {
            public static WebhookError webhookError(String url, String error) {
                return new WebhookError(url, error);
            }

            @Override
            public String message() {
                return "Webhook " + url + " failed: " + error;
            }
        }
    }
}
