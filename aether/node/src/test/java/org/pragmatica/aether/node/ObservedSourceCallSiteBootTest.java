// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.fsm.ControlLoopContext;
import org.pragmatica.aether.resource.ResourceProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.config.ConfigService;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// F5b call-site pin (#3113, CTO ruling on the v3113c delta): [ObservedSourceWiringTest] pins the lambdas the wiring helper builds; this pins that
/// [AetherNode] CALLS them, on the right collaborators. A real FSM2 boot, a real `SliceObservedKey` row committed to the node's own store, and the
/// effect read at the destination: the control loop's slice state, and the all-instances-failed event the detector publishes. Wiring that merely
/// exists is not wiring that delivers: the v3113c swaps left both control-loop routes as no-ops (since F5a the loop is fed by the state feed, not by a route of its own) and the boot test green.
///
/// Mutations and what goes red (AetherNode call sites):
/// - control loop dropped from the state feed's listeners (F5a `DeploymentWiring` readers): `observedPutRow_reachesTheControlLoop` and
///   `observedRemove_clearsTheControlLoopsSliceState`.
///
/// Residual, not pinned here (needs a cluster): (1) the detector's source, `Option.none()` in place of the membership FSM, is pinned by the
/// forge `AutoRollbackOnAllInstancesFailedFsm2Test`: the detector acts only on the leader, and a single node never becomes leader (a cluster needs
/// three nodes), so a boot test cannot reach its publish. (2) The seam passed to the put route and to the detector (`deploymentWiring.admissions()`
/// against a fresh seam): a single node's boot-token registry records peers only, so every admitted token reads 0 and a fresh seam is
/// indistinguishable from the real one; that needs a peer admitted through the transport.
class ObservedSourceCallSiteBootTest {
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:call-site:1.0.0").unwrap();

    @TempDir
    Path tempDir;

    private AetherNode node;

    @AfterEach
    void tearDown() {
        if (node != null) {
            node.stop().await(timeSpan(10).seconds()).onFailure(cause -> {});
        }
        ConfigService.clear();
        ResourceProvider.clear();
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void observedPutRow_reachesTheControlLoop() {
        boot(MessageRouter.DelegateRouter.delegate());
        var loop = node.controlLoop();

        assertThat(slicesInProgress(loop)).as("precondition: nothing in progress before the row").isFalse();
        commit(row(SliceObservedState.LOADING));

        await("the row reached the loop").atMost(Duration.ofSeconds(30)).until(() -> slicesInProgress(loop));
    }

    @Test
    @Timeout(value = 120, unit = SECONDS)
    void observedRemove_clearsTheControlLoopsSliceState() {
        boot(MessageRouter.DelegateRouter.delegate());
        var loop = node.controlLoop();
        var row = row(SliceObservedState.LOADING);

        commit(row);
        await("the row reached the loop").atMost(Duration.ofSeconds(30)).until(() -> slicesInProgress(loop));
        remove(row);

        await("the removal reached the loop").atMost(Duration.ofSeconds(30)).until(() -> !slicesInProgress(loop));
    }

    private void boot(MessageRouter.DelegateRouter router) {
        var config = AetherNodeContentStorageWarnBootTest.minimalConfig(Option.none(),
                                                                        Option.none(),
                                                                        ConfigurationProvider.builder().build(),
                                                                        tempDir)
                                                         .withDeploymentEngine(DeploymentEngine.FSM2);

        node = AetherNode.aetherNode(config, router, NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs()), () -> {})
                         .onFailure(cause -> fail("boot must succeed: " + cause.message()))
                         .unwrap();
        node.start().await(timeSpan(30).seconds()).onFailure(cause -> fail("start() must succeed: " + cause.message()));
    }

    private void commit(SliceObservedValue row) {
        var store = node.kvStore();

        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(key(), row))));
    }

    private void remove(SliceObservedValue row) {
        var store = node.kvStore();

        store.process(store.createBatch(List.of(new KVCommand.Remove<AetherKey>(key(), Option.some(row)))));
    }

    private SliceObservedKey key() {
        return new SliceObservedKey(node.self(), ARTIFACT);
    }

    private static SliceObservedValue row(SliceObservedState state) {
        return new SliceObservedValue(state,
                                      1L,
                                      new Generation(1L, 1L),
                                      new ProcessIncarnation(1L, 0L),
                                      Option.none(),
                                      false,
                                      0,
                                      List.of(),
                                      0L);
    }

    /// The loop's slice-state map is not on its interface; its adapter record exposes the context, which is where the routes deliver.
    private static boolean slicesInProgress(ControlLoop loop) {
        try {
            var accessor = loop.getClass().getDeclaredMethod("ctx");

            accessor.setAccessible(true);
            return ((ControlLoopContext) accessor.invoke(loop)).anySliceInProgress();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("the control loop adapter no longer exposes ctx()", e);
        }
    }
}
