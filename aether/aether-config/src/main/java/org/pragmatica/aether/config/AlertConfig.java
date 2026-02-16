package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;

import java.util.List;

import static org.pragmatica.lang.Result.success;

/// Configuration for alert forwarding.
///
///
/// Example aether.toml:
/// ```
/// [alerts]
/// enabled = true
///
/// [alerts.webhook]
/// enabled = true
/// urls = ["https://pagerduty.example.com/webhook", "https://slack.example.com/webhook"]
/// retry_count = 3
/// timeout_ms = 5000
///
/// [alerts.events]
/// enabled = true
/// ```
///
/// @param enabled Whether alerting is enabled
/// @param webhook Webhook configuration
/// @param events Event stream configuration
public record AlertConfig(boolean enabled,
                          WebhookConfig webhook,
                          EventConfig events) {
    private static final AlertConfig DEFAULT = alertConfig(true,
                                                           WebhookConfig.webhookConfig(),
                                                           EventConfig.eventConfig()).unwrap();

    /// Factory method following JBCT naming convention.
    public static Result<AlertConfig> alertConfig(boolean enabled,
                                                  WebhookConfig webhook,
                                                  EventConfig events) {
        return success(new AlertConfig(enabled, webhook, events));
    }

    /// Default configuration with alerting enabled but no webhooks configured.
    public static AlertConfig alertConfig() {
        return DEFAULT;
    }

    /// Create AlertConfig with webhook URLs.
    public static AlertConfig alertConfig(List<String> urls) {
        return alertConfig(true,
                           WebhookConfig.webhookConfig(true, urls, 3, 5000)
                                        .unwrap(),
                           EventConfig.eventConfig(true)
                                      .unwrap()).unwrap();
    }

    /// Configuration for webhook-based alert forwarding.
    ///
    /// @param enabled Whether webhooks are enabled
    /// @param urls List of webhook URLs to call
    /// @param retryCount Number of retries for failed webhook calls
    /// @param timeoutMs Timeout for webhook calls in milliseconds
    @SuppressWarnings("JBCT-ZONE-02")
    public record WebhookConfig(boolean enabled,
                                List<String> urls,
                                int retryCount,
                                int timeoutMs) {
        private static final WebhookConfig DISABLED = webhookConfig(false, List.of(), 0, 0).unwrap();

        /// Factory method following JBCT naming convention.
        public static Result<WebhookConfig> webhookConfig(boolean enabled,
                                                          List<String> urls,
                                                          int retryCount,
                                                          int timeoutMs) {
            return success(new WebhookConfig(enabled, List.copyOf(urls), retryCount, timeoutMs));
        }

        /// Disabled webhook configuration.
        public static WebhookConfig webhookConfig() {
            return DISABLED;
        }

        /// Check webhook configuration.
        public Result<WebhookConfig> check() {
            return checkUrls().flatMap(WebhookConfig::checkRetryCount)
                            .flatMap(WebhookConfig::checkTimeoutMs);
        }

        private Result<WebhookConfig> checkUrls() {
            return ! enabled || hasUrls()
                   ? success(this)
                   : AlertConfigError.InvalidAlertConfig.invalidAlertConfig("webhook.urls cannot be empty when enabled")
                                     .result();
        }

        private boolean hasUrls() {
            return urls.size() > 0;
        }

        private Result<WebhookConfig> checkRetryCount() {
            return retryCount >= 0
                   ? success(this)
                   : AlertConfigError.InvalidAlertConfig.invalidAlertConfig("webhook.retry_count must be >= 0")
                                     .result();
        }

        private Result<WebhookConfig> checkTimeoutMs() {
            return ! enabled || timeoutMs >= 100
                   ? success(this)
                   : AlertConfigError.InvalidAlertConfig.invalidAlertConfig("webhook.timeout_ms must be >= 100")
                                     .result();
        }
    }

    /// Configuration for internal event stream alerts.
    ///
    /// @param enabled Whether event stream is enabled
    public record EventConfig(boolean enabled) {
        /// Factory method following JBCT naming convention.
        public static Result<EventConfig> eventConfig(boolean enabled) {
            return success(new EventConfig(enabled));
        }

        /// Disabled event configuration.
        public static EventConfig eventConfig() {
            return eventConfig(false).unwrap();
        }
    }

    /// Error hierarchy for alert configuration failures.
    public sealed interface AlertConfigError extends Cause {
        record unused() implements AlertConfigError {
            @Override
            public String message() {
                return "unused";
            }
        }

        /// Configuration error for AlertConfig.
        record InvalidAlertConfig(String detail) implements AlertConfigError {
            /// Factory method following JBCT naming convention.
            public static Result<InvalidAlertConfig> invalidAlertConfig(String detail, boolean validated) {
                return success(new InvalidAlertConfig(detail));
            }

            /// Convenience factory method.
            public static InvalidAlertConfig invalidAlertConfig(String detail) {
                return invalidAlertConfig(detail, true).unwrap();
            }

            @Override
            public String message() {
                return "Invalid alert configuration: " + detail;
            }
        }
    }
}
