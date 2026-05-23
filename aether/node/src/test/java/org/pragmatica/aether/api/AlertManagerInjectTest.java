// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterEventValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private static AlertManager.EventLogPublisher capturingPublisher(List<Map<String, String>> capturedMetadata,
                                                                       List<ClusterEventValue.EventType> capturedTypes) {
        return (type, severity, message, metadata) -> {
            capturedTypes.add(type);
            capturedMetadata.add(metadata);
            return Promise.success(Unit.unit());
        };
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
    class ClusterReplication {

        @Test
        void inject_publishesAlertInjectedEvent_whenPublisherBound() {
            var manager = newManager();
            var captured = new CopyOnWriteArrayList<Map<String, String>>();
            var capturedTypes = new CopyOnWriteArrayList<ClusterEventValue.EventType>();
            var publisher = capturingPublisher(captured, capturedTypes);
            manager.bindEventLogPublisher(publisher);

            manager.inject("replicated-alert",
                           "WARNING",
                           "must replicate via event log",
                           Option.option("test.metric"),
                           Option.option(99.0))
                   .onFailure(cause -> fail("Inject failed: " + cause.message()))
                   .await();

            assertEquals(1, captured.size(), "inject must publish exactly one cluster event");
            assertEquals(ClusterEventValue.EventType.ALERT_INJECTED, capturedTypes.get(0),
                          "Published event type must be ALERT_INJECTED");
            var metadata = captured.get(0);
            assertNotNull(metadata.get("alertId"), "metadata must carry alertId");
            assertTrue(metadata.get("alertId").startsWith("injected-"),
                       "metadata alertId must use 'injected-' prefix: " + metadata.get("alertId"));
            assertEquals("replicated-alert", metadata.get("name"));
            assertEquals("WARNING", metadata.get("severity"));
            assertEquals("must replicate via event log", metadata.get("message"));
            assertEquals("test.metric", metadata.get("metric"));
            assertNotNull(metadata.get("timestamp"), "metadata must carry timestamp");
        }

        @Test
        void activeAlertsAsList_includesClusterWideInjectedAlerts_andDedupsByAlertId() {
            var manager = newManager();
            var injectedAlertId = manager.inject("local-only",
                                                  "INFO",
                                                  "originator local entry",
                                                  Option.empty(),
                                                  Option.empty())
                                          .map(response -> response.alertId())
                                          .await()
                                          .or("");
            assertTrue(!injectedAlertId.isEmpty(), "Originator inject must produce an alertId");

            var peerEvent = ClusterEvent.clusterEvent(ClusterEventValue.EventType.ALERT_INJECTED,
                                                       ClusterEventValue.Severity.CRITICAL,
                                                       "peer-injected message",
                                                       Map.of("alertId", "injected-peer-1",
                                                              "name", "peer-alert",
                                                              "severity", "CRITICAL",
                                                              "message", "peer-injected message",
                                                              "timestamp", "1234567890"));
            // Echo back the originator's own alert too — verifies dedup by alertId.
            var echoEvent = ClusterEvent.clusterEvent(ClusterEventValue.EventType.ALERT_INJECTED,
                                                       ClusterEventValue.Severity.INFO,
                                                       "originator local entry",
                                                       Map.of("alertId", injectedAlertId,
                                                              "name", "local-only",
                                                              "severity", "INFO",
                                                              "message", "originator local entry",
                                                              "timestamp", "1234567891"));
            manager.bindClusterEventsSource(() -> List.of(peerEvent, echoEvent));

            var alerts = manager.activeAlertsAsList();
            var alertIds = alerts.stream().map(AlertManager.AlertView::alertId).toList();
            assertTrue(alertIds.contains(injectedAlertId),
                       "Originator's local alertId must remain visible: " + alertIds);
            assertTrue(alertIds.contains("injected-peer-1"),
                       "Peer-originated alertId must be unioned in: " + alertIds);
            assertEquals(2, alerts.size(),
                          "Echo of originator's alertId must dedup: " + alertIds);
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
