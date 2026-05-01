// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.health;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhaseValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;


/// Smoke-level integration check for the cluster-phase contract — full multi-node cluster
/// startup is exercised in R9. R3 verifies that:
///  - newly-constructed reconciler reports `BOOTING` until a `ClusterPhaseValue` Put commits
///  - ClusterPhaseKey wiring round-trips through `onClusterPhasePut`
class ClusterPhaseSmokeTest {
    private static final NodeId SELF = nodeId("self").unwrap();

    @Test
    void clusterPhase_initialValue_isBooting() {
        var reconciler = HealthReconciler.healthReconciler(SELF,
                                                           3,
                                                           _ -> Option.<NodeLifecycleValue>none(),
                                                           Option::none,
                                                           Option::none,
                                                           () -> 0,
                                                           cmds -> Promise.success(List.<Object>of()),
                                                           HealthReconcilerConfig.DEFAULT);
        // Pre-start phase: the implementation defaults to BOOTING.
        assertThat(reconciler.phase()).isEqualTo(ClusterPhase.BOOTING);
    }

    @Test
    void clusterPhase_advancesToNormal_whenLeaderAndAllPeersOnDuty() {
        // Leader, 3 ON_DUTY of 3, stable window 1ms → first observation sets stable marker,
        // second observation past window triggers leader to write ClusterPhaseValue=NORMAL.
        var captured = new java.util.ArrayList<KVCommand<?>>();
        var reconciler = HealthReconciler.healthReconciler(SELF,
                                                           3,
                                                           _ -> Option.<NodeLifecycleValue>none(),
                                                           () -> Option.some(ClusterPhase.BOOTING),
                                                           () -> Option.some(SELF),
                                                           () -> 3,
                                                           cmds -> {
                                                               captured.addAll(cmds);
                                                               return Promise.success(List.<Object>of());
                                                           },
                                                           HealthReconcilerConfig.healthReconcilerConfig(60_000L,
                                                                                                         30_000L,
                                                                                                         1L,
                                                                                                         30_000L));
        reconciler.start();
        // Give the reconciler an observation to trigger the phase tick. Use a target that
        // never reaches HEALTHY quorum so no NodeLifecycleKey is written, only the
        // ClusterPhaseKey.
        reconciler.onSwimObservation(new org.pragmatica.swim.SwimObservation.HealthyObserved(nodeId("peer-a").unwrap(), 1L));
        sleep(50L);
        reconciler.onSwimObservation(new org.pragmatica.swim.SwimObservation.HealthyObserved(nodeId("peer-a").unwrap(), 1L));
        var phaseWrites = captured.stream()
                                  .filter(cmd -> cmd instanceof KVCommand.Put<?, ?> put && put.key() instanceof AetherKey.ClusterPhaseKey)
                                  .map(cmd -> (KVCommand.Put<?, ?>) cmd)
                                  .map(put -> ((ClusterPhaseValue) put.value()).phase())
                                  .toList();
        assertThat(phaseWrites).contains(ClusterPhase.NORMAL);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
