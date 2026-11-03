// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AlertThresholdKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AlertThresholdValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;

/// #2616 (the DynamicConfigManager F4/W-9 hazard): the threshold map is written by the commit's own notification
/// handler. `setThreshold`/`removeThreshold` used to write it again in the apply continuation, so a later commit B
/// absorbed between A's commit and A's continuation was overwritten by A. The continuation must not touch the map.
@Timeout(30)
class AlertManagerStaleContinuationTest {
    private static final String METRIC = "test.stale.metric";

    @Test
    void setThreshold_continuation_doesNotOverwriteALaterCommit() throws InterruptedException {
        var applyA = Promise.<List<Object>> promise();
        var manager = managerApplying(applyA);

        var settled = settledSignal(manager.setThreshold(METRIC, 0.1, 0.2));
        manager.onAlertThresholdPut(put(0.1, 0.2));
        manager.onAlertThresholdPut(put(0.3, 0.4));
        applyA.succeed(List.of());
        settled.await();

        assertThat(manager.getAllThresholds().get(METRIC)).as("B was committed after A; A's continuation must not resurrect A")
                                                          .containsExactly(0.3, 0.4);
    }

    @Test
    void removeThreshold_continuation_doesNotRemoveALaterCommit() throws InterruptedException {
        var applyA = Promise.<List<Object>> promise();
        var manager = managerApplying(applyA);

        manager.onAlertThresholdPut(put(0.1, 0.2));
        var settled = settledSignal(manager.removeThreshold(METRIC));
        manager.onAlertThresholdRemove(remove());
        manager.onAlertThresholdPut(put(0.3, 0.4));
        applyA.succeed(List.of());
        settled.await();

        assertThat(manager.getAllThresholds().get(METRIC)).as("B re-set the key after A's removal; A's continuation must not drop it")
                                                          .containsExactly(0.3, 0.4);
    }

    @Test
    void setThreshold_continuation_leavesTheMapToTheCommit() throws InterruptedException {
        var applyA = Promise.<List<Object>> promise();
        var manager = managerApplying(applyA);

        var settled = settledSignal(manager.setThreshold(METRIC, 0.1, 0.2));
        applyA.succeed(List.of());
        settled.await();

        assertThat(manager.getAllThresholds()).as("only a commit's own onAlertThresholdPut writes the map")
                                              .doesNotContainKey(METRIC);
    }

    @Test
    void removeThreshold_continuation_leavesTheMapToTheCommit() throws InterruptedException {
        var applyA = Promise.<List<Object>> promise();
        var manager = managerApplying(applyA);

        manager.onAlertThresholdPut(put(0.1, 0.2));
        var settled = settledSignal(manager.removeThreshold(METRIC));
        applyA.succeed(List.of());
        settled.await();

        assertThat(manager.getAllThresholds()).as("only a commit's own onAlertThresholdRemove drops the key")
                                              .containsKey(METRIC);
    }

    @Test
    void onAlertThresholdRemove_dropsTheThreshold() {
        var manager = managerApplying(Promise.<List<Object>> promise());

        manager.onAlertThresholdPut(put(0.1, 0.2));
        manager.onAlertThresholdRemove(remove());

        assertThat(manager.getAllThresholds()).as("the commit's own remove notification is now the only thing that drops the key")
                                              .doesNotContainKey(METRIC);
    }

    // `onSuccess` continuations are event handlers: Promise runs them on the executor, in registration order, on one
    // task per resolved promise. This handler is registered after the continuation under test (and after the failure
    // log), so once it fires the continuation has run - a deterministic barrier, not a sleep.
    private static CountDownLatch settledSignal(Promise<Unit> promise) {
        var settled = new CountDownLatch(1);

        promise.onResult(_ -> settled.countDown());

        return settled;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static AlertManager managerApplying(Promise<List<Object>> apply) {
        var node = (RabiaNode<KVCommand<AetherKey>>) Mockito.mock(RabiaNode.class);

        Mockito.when(node.apply(Mockito.anyList())).thenReturn((Promise) apply);

        return AlertManager.alertManager(node, (KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    private static ValuePut<AlertThresholdKey, AlertThresholdValue> put(double warning, double critical) {
        var key = new AlertThresholdKey(METRIC);

        return new ValuePut<>(new KVCommand.Put<>(key, AlertThresholdValue.alertThresholdValue(METRIC, warning, critical)),
                              Option.none());
    }

    private static ValueRemove<AlertThresholdKey, AlertThresholdValue> remove() {
        return new ValueRemove<>(new KVCommand.Remove<>(new AlertThresholdKey(METRIC)), Option.none());
    }
}
