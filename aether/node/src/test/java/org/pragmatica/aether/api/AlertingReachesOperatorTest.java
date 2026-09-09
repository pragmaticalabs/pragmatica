// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.pragmatica.aether.config.AlertConfig;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AlertThresholdKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AlertThresholdValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Promise;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.sun.net.httpserver.HttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;


/// Pins the three independent breaks that together made alerting structurally dead in production
/// (row 39 of the cluster-checkability census, plus #957). Each break alone is invisible: fixing any
/// one of them changes no observable behaviour, which is why each had its own investigation and none
/// produced a working alert.
///
///   1. **Evaluation was conditioned on a UI client.** `checkThreshold`'s only production caller,
///      `DashboardMetricsPublisher.checkAndBroadcastAlerts`, sat AFTER an
///      `if (connectedClients() == 0) return;`. On a headless cluster no threshold was ever
///      evaluated. Measured on a live 3-node rc4 cluster: thresholds set far below live values
///      produced zero alerts in 210s, while injected alerts appeared immediately.
///   2. **The event was never constructed.** `AlertEvent.ThresholdAlert` was declared,
///      pattern-matched by `AlertForwarder`'s renderer, and received by its `@MessageReceiver` --
///      and built NOWHERE in `src/main`. A renderer handling a variant is not something emitting one.
///   3. **The forwarder was never constructed** (#957), so nothing left the process by any path.
///
/// The tests below are written so that reverting any single production hunk reddens a NAMED test:
/// hunk 1 reddens [#thresholdIsEvaluatedWithNoDashboardClientConnected], hunks 2 and 3 redden
/// [#raisedThresholdAlertReachesARealWebhook].
class AlertingReachesOperatorTest {
    private static final NodeId NODE = new NodeId("node-1");

    // Mockito's Answer contract for the void-returning KVStore.forEach requires returning null;
    // this method itself always returns AlertManager.readOnly(kvStore).
    @NullReturn
    @SuppressWarnings("unchecked")
    private static AlertManager managerWithThreshold(String metric, double warning, double critical) {
        var kvStore = (KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class);

        Mockito.doAnswer(invocation -> {
                             BiConsumer<AlertThresholdKey, AlertThresholdValue> consumer = invocation.getArgument(2);

                             consumer.accept(new AlertThresholdKey(metric),
                                             AlertThresholdValue.alertThresholdValue(metric, warning, critical));

                             return null;
                         })
               .when(kvStore)
               .forEach(eq(AlertThresholdKey.class), eq(AlertThresholdValue.class), any());

        return AlertManager.readOnly(kvStore);
    }

    private static ManageableNode nodeReporting(String metric, double value) {
        var collector = Mockito.mock(ClusterSyncCollector.class);
        var node = Mockito.mock(ManageableNode.class);

        Mockito.when(collector.allMetrics()).thenReturn(Map.of(NODE, Map.of(metric, value)));
        Mockito.when(node.metricsCollector()).thenReturn(collector);

        return node;
    }

    /// HUNK 1. No WebSocket client is connected in a unit test -- `connectedClients()` is 0, which is
    /// exactly the production state in which evaluation used to be skipped. A raised alert here can
    /// therefore only mean evaluation ran without a dashboard client.
    ///
    /// Reverting the hunk (moving `checkAndBroadcastAlerts()` back below the guard) makes this fail.
    @Test
    void thresholdIsEvaluatedWithNoDashboardClientConnected() {
        var alertManager = managerWithThreshold("cpu.usage", 0.7, 0.9);
        var node = nodeReporting("cpu.usage", 0.95);
        var publisher = DashboardMetricsPublisher.dashboardMetricsPublisher(() -> node, alertManager);

        assertThat(DashboardWebSocketHandler.connectedClients())
                .describedAs("precondition: the guard's condition must actually hold, or this test proves nothing")
                .isZero();
        assertThat(alertManager.activeAlertCount()).isZero();

        publisher.publishMetrics();

        assertThat(alertManager.activeAlertCount())
                .describedAs("a metric above its critical threshold must raise an alert even with no dashboard client")
                .isEqualTo(1);
    }

    /// Control for the test above: with the SAME publisher and the SAME zero connected clients, a
    /// value BELOW the threshold raises nothing. Without this, `activeAlertCount() == 1` above could
    /// be satisfied by an implementation that raises unconditionally, which is the mirror-image
    /// defect and would pass the first assertion.
    @Test
    void valueBelowThresholdRaisesNothingUnderTheSameConditions() {
        var alertManager = managerWithThreshold("cpu.usage", 0.7, 0.9);
        var node = nodeReporting("cpu.usage", 0.10);
        var publisher = DashboardMetricsPublisher.dashboardMetricsPublisher(() -> node, alertManager);

        publisher.publishMetrics();

        assertThat(alertManager.activeAlertCount()).isZero();
    }

