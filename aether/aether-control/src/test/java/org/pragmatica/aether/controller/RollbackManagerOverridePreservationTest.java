// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.controller;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.invoke.SliceFailureEvent;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// #424 regression: the auto-rollback writer must re-read the current `SliceTargetValue` and carry
/// the operator's per-slice autoscaler overrides (`maxInstances`/thresholds) onto the rolled-back
/// version instead of resetting them to `none()`.
class RollbackManagerOverridePreservationTest {
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final ArtifactBase BASE = Artifact.artifact("org.test:my-slice:2.0.0").unwrap().base();
    private static final Version V1 = Version.version("1.0.0").unwrap();
    private static final Version V2 = Version.version("2.0.0").unwrap();

    private CapturingClusterNode clusterNode;
    private KVStore<AetherKey, AetherValue> kvStore;
    private RollbackManager rollbackManager;

    @BeforeEach
    void setUp() {
        var config = RollbackConfig.rollbackConfig(true, true, TimeSpan.timeSpan(5).minutes(), 2).unwrap();

        clusterNode = new CapturingClusterNode(SELF);
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

        seed(SliceTargetKey.sliceTargetKey(BASE), targetWithOverrides());
        seed(PreviousVersionKey.previousVersionKey(BASE), PreviousVersionValue.previousVersionValue(BASE, V1, V2));

        rollbackManager = RollbackManager.rollbackManager(SELF,
                                                          config,
                                                          clusterNode,
                                                          kvStore,
                                                          new AlwaysLeaderManager(SELF));
    }

    @Test
    void onAllInstancesFailed_preserves_autoscaler_overrides_on_rollback_target() {
        rollbackManager.onAllInstancesFailed(failureEvent());

        var rolledBack = capturedSliceTargets().stream()
                                               .filter(target -> target.currentVersion().equals(V1))
                                               .toList();

        assertThat(rolledBack).isNotEmpty();
        assertThat(rolledBack).allSatisfy(target -> {
            assertThat(target.maxInstances()).isEqualTo(Option.some(5));
            assertThat(target.scaleUpThreshold()).isEqualTo(Option.some(0.8));
            assertThat(target.scaleDownThreshold()).isEqualTo(Option.some(0.2));
        });
    }

    private static SliceTargetValue targetWithOverrides() {
        return SliceTargetValue.sliceTargetValue(V2,
                                                 3,
                                                 1,
                                                 Option.none(),
                                                 Option.some(5),
                                                 Option.some(0.8),
                                                 Option.some(0.2));
    }

    private SliceFailureEvent.AllInstancesFailed failureEvent() {
        return SliceFailureEvent.AllInstancesFailed.allInstancesFailed("req-1",
                                                                       Artifact.artifact(BASE, V2),
                                                                       MethodName.methodName("doSomething").unwrap(),
                                                                       Option.some(Causes.cause("all instances failed")),
                                                                       List.of(NodeId.nodeId("node-2").unwrap()));
    }

    private void seed(AetherKey key, AetherValue value) {
        kvStore.process(kvStore.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(key,
                                                                                                                    value))));
    }

    private List<SliceTargetValue> capturedSliceTargets() {
        return clusterNode.appliedCommands.stream()
                                          .filter(KVCommand.Put.class::isInstance)
                                          .map(command -> ((KVCommand.Put<?, ?>) command).value())
                                          .filter(SliceTargetValue.class::isInstance)
                                          .map(SliceTargetValue.class::cast)
                                          .toList();
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    static final class CapturingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        final List<KVCommand<AetherKey>> appliedCommands = new CopyOnWriteArrayList<>();

        CapturingClusterNode(NodeId self) {
            this.self = self;
        }

        @Override
        public NodeId self() {
            return self;
        }

        @Override
        public TopologyManager topologyManager() {
            return null;
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            appliedCommands.addAll(commands);

            return Promise.success((List<R>) commands.stream().map(_ -> Unit.unit()).toList());
        }
    }

    static final class AlwaysLeaderManager implements LeaderManager {
        private final NodeId self;

        AlwaysLeaderManager(NodeId self) {
            this.self = self;
        }

        @Override
        public Option<NodeId> leader() {
            return Option.some(self);
        }

        @Override
        public boolean isLeader() {
            return true;
        }

        @Override
        public Option<Long> currentLeaderEpoch() {
            return Option.none();
        }

        @Override
        public void onLeaderCommitted(NodeId leader) {}

        @Override
        public void triggerElection() {}

        @Override
        public void stop() {}

        @Override
        public void peerJoined(TransportObservation.PeerJoined p) {}

        @Override
        public void peerDisconnected(TransportObservation.PeerDisconnected p) {}

        @Override
        public void peerObservedFaulty(TransportObservation.PeerObservedFaulty p) {}

        @Override
        public void peerReconnected(TransportObservation.PeerReconnected p) {}

        @Override
        public void selfShutdown(TransportObservation.SelfShutdown s) {}

        @Override
        public void watchClusterState(ClusterStateNotification q) {}
    }
}
