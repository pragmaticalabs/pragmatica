package org.pragmatica.aether.api;

import org.pragmatica.aether.config.AlertConfig;
import org.pragmatica.aether.config.AlertConfig.WebhookConfig;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private final HttpClient httpClient;
    private final ExecutorService executor;
    private final boolean enabled;

    private AlertForwarder(AlertConfig alertConfig) {
        this.config = alertConfig.webhook();
        this.enabled = alertConfig.enabled() && config.enabled();
        if (enabled && !config.urls()
                              .isEmpty()) {
            this.executor = Executors.newVirtualThreadPerTaskExecutor();
            this.httpClient = HttpClient.newBuilder()
                                        .connectTimeout(Duration.ofMillis(config.timeoutMs()))
                                        .executor(executor)
                                        .build();
            log.info("AlertForwarder initialized with {} webhook URLs",
                     config.urls()
                           .size());
        } else {
            this.executor = null;
            this.httpClient = null;
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
        if (!enabled || httpClient == null) {
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
        forward(alert)
               .onFailure(cause -> log.error("Failed to forward slice failure alert: {}",
                                             cause.message()));
    }

    /**
     * Handle threshold alert event via MessageRouter.
     */
    @MessageReceiver
    public void onThresholdAlert(AlertEvent.ThresholdAlert alert) {
        forward(alert)
               .onFailure(cause -> log.error("Failed to forward threshold alert: {}",
                                             cause.message()));
    }

    private Promise<Unit> sendToWebhook(String url, String payload) {
        return sendWithRetry(url, payload, 0);
    }

    private Promise<Unit> sendWithRetry(String url, String payload, int attempt) {
        return Promise.promise(promise -> doSend(url, payload, attempt, promise));
    }

    private void doSend(String url, String payload, int attempt, Promise<Unit> promise) {
        try{
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create(url))
                                     .timeout(Duration.ofMillis(config.timeoutMs()))
                                     .header("Content-Type", "application/json")
                                     .POST(HttpRequest.BodyPublishers.ofString(payload))
                                     .build();
            httpClient.sendAsync(request,
                                 HttpResponse.BodyHandlers.ofString())
                      .thenAccept(response -> handleResponse(url, payload, attempt, promise, response))
                      .exceptionally(e -> {
                          handleError(url, payload, attempt, promise, e);
                          return null;
                      });
        } catch (Exception e) {
            handleError(url, payload, attempt, promise, e);
        }
    }

    private void handleResponse(String url,
                                String payload,
                                int attempt,
                                Promise<Unit> promise,
                                HttpResponse<String> response) {
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.debug("Alert forwarded successfully to {}", url);
            promise.succeed(Unit.unit());
        } else {
            log.warn("Webhook {} returned status {}", url, response.statusCode());
            retryOrFail(url, payload, attempt, promise, "HTTP " + response.statusCode());
        }
    }

    private void handleError(String url, String payload, int attempt, Promise<Unit> promise, Throwable e) {
        log.warn("Error sending to webhook {}: {}", url, e.getMessage());
        retryOrFail(url, payload, attempt, promise, e.getMessage());
    }

    private void retryOrFail(String url, String payload, int attempt, Promise<Unit> promise, String error) {
        if (attempt < config.retryCount()) {
            log.debug("Retrying webhook {} (attempt {}/{})", url, attempt + 1, config.retryCount());
            doSend(url, payload, attempt + 1, promise);
        } else {
            log.error("Failed to send to webhook {} after {} attempts: {}", url, config.retryCount(), error);
            promise.fail(AlertForwarderError.WebhookError.webhookError(url, error));
        }
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
            case AlertEvent.SliceFailureAlert sfa -> {
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
                boolean first = true;
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
            case AlertEvent.ThresholdAlert ta -> {
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
            case AlertEvent.AlertResolved ar -> {
                sb.append("\"type\":\"ALERT_RESOLVED\",");
                sb.append("\"resolvedBy\":\"")
                  .append(escapeJson(ar.resolvedBy()))
                  .append("\"");
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * Shutdown the forwarder.
     */
    public void shutdown() {
        if (executor != null) {
            executor.shutdown();
            log.info("AlertForwarder shutdown");
        }
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
