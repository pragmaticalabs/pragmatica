package org.pragmatica.aether.resource.notification;

import org.pragmatica.email.http.HttpEmailConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.smtp.SmtpConfig;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// Configuration for the notification resource.
///
/// @param backend Backend type: "smtp" or "http"
/// @param smtpConfig SMTP configuration (required when backend is "smtp")
/// @param httpConfig HTTP vendor configuration (required when backend is "http")
/// @param retryConfig Optional retry configuration (defaults to 3 attempts with exponential backoff)
public record NotificationConfig(String backend,
                                 Option<SmtpConfig> smtpConfig,
                                 Option<HttpEmailConfig> httpConfig,
                                 Option<RetryConfig> retryConfig) {
    /// Create a notification config for the given backend with no backend-specific config.
    public static NotificationConfig notificationConfig(String backend) {
        return new NotificationConfig(backend, none(), none(), none());
    }

    /// Create a notification config for SMTP backend.
    public static NotificationConfig smtpNotificationConfig(SmtpConfig smtpConfig) {
        return new NotificationConfig("smtp", some(smtpConfig), none(), none());
    }

    /// Create a notification config for HTTP vendor backend.
    public static NotificationConfig httpNotificationConfig(HttpEmailConfig httpConfig) {
        return new NotificationConfig("http", none(), some(httpConfig), none());
    }

    /// Return a copy with the given retry configuration.
    public NotificationConfig withRetryConfig(RetryConfig retryConfig) {
        return new NotificationConfig(backend, smtpConfig, httpConfig, some(retryConfig));
    }

    /// Effective retry config — returns configured value or default.
    public RetryConfig effectiveRetryConfig() {
        return retryConfig.or(RetryConfig.DEFAULT);
    }
}