    /// A sustained breach must raise ONCE, not once per evaluation tick. `publishMetrics` runs every
    /// second on every node (`DEFAULT_BROADCAST_INTERVAL_MS = 1000`, started unconditionally from
    /// `ManagementServer.onServerStarted`), so a level-triggered implementation would append an
    /// `alertHistory` entry every second on any node above a default threshold and overwrite the
    /// 100-entry deque in under two minutes. `shouldTrigger` is severity-transition-gated, which is
    /// what bounds it — this pins that, because it is the property that keeps the fix from turning
    /// into a slow degradation of `/api/alerts` on exactly the busy nodes that need it.
    @Test
    void aSustainedBreachRaisesOnceNotOncePerEvaluation() {
        var alertManager = managerWithThreshold("cpu.usage", 0.7, 0.9);
        var node = nodeReporting("cpu.usage", 0.95);
        var publisher = DashboardMetricsPublisher.dashboardMetricsPublisher(() -> node, alertManager);
        var forwarder = Mockito.mock(AlertForwarder.class);

        Mockito.when(forwarder.forward(any())).thenReturn(Promise.success(Unit.unit()));
        alertManager.bindAlertForwarder(forwarder);

        for (var tick = 0; tick < 20; tick++) {
            publisher.publishMetrics();
        }

        // NOTE: `activeAlertCount()` is deliberately NOT asserted here. `activeAlerts` is keyed
        // `metric + ":" + nodeId` and `handleAlertValue` uses `put`, which REPLACES -- so the count is
        // 1 under level-triggering too, and an "is 1, not 20" assertion cannot fail. It reads as a
        // check and discriminates nothing. Both assertions below DO differ under level-triggering.
        assertThat(alertManager.alertHistoryAsList())
                .describedAs("20 evaluations of ONE sustained breach must append ONE history entry, not one "
                             + "per tick -- the 100-entry deque would otherwise be overwritten in ~100s by a "
                             + "single hot node")
                .hasSize(1);
        Mockito.verify(forwarder, Mockito.times(1))
               .forward(any());
    }

    /// HUNKS 2 and 3, proven at the OUTERMOST OBSERVABLE that #957 asks for: a real HTTP request
    /// arriving at a real webhook, not a renderer unit test. Crossing a threshold must produce an
    /// `AlertEvent.ThresholdAlert` AND hand it to a bound `AlertForwarder` that actually sends it.
    ///
    /// Reverting hunk 2 (the `forwardThresholdAlert` call, or the `bindAlertForwarder` field) means
    /// no request ever arrives and this fails on the latch timeout.
    @Test
    void raisedThresholdAlertReachesARealWebhook() throws IOException, InterruptedException {
        var received = new AtomicReference<String>();
        var arrived = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/hook", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            arrived.countDown();
        });
        server.start();

        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            var alertManager = managerWithThreshold("cpu.usage", 0.7, 0.9);

            // the SAME production expression AetherNode calls -- not a re-typed fixture
            alertManager.withAlertForwarder(AlertConfig.alertConfig(List.of(url)));

            alertManager.checkThreshold("cpu.usage", NODE, 0.95);

            assertThat(arrived.await(10, TimeUnit.SECONDS))
                    .describedAs("a threshold crossing must reach a configured webhook -- this is the hop #957 found absent")
                    .isTrue();
            assertThat(received.get())
                    .contains("cpu.usage")
                    .contains("node-1")
                    .contains("CRITICAL");
        } finally {
            server.stop(0);
        }
    }

    /// Control for the webhook test: an alert that does NOT cross the threshold sends nothing, so the
    /// arrival above is attributable to the threshold crossing rather than to the forwarder emitting
    /// on every evaluation.
    @Test
    void subThresholdValueSendsNothingToTheWebhook() throws IOException, InterruptedException {
        var arrived = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        server.createContext("/hook", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            arrived.countDown();
        });
        server.start();

        try {
            var url = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook";
            var alertManager = managerWithThreshold("cpu.usage", 0.7, 0.9);

            alertManager.withAlertForwarder(AlertConfig.alertConfig(List.of(url)));

            alertManager.checkThreshold("cpu.usage", NODE, 0.10);

            assertThat(arrived.await(2, TimeUnit.SECONDS))
                    .describedAs("no threshold crossing means no webhook delivery")
                    .isFalse();
        } finally {
            server.stop(0);
        }
    }
}
