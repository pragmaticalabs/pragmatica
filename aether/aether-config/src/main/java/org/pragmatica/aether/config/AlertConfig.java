package org.pragmatica.aether.config;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


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
/// timeout = "5s"
///
/// [alerts.events]
/// enabled = true
/// ```
///
/// @param enabled Whether alerting is enabled
/// @param webhook Webhook configuration
/// @param events Event stream configuration
public record AlertConfig(boolean enabled, WebhookConfig webhook, EventConfig events) {
    private static final AlertConfig DEFAULT = alertConfig(true,
                                                           WebhookConfig.webhookConfig(),
                                                           EventConfig.eventConfig()).unwrap();

    public static Result<AlertConfig> alertConfig(boolean enabled, WebhookConfig webhook, EventConfig events) {
        return success(new AlertConfig(enabled, webhook, events));
    }

    public static AlertConfig alertConfig() {
        return DEFAULT;
    }

    public static AlertConfig alertConfig(List<String> urls) {
        return alertConfig(true,
                           WebhookConfig.webhookConfig(true, urls, 3, timeSpan(5).seconds()).unwrap(),
                           EventConfig.eventConfig(true).unwrap()).unwrap();
    }

    @SuppressWarnings("JBCT-ZONE-02") public record WebhookConfig(boolean enabled,
                                                                  List<String> urls,
                                                                  int retryCount,
                                                                  TimeSpan timeout) {
        private static final WebhookConfig DISABLED = webhookConfig(false, List.of(), 0, timeSpan(0).millis()).unwrap();

        public static Result<WebhookConfig> webhookConfig(boolean enabled,
                                                          List<String> urls,
                                                          int retryCount,
                                                          TimeSpan timeout) {
            return success(new WebhookConfig(enabled, List.copyOf(urls), retryCount, timeout));
        }

        public static WebhookConfig webhookConfig() {
            return DISABLED;
        }

        public Result<WebhookConfig> check() {
            return checkUrls().flatMap(WebhookConfig::checkRetryCount).flatMap(WebhookConfig::checkTimeout);
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
                  : AlertConfigError.InvalidAlertConfig.invalidAlertConfig("webhook.retry_count must be >= 0").result();
        }

        private Result<WebhookConfig> checkTimeout() {
            return ! enabled || timeout.millis() >= 100
                  ? success(this)
                  : AlertConfigError.InvalidAlertConfig.invalidAlertConfig("webhook.timeout must be >= 100ms").result();
        }
    }

    public record EventConfig(boolean enabled) {
        public static Result<EventConfig> eventConfig(boolean enabled) {
            return success(new EventConfig(enabled));
        }

        public static EventConfig eventConfig() {
            return eventConfig(false).unwrap();
        }
    }

    public sealed interface AlertConfigError extends Cause {
        record unused() implements AlertConfigError {
            @Override public String message() {
                return "unused";
            }
        }

        record InvalidAlertConfig(String detail) implements AlertConfigError {
            public static Result<InvalidAlertConfig> invalidAlertConfig(String detail, boolean validated) {
                return success(new InvalidAlertConfig(detail));
            }

            public static InvalidAlertConfig invalidAlertConfig(String detail) {
                return invalidAlertConfig(detail, true).unwrap();
            }

            @Override public String message() {
                return "Invalid alert configuration: " + detail;
            }
        }
    }
}
