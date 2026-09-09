// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #957 / #969 — `AlertConfig` validation, and the boot gate that finally calls it.
///
/// **`WebhookConfig.check()` existed before this ticket and was invoked by NOTHING.** A repo-wide grep
/// for `check()` returned seven hits, of which the only alert-related one was the declaration itself.
/// It is a complete validator — url, retry-count and timeout guards, each with a typed cause — that no
/// code path reached, which is why #957 could describe a "configured webhook that cannot fire" as an
/// open problem while the code to refuse one was already written.
///
/// [AlertConfig#check] now composes it, and `Main.resolveAlertConfig` calls that at boot, so an invalid
/// section aborts startup instead of producing a node that accepts alerts and silently drops them.
class AlertConfigTest {

    @Nested
    class HysteresisMargin {

        @Test
        void defaultConfig_carriesTheShippedMarginAndPasses() {
            assertThat(AlertConfig.alertConfig().hysteresisMargin()).isEqualTo(AlertConfig.DEFAULT_HYSTERESIS_MARGIN);
            assertThat(AlertConfig.alertConfig().check().isSuccess()).isTrue();
        }

        /// Zero is legal and meaningful: it disables damping, restoring the pre-#969 behaviour where an
        /// alert clears the moment it falls back under its threshold.
        @Test
        void zeroMargin_isAccepted() {
            assertThat(withMargin(0.0).check().isSuccess()).isTrue();
        }

        /// A margin of 1.0 drives the clear point to zero, so a breach could never clear on its own. It
        /// is REFUSED rather than clamped: silently correcting an operator's number would hide the
        /// mistake behind behaviour that looks like it works.
        @Test
        void marginOfOneOrAbove_isRefused() {
            assertThat(withMargin(1.0).check().isFailure()).isTrue();
            assertThat(withMargin(1.5).check().isFailure()).isTrue();
        }

        @Test
        void negativeMargin_isRefused() {
            assertThat(withMargin(-0.01).check().isFailure()).isTrue();
        }

        /// The failure must name the field and the offending value — a boot abort that says only
        /// "invalid alert configuration" sends an operator reading source code.
        @Test
        void refusal_namesTheFieldAndTheValue() {
            withMargin(2.0).check()
                           .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("2.0 must be refused"))
                           .onFailure(cause -> assertThat(cause.message()).contains("hysteresis_margin")
                                                                          .contains("2.0"));
        }

        private static AlertConfig withMargin(double margin) {
            return AlertConfig.alertConfig(true,
                                           AlertConfig.WebhookConfig.webhookConfig(),
                                           AlertConfig.EventConfig.eventConfig(),
                                           margin)
                              .unwrap();
        }
    }

    @Nested
    class WebhookFailClosed {

        /// #957's fail-closed clause: a webhook turned on with nowhere to send refuses at boot rather
        /// than accepting alerts and dropping them. This is the case the pre-existing, uncalled
        /// validator was written for.
        @Test
        void webhookEnabledWithNoUrls_isRefused() {
            var config = AlertConfig.alertConfig(true,
                                                 AlertConfig.WebhookConfig.webhookConfig(true,
                                                                                         List.of(),
                                                                                         3,
                                                                                         timeSpan(5).seconds())
                                                                          .unwrap(),
                                                 AlertConfig.EventConfig.eventConfig(),
                                                 AlertConfig.DEFAULT_HYSTERESIS_MARGIN)
                                    .unwrap();

            assertThat(config.check().isFailure()).isTrue();
        }

        /// The control. Same shape, DISABLED — the shipped default — must pass, or the gate would
        /// refuse to boot every node that has no `[alerts.webhook]` section at all.
        @Test
        void webhookDisabledWithNoUrls_isAccepted() {
            assertThat(AlertConfig.alertConfig().check().isSuccess()).isTrue();
        }

        @Test
        void webhookEnabledWithUrls_isAccepted() {
            assertThat(AlertConfig.alertConfig(List.of("https://example.invalid/hook")).check().isSuccess()).isTrue();
        }

        @Test
        void negativeRetryCount_isRefused() {
            var config = AlertConfig.alertConfig(true,
                                                 AlertConfig.WebhookConfig.webhookConfig(true,
                                                                                         List.of("https://example.invalid/hook"),
                                                                                         -1,
                                                                                         timeSpan(5).seconds())
                                                                          .unwrap(),
                                                 AlertConfig.EventConfig.eventConfig(),
                                                 AlertConfig.DEFAULT_HYSTERESIS_MARGIN)
                                    .unwrap();

            assertThat(config.check().isFailure()).isTrue();
        }
    }
}
