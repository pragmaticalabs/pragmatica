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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Assertions;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/// Covers the synthetic alert injection endpoint surface on `AlertManager`, ported to the
/// stream-namespaces cluster-events model (Stage 4):
///   - inject + read-back via `activeAlertsAsJson` (correlation by `alertId`)
///   - inject emits a sealed `ClusterEvent.AlertInjected` through the bound `EventSink`
///     (was: an `EventLogPublisher.publish(EventType, ...)` call against the deleted KV log)
///   - cross-node UNION read via the async `clusterEventsSource` (Supplier<Promise<List>>)
///   - validation (blank name / message / invalid severity)
///
/// Uses the `readOnly` constructor to bypass `RabiaNode` wiring — injection is node-local
/// in-memory state, no cluster apply happens on the inject path.
class AlertManagerInjectTest {

    private static final HlcClock HLC = HlcClock.hlcClock(new NodeId("test-node"));

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
    class ClusterReplication {

        @Test
        void inject_emitsAlertInjectedEvent_whenSinkBound() {
            var manager = newManager();
            var captured = new CopyOnWriteArrayList<ClusterEvent>();
            manager.bindEventSink(captured::add, HLC);

            manager.inject("replicated-alert",
                           "WARNING",
                           "must replicate via event stream",
                           Option.option("test.metric"),
                           Option.option(99.0))
                   .onFailure(cause -> fail("Inject failed: " + cause.message()))
                   .await();

            assertEquals(1, captured.size(), "inject must emit exactly one cluster event");
            var event = captured.get(0);
            assertTrue(event instanceof ClusterEvent.AlertInjected,
                       "Emitted event must be an AlertInjected variant: " + event.getClass().getSimpleName());
            var metadata = event.details();
            assertNotNull(metadata.get("alertId"), "metadata must carry alertId");
            assertTrue(metadata.get("alertId").startsWith("injected-"),
                       "metadata alertId must use 'injected-' prefix: " + metadata.get("alertId"));
            assertEquals("replicated-alert", metadata.get("name"));
            assertEquals("WARNING", metadata.get("severity"));
            assertEquals("must replicate via event stream", metadata.get("message"));
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

            var peerEvent = new ClusterEvent.AlertInjected(HLC.now(),
                                                           ClusterEvent.Severity.CRITICAL,
                                                           "peer-injected message",
                                                           Map.of("alertId", "injected-peer-1",
                                                                  "name", "peer-alert",
                                                                  "severity", "CRITICAL",
                                                                  "message", "peer-injected message",
                                                                  "timestamp", "1234567890"));
            // Echo back the originator's own alert too — verifies dedup by alertId.
            var echoEvent = new ClusterEvent.AlertInjected(HLC.now(),
                                                           ClusterEvent.Severity.INFO,
                                                           "originator local entry",
                                                           Map.of("alertId", injectedAlertId,
                                                                  "name", "local-only",
                                                                  "severity", "INFO",
                                                                  "message", "originator local entry",
                                                                  "timestamp", "1234567891"));
            manager.bindClusterEventsSource(() -> Promise.success(List.of(peerEvent, echoEvent)));

            var alerts = manager.activeAlertsAsList().await().or(List.of());
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
