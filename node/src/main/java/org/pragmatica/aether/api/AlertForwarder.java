package org.pragmatica.aether.api;

import org.pragmatica.aether.config.AlertConfig;
import org.pragmatica.aether.config.AlertConfig.WebhookConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageReceiver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Forwards alerts to external webhook endpoints.
 *
 * <p>Supports:
 * <ul>
 *   <li>Multiple webhook URLs</li>
 *   <li>Configurable retries</li>
 *   <li>Configurable timeout</li>
 * </ul>
 */
public class AlertForwarder {
    private static final Logger log = LoggerFactory.getLogger(AlertForwarder.class);

    private final WebhookConfig config;
    private final Option<HttpClient> httpClient;
    private final boolean enabled;

    private AlertForwarder(AlertConfig alertConfig) {
        this.config = alertConfig.webhook();
        this.enabled = alertConfig.enabled() && config.enabled();
        if (enabled && !config.urls()
                              .isEmpty()) {
            this.httpClient = Option.some(HttpClient.newBuilder()
                                                    .connectTimeout(Duration.ofMillis(config.timeoutMs()))
                                                    .build());
            log.info("AlertForwarder initialized with {} webhook URLs",
                     config.urls()
                           .size());
        } else {
            this.httpClient = Option.none();
            log.info("AlertForwarder disabled");
        }
    }

    /**
     * Factory method following JBCT naming convention.
     */
    public static AlertForwarder alertForwarder(AlertConfig config) {
        return new AlertForwarder(config);
    }

    /**
     * Forward an alert to all configured webhooks.
     */
    public Promise<Unit> forward(AlertEvent event) {
        if (!enabled || httpClient.isEmpty()) {
            return Promise.success(Unit.unit());
        }
        var payload = toJson(event);
        log.debug("Forwarding alert to {} webhooks: {}",
                  config.urls()
                        .size(),
                  event.alertId());
        return Promise.allOf(config.urls()
                                   .stream()
                                   .map(url -> sendToWebhook(url, payload))
                                   .toList())
                      .map(_ -> Unit.unit());
    }

    /**
     * Handle slice failure alert event via MessageRouter.
     */
    @MessageReceiver
    public void onSliceFailureAlert(AlertEvent.SliceFailureAlert alert) {
        forward(alert).onFailure(cause -> log.error("Failed to forward slice failure alert: {}", cause.message()));
    }

    /**
     * Handle threshold alert event via MessageRouter.
     */
    @MessageReceiver
    public void onThresholdAlert(AlertEvent.ThresholdAlert alert) {
        forward(alert).onFailure(cause -> log.error("Failed to forward threshold alert: {}", cause.message()));
    }

    private Promise<Unit> sendToWebhook(String url, String payload) {
        return httpClient.fold(() -> Promise.success(Unit.unit()),
                               client -> sendWithRetry(client, url, payload, 0));
    }

    private Promise<Unit> sendWithRetry(HttpClient client, String url, String payload, int attempt) {
        return doSend(client, url, payload)
        .flatMap(statusCode -> handleStatusCode(client, url, payload, attempt, statusCode));
    }

    private Promise<Integer> doSend(HttpClient client, String url, String payload) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create(url))
                                 .timeout(Duration.ofMillis(config.timeoutMs()))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(payload))
                                 .build();
        return Result.lift(Causes::fromThrowable,
                           () -> client.send(request,
                                             HttpResponse.BodyHandlers.ofString()))
                     .map(HttpResponse::statusCode)
                     .async();
    }

    private Promise<Unit> handleStatusCode(HttpClient client, String url, String payload, int attempt, int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            log.debug("Alert forwarded successfully to {}", url);
            return Promise.success(Unit.unit());
        }
        log.warn("Webhook {} returned status {}", url, statusCode);
        return retryOrFail(client, url, payload, attempt, "HTTP " + statusCode);
    }

    private Promise<Unit> retryOrFail(HttpClient client, String url, String payload, int attempt, String error) {
        if (attempt < config.retryCount()) {
            log.debug("Retrying webhook {} (attempt {}/{})", url, attempt + 1, config.retryCount());
            return sendWithRetry(client, url, payload, attempt + 1);
        }
        log.error("Failed to send to webhook {} after {} attempts: {}", url, config.retryCount(), error);
        return AlertForwarderError.WebhookError.webhookError(url, error)
                                  .promise();
    }

    private String toJson(AlertEvent event) {
        var sb = new StringBuilder();
        sb.append("{");
        sb.append("\"alertId\":\"")
          .append(event.alertId())
          .append("\",");
        sb.append("\"timestamp\":")
          .append(event.timestamp())
          .append(",");
        sb.append("\"severity\":\"")
          .append(event.severity())
          .append("\",");
        switch (event) {
            case AlertEvent.SliceFailureAlert sfa -> appendSliceFailureFields(sb, sfa);
            case AlertEvent.ThresholdAlert ta -> appendThresholdFields(sb, ta);
            case AlertEvent.AlertResolved ar -> appendResolvedFields(sb, ar);
        }
        sb.append("}");
        return sb.toString();
    }

    private void appendSliceFailureFields(StringBuilder sb, AlertEvent.SliceFailureAlert sfa) {
        sb.append("\"type\":\"SLICE_ALL_INSTANCES_FAILED\",");
        sb.append("\"artifact\":\"")
          .append(sfa.artifact()
                     .asString())
          .append("\",");
        sb.append("\"method\":\"")
          .append(sfa.method()
                     .name())
          .append("\",");
        sb.append("\"requestId\":\"")
          .append(sfa.requestId())
          .append("\",");
        sb.append("\"attemptedNodes\":[");
        var first = true;
        for (var nodeId : sfa.attemptedNodes()) {
            if (!first) sb.append(",");
            sb.append("\"")
              .append(nodeId.id())
              .append("\"");
            first = false;
        }
        sb.append("],");
        sb.append("\"lastError\":\"")
          .append(escapeJson(sfa.lastError()))
          .append("\"");
    }

    private void appendThresholdFields(StringBuilder sb, AlertEvent.ThresholdAlert ta) {
        sb.append("\"type\":\"THRESHOLD_EXCEEDED\",");
        sb.append("\"metric\":\"")
          .append(ta.metric())
          .append("\",");
        sb.append("\"nodeId\":\"")
          .append(ta.nodeId()
                    .id())
          .append("\",");
        sb.append("\"value\":")
          .append(ta.value())
          .append(",");
        sb.append("\"threshold\":")
          .append(ta.threshold());
    }

    private void appendResolvedFields(StringBuilder sb, AlertEvent.AlertResolved ar) {
        sb.append("\"type\":\"ALERT_RESOLVED\",");
        sb.append("\"resolvedBy\":\"")
          .append(escapeJson(ar.resolvedBy()))
          .append("\"");
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

    /**
     * Shutdown the forwarder.
     */
    public void shutdown() {
        log.info("AlertForwarder shutdown");
    }

    /**
     * Error hierarchy for AlertForwarder failures.
     */
    public sealed interface AlertForwarderError extends Cause {
        /**
         * Error for webhook failures.
         */
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
