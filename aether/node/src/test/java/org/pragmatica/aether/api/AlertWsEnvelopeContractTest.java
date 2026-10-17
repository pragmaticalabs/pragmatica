// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AlertThresholdKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AlertThresholdValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.NullReturn;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;


/// Pins the wire contract behind #292 (live alerts never render): the WS envelope
/// `AlertManager.buildAlertMessage` produces is `{"type":"ALERT","timestamp":…,"data":{…}}` — the
/// discriminator lives ONLY at the top level, never duplicated inside `data`
/// (`DashboardMetricsPublisher.checkAndBroadcastAlerts` broadcasts `checkThreshold`'s return value
/// verbatim, so this IS the wire message, not a stand-in for it).
///
/// The pre-fix client bug was two-sided and both sides are pinned here as JS-source-text
/// assertions, since neither `app.js` nor `alerts.js` has a Java-side seam to exercise directly:
///   - `app.js` unwrapped the envelope before dispatch (`data.data || data`), so the handler that
///     reads `type` never saw it.
///   - `alerts.js` then read `data.type === 'ALERT'` on that already-unwrapped payload, which never
///     carries a `type` field — so the check was always false and nothing rendered.
///
/// `ALERT_RESOLVED` (`AlertManager.broadcastAlertResolved`) is a private, directly-broadcasting
/// method with no return-value seam — its envelope shape is confirmed by source inspection only
/// (`[design intent — unverified]` in the changelog fragment), not executed here.
class AlertWsEnvelopeContractTest {
    private static final JsonMapper MAPPER = JsonMapper.defaultJsonMapper();

    // The `return null` below is Mockito's own `Answer` contract for a void-returning stubbed
    // method (`KVStore.forEach`), not a null this test's code chooses to return —
    // `managerWithThreshold` itself always returns `AlertManager.readOnly(kvStore)`.
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
               .forEach(eq(AlertThresholdKey.class),
                        eq(AlertThresholdValue.class),
                        any());

        return AlertManager.readOnly(kvStore);
    }

    private static Result<String> resource(String path) {
        try (InputStream in = AlertWsEnvelopeContractTest.class.getResourceAsStream(path)) {
            if (in == null) {
                return Result.failure(Causes.cause("Dashboard resource not found on classpath: " + path));
            }

            return Result.success(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Result.failure(Causes.fromThrowable(e));
        }
    }

    @Test
    void checkThreshold_valueAboveCritical_returnsEnvelopeWithTopLevelTypeAndUnwrappedData() {
        var manager = managerWithThreshold("cpu.usage", 0.7, 0.9);
        var json = manager.checkThreshold("cpu.usage", new NodeId("node-1"), 0.95).or("");

        assertThat(json).as("a value above the critical threshold must produce an alert message").isNotEmpty();
        var parsed = MAPPER.readTree(json);

        assertThat(parsed.isSuccess()).as("alert message must be valid JSON").isTrue();
        parsed.onSuccess(tree -> {
            assertThat(tree.has("type")).as("discriminator must live at the top level")
                      .isTrue();
            assertThat(tree.get("type").asString()).isEqualTo("ALERT");
            assertThat(tree.has("data")).isTrue();
            var data = tree.get("data");

            assertThat(data.has("type")).as("'type' must never be duplicated inside 'data'")
                      .isFalse();
            assertThat(data.get("metric").asString()).isEqualTo("cpu.usage");
            assertThat(data.get("nodeId").asString()).isEqualTo("node-1");
            assertThat(data.get("severity").asString()).isEqualTo("CRITICAL");
        });
    }

    @Test
    void checkThreshold_valueBelowWarning_returnsEmpty() {
        var manager = managerWithThreshold("cpu.usage", 0.7, 0.9);
        var json = manager.checkThreshold("cpu.usage", new NodeId("node-1"), 0.1).or("");

        assertThat(json).as("a value below every threshold must trigger no alert").isEmpty();
    }

    /// #292 fix, half 1: `onWsMessage` must forward the WHOLE envelope to the alerts store, not the
    /// unwrapped `data.data || data` payload — the store is what needs to read `type`.
    @Test
    void appJs_dispatchesWholeEnvelopeToAlertsStore_notUnwrappedPayload() {
        var appJs = resource("/dashboard/js/app.js").unwrap();

        assertThat(appJs).as("app.js must pass the whole ALERT/ALERT_RESOLVED envelope to the alerts store")
                  .contains("Alpine.store('alerts').updateFromWs(data);")
                  .as("the old unwrap-before-dispatch must be gone")
                  .doesNotContain("Alpine.store('alerts').updateFromWs(data.data || data);");
    }

    /// #292 fix, "alerts included in the poll fallback" (CTO scope ruling): the alerts store must be
    /// refreshed from the same gated REPEATING poll timer that drives events/status, not left WS-only.
    ///
    /// Scoped to `startPolling()`'s own body, not the whole file: `loadInitialData()` already calls
    /// `Alpine.store('alerts').refresh()` exactly once, on page load — that pre-existing call would
    /// make a whole-file `contains` check pass vacuously whether or not the repeating timer was ever
    /// touched, and self-healing after a missed/dropped WS message is exactly what a one-time
    /// initial-load call cannot provide.
    @Test
    void appJs_startPolling_refreshesAlertsStoreOnTheRepeatingTimer_notOnlyOnce() {
        var appJs = resource("/dashboard/js/app.js").unwrap();
        var start = appJs.indexOf("startPolling() {");
        var end = appJs.indexOf("async pollStatus() {", start);

        assertThat(start).as("startPolling() must exist in app.js").isNotNegative();
        assertThat(end).as("pollStatus() must follow startPolling() so its body is boundable").isGreaterThan(start);
        var startPollingBody = appJs.substring(start, end);

        assertThat(startPollingBody).as("alerts must be refreshed from the REPEATING poll timer inside startPolling(), not only "
                                       + "once from loadInitialData()")
                  .contains("Alpine.store('alerts').refresh()");
    }

    /// #292 fix, half 2: `alerts.js` must read the discriminator off the still-wrapped envelope
    /// (`envelope.type`), not off the unwrapped payload (`data.type`) — the two halves of this test
    /// class exist because the bug required both a client-dispatch fix and a store-handler fix; either
    /// alone leaves alerts un-rendered.
    @Test
    void alertsJs_readsDiscriminatorOnTheEnvelope_notOnTheUnwrappedPayload() {
        var alertsJs = resource("/dashboard/js/stores/alerts.js").unwrap();

        assertThat(alertsJs).as("alerts.js must check the envelope-level discriminator")
                  .contains("envelope.type === 'ALERT'")
                  .as("the old payload-level check must be gone")
                  .doesNotContain("data.type === 'ALERT'");
    }
}
