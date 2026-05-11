// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Covers the synthetic alert injection endpoint surface on `AlertManager`:
///   - inject + read-back via `activeAlertsAsJson` (correlation by `alertId`)
///   - validation (blank name / message / invalid severity)
///   - inject also writes a history entry with status `INJECTED`
///
/// Uses the `readOnly` constructor to bypass `RabiaNode` wiring — injection is
/// node-local in-memory state, no cluster apply happens on the inject path, so
/// the absence of a consensus node does not affect coverage.
class AlertManagerInjectTest {

    @SuppressWarnings("unchecked")
    private static AlertManager newManager() {
        var kvStore = (KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class);
        return AlertManager.readOnly(kvStore);
    }

    @Nested
    class InjectionSuccess {

        @Test
        void inject_returnsStampedResponse_whenValid() {
            var manager = newManager();
            manager.inject("smoke-alert",
                           "WARNING",
                           "synthetic injection from unit test",
                           Option.option("test.metric"),
                           Option.option(42.0))
                   .onFailure(cause -> fail("Inject failed: " + cause.message()))
                   .onSuccess(response -> {
                       assertNotNull(response.alertId(), "alertId must be stamped");
                       assertTrue(response.alertId().startsWith("injected-"),
                                  "alertId must use 'injected-' prefix: " + response.alertId());
                       assertEquals("smoke-alert", response.name());
                       assertEquals("WARNING", response.severity());
                       assertEquals("synthetic injection from unit test", response.message());
                       assertTrue(response.timestamp() > 0, "timestamp must be stamped");
                   })
                   .await();
        }

        @Test
        void inject_entryVisibleInActiveAlertsJson_andHistoryJson() {
            var manager = newManager();
            var alertId = manager.inject("readback-alert",
                                         "CRITICAL",
                                         "must appear in active list",
                                         Option.empty(),
                                         Option.empty())
                                 .map(response -> response.alertId())
                                 .await()
                                 .or("");
            assertTrue(!alertId.isEmpty(), "Injection must produce a non-empty alertId");

            var activeJson = manager.activeAlertsAsJson();
            assertTrue(activeJson.contains(alertId),
                       "activeAlertsAsJson must surface injected alertId: actual=" + activeJson);
            assertTrue(activeJson.contains("\"name\":\"readback-alert\""),
                       "activeAlertsAsJson must surface injected name field: actual=" + activeJson);
            assertTrue(activeJson.contains("\"severity\":\"CRITICAL\""),
                       "activeAlertsAsJson must surface injected severity field: actual=" + activeJson);
            assertTrue(activeJson.contains("\"source\":\"injected\""),
                       "activeAlertsAsJson must mark synthetic entries with source=injected: actual=" + activeJson);

            var historyJson = manager.alertHistoryAsJson();
            assertTrue(historyJson.contains("\"status\":\"INJECTED\""),
                       "alertHistoryAsJson must record an INJECTED history entry: actual=" + historyJson);
        }
    }

    @Nested
    class InjectionValidation {

        @Test
        void inject_fails_whenNameBlank() {
            newManager().inject("",
                                "WARNING",
                                "must reject blank name",
                                Option.empty(),
                                Option.empty())
                        .onSuccess(_ -> fail("Blank name must be rejected"))
                        .onFailure(cause -> Assertions.assertTrue(cause.message().toLowerCase().contains("name"),
                                                                   "Failure message must mention name: " + cause.message()))
                        .await();
        }

        @Test
        void inject_fails_whenSeverityInvalid() {
            newManager().inject("bad-severity",
                                "FATAL",
                                "must reject non-canonical severity",
                                Option.empty(),
                                Option.empty())
                        .onSuccess(_ -> fail("Invalid severity must be rejected"))
                        .onFailure(cause -> Assertions.assertTrue(cause.message().toLowerCase().contains("severity"),
                                                                   "Failure message must mention severity: " + cause.message()))
                        .await();
        }

        @Test
        void inject_fails_whenMessageBlank() {
            newManager().inject("blank-message",
                                "INFO",
                                "   ",
                                Option.empty(),
                                Option.empty())
                        .onSuccess(_ -> fail("Blank message must be rejected"))
                        .onFailure(cause -> Assertions.assertTrue(cause.message().toLowerCase().contains("message"),
                                                                   "Failure message must mention message: " + cause.message()))
                        .await();
        }
    }
}
