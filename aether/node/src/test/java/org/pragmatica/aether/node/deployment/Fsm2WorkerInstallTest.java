// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Ruling D8 through the production factory (`DeploymentWiring.deploymentWiring(config, ...)`): on an FSM2 worker every replica snapshot install
/// of the store, whatever its path, resets the node FSM's replay watermark to the install's revision, its burst empty or not. Ported from the
/// v2770c probes (E1, F2).
class Fsm2WorkerInstallTest {
    private static final Artifact ORDERS = Artifact.artifact("org.example:orders:1.0.0").unwrap();
    private static final Artifact PAYMENTS = Artifact.artifact("org.example:payments:1.0.0").unwrap();

    private static AetherValue.SliceDesiredValue desired() {
        return new AetherValue.SliceDesiredValue(AetherValue.SliceGoal.ACTIVE, new AetherValue.Generation(3L, 1L), 1L);
    }

    private static AetherKey.SliceDesiredKey desiredKey(Artifact artifact) {
        return AetherKey.SliceDesiredKey.sliceDesiredKey(Fsm2Harness.SELF, artifact);
    }

    private static boolean activeNode(Fsm2Harness harness) {
        return harness.system.map(system -> system.nodeInterpreter().model().active()).or(false);
    }

    private static void target(Fsm2Harness worker, int n) {
        worker.put(AetherKey.SliceTargetKey.sliceTargetKey(ORDERS.base()), AetherValue.SliceTargetValue.sliceTargetValue(ORDERS.version(), n, n));
    }

    private static void install(Fsm2Harness worker, Map<AetherKey, AetherValue> content, long revision) {
        worker.installing = Map.copyOf(content);
        assertThat(worker.store.restoreCommittedSnapshot(new byte[0], revision).isSuccess()).isTrue();
        worker.store.replayNotifications();
    }

    private static Fsm2Harness enteredWorker() {
        var worker = Fsm2Harness.productionWorker(DeploymentEngine.FSM2).assembled();

        worker.commitLeader();
        worker.leaderPut(AetherKey.DeploymentEngineKey.deploymentEngineKey(),
                         new AetherValue.DeploymentEngineValue(DeploymentEngine.FSM2, Fsm2Harness.SELF, 1L));
        worker.awaitThat(worker.guard::isOpen);
        target(worker, 1);
        worker.wiring.passiveClientSwitch(() -> {}).run();
        worker.awaitThat(() -> activeNode(worker));

        return worker;
    }

    /// F1 (v2770c E1): install 1 at revision 50 (its burst steps a goal) raises the watermark; install 2, the store's current content at revision
    /// 1, has an EMPTY burst; the live goal of the new life at revision 2 is then applied, not dropped against 50. Mutation: the store's install
    /// listener not registered (or not resetting), and the live goal is `dropped_revision`.
    @Test
    void anInstallWithAnEmptyBurst_belowTheWatermark_resetsIt_soTheNextLiveGoalIsApplied() {
        try (var worker = enteredWorker()) {
            var first = new HashMap<AetherKey, AetherValue>(worker.store.snapshot());

            first.put(desiredKey(ORDERS), desired());
            install(worker, first, 50L);
            worker.awaitThat(() -> worker.count("DesiredPut", "applied", "node") == 1.0);
            // the node FSM's own writes settle, then install 2 is exactly the store's current content: an empty diff
            await().pollDelay(Duration.ofMillis(1000)).atMost(Duration.ofSeconds(3)).until(() -> true);
            install(worker, new HashMap<AetherKey, AetherValue>(worker.store.snapshot()), 1L);
            worker.slot.set(1);
            worker.leaderPut(desiredKey(PAYMENTS), desired());

            worker.awaitThat(() -> worker.count("DesiredPut", "applied", "node") == 2.0);
            assertThat(worker.count("DesiredPut", "dropped_revision", "node")).isZero();
        }
    }

    /// F2 (v2770c): the metadata channel installs through `restoreSnapshot` (the committed revision unchanged; its projection carries
    /// `SliceTargetKey`, `WorkerMetadataIndex`), then `replayNotifications()`, on the SAME store the node FSM listens to (`WorkerHierarchyAssembly`
    /// hands `kvStore` to both). The install reaches the node FSM as an install signal like the replica feed's: its burst is stepped. Named limit,
    /// as for any install: one at the revision of the last install read (here the entry cut, with no live entry since) is taken for it; the test
    /// installs after a live entry moved the committed revision. Mutation: the install listener not registered pins the E1 sibling; here the
    /// reach itself (the burst's goal applied after a `restoreSnapshot`).
    @Test
    void aMetadataChannelInstall_throughRestoreSnapshot_reachesTheNodeFsm() {
        try (var worker = enteredWorker()) {
            worker.leaderPut(desiredKey(PAYMENTS), desired());
            worker.awaitThat(() -> worker.count("DesiredPut", "applied", "node") == 1.0);
            var content = new HashMap<AetherKey, AetherValue>(worker.store.snapshot());

            content.put(desiredKey(ORDERS), desired());
            worker.installing = Map.copyOf(content);
            assertThat(worker.store.restoreSnapshot(new byte[0]).isSuccess()).isTrue();
            worker.store.replayNotifications();

            worker.awaitThat(() -> worker.count("DesiredPut", "applied", "node") == 2.0);
        }
    }
}
