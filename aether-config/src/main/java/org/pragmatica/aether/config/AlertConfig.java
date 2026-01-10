package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.List;

/**
 * Configuration for alert forwarding.
 *
 * <p>Example aether.toml:
 * <pre>
 * [alerts]
 * enabled = true
 *
 * [alerts.webhook]
 * enabled = true
 * urls = ["https://pagerduty.example.com/webhook", "https://slack.example.com/webhook"]
 * retry_count = 3
 * timeout_ms = 5000
 *
 * [alerts.events]
 * enabled = true
 * </pre>
 *
 * @param enabled Whether alerting is enabled
 * @param webhook Webhook configuration
 * @param events Event stream configuration
 */
public record AlertConfig(boolean enabled,
                          WebhookConfig webhook,
                          EventConfig events) {
    private static final AlertConfig DEFAULT = new AlertConfig(true, WebhookConfig.disabled(), EventConfig.disabled());

    /**
     * Default configuration with alerting enabled but no webhooks configured.
     */
    public static AlertConfig defaults() {
        return DEFAULT;
    }

    /**
     * Create AlertConfig with webhook URLs.
     */
    public static AlertConfig withWebhooks(List<String> urls) {
        return new AlertConfig(true, WebhookConfig.webhookConfig(true, urls, 3, 5000), EventConfig.eventConfig(true));
    }

    /**
     * Configuration for webhook-based alert forwarding.
     *
     * @param enabled Whether webhooks are enabled
     * @param urls List of webhook URLs to call
     * @param retryCount Number of retries for failed webhook calls
     * @param timeoutMs Timeout for webhook calls in milliseconds
     */
    public record WebhookConfig(boolean enabled,
                                List<String> urls,
                                int retryCount,
                                int timeoutMs) {
        public static WebhookConfig disabled() {
            return new WebhookConfig(false, List.of(), 0, 0);
        }

        public static WebhookConfig webhookConfig(boolean enabled,
                                                  List<String> urls,
                                                  int retryCount,
                                                  int timeoutMs) {
            return new WebhookConfig(enabled, List.copyOf(urls), retryCount, timeoutMs);
        }

        /**
         * Validate webhook configuration.
         */
        public Result<WebhookConfig> validate() {
            if (!enabled) {
                return Result.success(this);
            }
            if (urls == null || urls.isEmpty()) {
                return AlertConfigError.InvalidAlertConfig.invalidConfig("webhook.urls cannot be empty when enabled")
                                       .result();
            }
            if (retryCount < 0) {
                return AlertConfigError.InvalidAlertConfig.invalidConfig("webhook.retry_count must be >= 0")
                                       .result();
            }
            if (timeoutMs < 100) {
                return AlertConfigError.InvalidAlertConfig.invalidConfig("webhook.timeout_ms must be >= 100")
                                       .result();
            }
            return Result.success(this);
        }
    }

    /**
     * Configuration for internal event stream alerts.
     *
     * @param enabled Whether event stream is enabled
     */
    public record EventConfig(boolean enabled) {
        public static EventConfig disabled() {
            return new EventConfig(false);
        }

        public static EventConfig eventConfig(boolean enabled) {
            return new EventConfig(enabled);
        }
    }

    /**
     * Error hierarchy for alert configuration failures.
     */
    public sealed interface AlertConfigError extends Cause {
        /**
         * Configuration error for AlertConfig.
         */
        record InvalidAlertConfig(String detail) implements AlertConfigError {
            public static InvalidAlertConfig invalidConfig(String detail) {
                return new InvalidAlertConfig(detail);
            }

            @Override
            public String message() {
                return "Invalid alert configuration: " + detail;
            }
        }
    }
}
