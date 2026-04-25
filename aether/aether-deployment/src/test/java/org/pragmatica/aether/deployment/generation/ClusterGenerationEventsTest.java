// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationChangedNotice;
import org.pragmatica.aether.slice.generation.GenerationChangedSink;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.aether.slice.generation.OperatorIntent.SetDesiredSize;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies `GenerationChangedNotice` is emitted whenever the snapshot epoch advances.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §14.4.
class ClusterGenerationEventsTest {
    private NodeId self;
    private ConcurrentLinkedQueue<GenerationChangedNotice> notices;
    private GenerationChangedSink sink;
    private HealthReconciler reconciler;

    @BeforeEach
    void setUp() {
        self = NodeId.nodeId("self").unwrap();
        notices = new ConcurrentLinkedQueue<>();
        sink = notices::add;
        reconciler = HealthReconciler.healthReconciler(self,
                                                       successCluster(),
                                                       ClusterGenerationProjector.clusterGenerationProjector(),
                                                       HlcClock.hlcClock("evt-test").unwrap(),
                                                       () -> 7L,
                                                       () -> true,
                                                       AutoHealConfig.DEFAULT,
                                                       sink);
        reconciler.start();
    }

    @Test
    void healthChange_advancesEpoch_emitsGenerationChanged() {
        var initial = reconciler.currentEpoch();

        reconciler.onSignal(new HealthSignal.SwimHint(NodeId.nodeId("peer").unwrap(),
                                                       HealthHint.SUSPECTED,
                                                       Epoch.ZERO));
        reconciler.onSignal(new HealthSignal.OperatorAction(new SetDesiredSize(5)));

        assertThat(notices).as("at least one bump should have emitted a notice").isNotEmpty();
        var first = notices.peek();
        assertThat(first.oldEpoch().rabiaTerm()).isEqualTo(initial.rabiaTerm());
        assertThat(first.newEpoch().localCounter())
                .as("counter must advance after a bump")
                .isGreaterThan(first.oldEpoch().localCounter());
    }

    @Test
    void multipleBumps_emitMultipleNotices() {
        notices.clear();
        reconciler.onSignal(new HealthSignal.OperatorAction(new SetDesiredSize(3)));
        reconciler.onSignal(new HealthSignal.OperatorAction(new SetDesiredSize(4)));
        reconciler.onSignal(new HealthSignal.OperatorAction(new SetDesiredSize(5)));

        assertThat(notices.size()).as("each distinct size change bumps once").isEqualTo(3);
    }

    @Test
    void noopSink_isInstalledByDefault_compatibleWithLegacyCallers() {
        var legacyReconciler = HealthReconciler.healthReconciler(self,
                                                                  successCluster(),
                                                                  ClusterGenerationProjector.clusterGenerationProjector(),
                                                                  HlcClock.hlcClock("noop-test").unwrap(),
                                                                  () -> 1L,
                                                                  () -> true,
                                                                  AutoHealConfig.DEFAULT);
        legacyReconciler.start();
        legacyReconciler.onSignal(new HealthSignal.OperatorAction(new SetDesiredSize(3)));
        assertThat(legacyReconciler.currentEpoch().localCounter()).isPositive();
    }

    @Test
    void noticeReason_matchesGenerationReason() {
        notices.clear();
        reconciler.onSignal(new HealthSignal.OperatorAction(new SetDesiredSize(8)));

        assertThat(notices).hasSize(1);
        assertThat(notices.peek().reason()).isEqualTo(GenerationReason.CLUSTER_SIZE_CHANGED);
    }

    @SuppressWarnings("unchecked") private static ClusterNode<KVCommand<AetherKey>> successCluster() {
        return (ClusterNode<KVCommand<AetherKey>>) Proxy.newProxyInstance(ClusterNode.class.getClassLoader(),
                                                                          new Class[]{ClusterNode.class},
                                                                          (_, method, _) -> dispatchClusterMethod(method.getName()));
    }

    private static Object dispatchClusterMethod(String methodName) {
        return switch (methodName) {
            case "apply" -> Promise.success(List.of());
            default -> throw new UnsupportedOperationException("Not in test proxy: " + methodName);
        };
    }
}
