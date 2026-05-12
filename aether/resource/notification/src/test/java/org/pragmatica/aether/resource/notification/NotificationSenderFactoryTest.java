// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.resource.notification;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.ResourceFactory;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class NotificationSenderFactoryTest {

    @Test
    void spiDiscovery_findsNotificationSenderFactory() {
        var factories = ServiceLoader.load(ResourceFactory.class).stream().toList();

        assertThat(factories)
            .anyMatch(provider -> provider.type() == NotificationSenderFactory.class);
    }

    @Test
    void factory_returnsCorrectResourceType() {
        var factory = new NotificationSenderFactory();

        assertThat(factory.resourceType()).isEqualTo(NotificationSender.class);
        assertThat(factory.configType()).isEqualTo(NotificationConfig.class);
    }

    @Test
    void notificationConfig_defaultRetry() {
        var config = NotificationConfig.notificationConfig("smtp");

        assertThat(config.backend()).isEqualTo("smtp");
        assertThat(config.smtpConfig().isEmpty()).isTrue();
        assertThat(config.httpConfig().isEmpty()).isTrue();
        assertThat(config.effectiveRetryConfig()).isEqualTo(RetryConfig.DEFAULT);
    }

    @Test
    void notificationConfig_customRetry() {
        var retry = RetryConfig.retryConfig(5, 500, 10_000, 1.5);
        var config = NotificationConfig.notificationConfig("http").withRetryConfig(retry);

        assertThat(config.effectiveRetryConfig().maxAttempts()).isEqualTo(5);
        assertThat(config.effectiveRetryConfig().initialDelay().millis()).isEqualTo(500);
    }
}
