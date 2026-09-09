// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.Result.success;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


public record AlertConfig(boolean enabled, WebhookConfig webhook, EventConfig events, double hysteresisMargin) {
    /// Default hysteresis margin (#969, #957): a breached threshold clears only once the metric falls
    /// to `max(threshold * (1 - margin), warningThreshold)`.
    ///
    /// **RELATIVE, not absolute, and that is forced by the API rather than chosen.** `POST
    /// /api/v1/thresholds` accepts an arbitrary metric name and a bare `double`, so a threshold may be
    /// a 0..1 ratio, a latency in millis, or a size in bytes. An absolute margin is meaningful for the
    /// first and absurd for the others; only a relative one is unit-agnostic.
    ///
    /// **The CLAMP, not this number, is what prevents a ladder inversion.** Without
    /// `max(..., warningThreshold)` a CRITICAL alert could clear beneath its own WARNING threshold and
    /// immediately re-raise as WARNING — manufacturing the flapping the margin exists to damp. With the
    /// clamp that is impossible for any operator configuration, which is what makes 5% a safe DEFAULT
    /// rather than a load-bearing constant: it is a conventional damping value, not a derived one
    /// (#969 was filed precisely because no derivation exists), and it is operator-overridable.
    public static final double DEFAULT_HYSTERESIS_MARGIN = 0.05;

    private static final AlertConfig DEFAULT = alertConfig(true,
                                                           WebhookConfig.webhookConfig(),
                                                           EventConfig.eventConfig(),
                                                           DEFAULT_HYSTERESIS_MARGIN).unwrap();

    public static Result<AlertConfig> alertConfig(boolean enabled,
                                                  WebhookConfig webhook,
                                                  EventConfig events,
                                                  double hysteresisMargin) {
        return success(new AlertConfig(enabled, webhook, events, hysteresisMargin));
    }

    public static AlertConfig alertConfig() {
        return DEFAULT;
    }

    public static AlertConfig alertConfig(List<String> urls) {
        return alertConfig(true,
                           WebhookConfig.webhookConfig(true, urls, 3, timeSpan(5).seconds()).unwrap(),
                           EventConfig.eventConfig(true).unwrap(),
                           DEFAULT_HYSTERESIS_MARGIN).unwrap();
    }

    /// Whole-config validation, called at node boot by `AetherNode`.
    ///
    /// **This composes [WebhookConfig#check], which before #957 was fully written and invoked by
    /// nothing** — a fifth dead surface in this subsystem, alongside the four the #957 investigation
    /// found. It is what delivers that ticket's fail-closed clause: a configured webhook that cannot
    /// fire refuses at boot rather than accepting and dropping.
    public Result<AlertConfig> check() {
        return webhook.check()
                      .flatMap(_ -> checkHysteresisMargin());
    }

    /// A margin of 0 disables damping (clear point == breach point, the pre-#969 behaviour) and is
    /// legal. 1 or above would drive the clear point to zero or negative, so a breach could never
    /// clear on its own — refused rather than clamped, because silently correcting an operator's
    /// number would hide a configuration mistake behind working-looking behaviour.
    private Result<AlertConfig> checkHysteresisMargin() {
        return hysteresisMargin >= 0.0 && hysteresisMargin < 1.0
               ? success(this)
               : AlertConfigError.InvalidAlertConfig.invalidAlertConfig("alerts.hysteresis_margin must be in [0.0, 1.0), got " + hysteresisMargin).result();
    }

    @SuppressWarnings("JBCT-ZONE-02")
    public record WebhookConfig(boolean enabled, List<String> urls, int retryCount, TimeSpan timeout) {
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
            return checkUrls().flatMap(WebhookConfig::checkRetryCount)
                            .flatMap(WebhookConfig::checkTimeout);
        }

        private Result<WebhookConfig> checkUrls() {
            return ! enabled || hasUrls()
                   ? success(this)
                   : AlertConfigError.InvalidAlertConfig.invalidAlertConfig("webhook.urls cannot be empty when enabled").result();
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
            @Override
            public String message() {
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

            @Override
            public String message() {
                return "Invalid alert configuration: " + detail;
            }
        }
    }
}
