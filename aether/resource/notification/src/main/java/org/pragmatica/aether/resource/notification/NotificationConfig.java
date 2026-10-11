// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import org.pragmatica.email.http.HttpEmailConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.net.smtp.SmtpConfig;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


public record NotificationConfig(String backend,
                                 Option<SmtpConfig> smtpConfig,
                                 Option<HttpEmailConfig> httpConfig,
                                 Option<RetryConfig> retryConfig) {
    public static NotificationConfig notificationConfig(String backend) {
        return new NotificationConfig(backend, none(), none(), none());
    }

    public static NotificationConfig smtpNotificationConfig(SmtpConfig smtpConfig) {
        return new NotificationConfig("smtp", some(smtpConfig), none(), none());
    }

    public static NotificationConfig httpNotificationConfig(HttpEmailConfig httpConfig) {
        return new NotificationConfig("http", none(), some(httpConfig), none());
    }

    public NotificationConfig withRetryConfig(RetryConfig retryConfig) {
        return new NotificationConfig(backend, smtpConfig, httpConfig, some(retryConfig));
    }

    public RetryConfig effectiveRetryConfig() {
        return retryConfig.or(RetryConfig.DEFAULT);
    }
}
