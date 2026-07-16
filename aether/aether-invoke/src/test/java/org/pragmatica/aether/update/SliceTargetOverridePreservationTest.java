// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.update;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.Entry;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// #424 regression: the deployment-update writer (`DeploymentManagerImpl.addSliceTargetCommand`) and
/// the A/B lifecycle writer (`AbTestManager.targetPreservingOverrides`, exercised via promote) must
/// re-read the current `SliceTargetValue` and carry the operator's per-slice autoscaler overrides
/// onto the rewritten version instead of resetting them to `none()`.
class SliceTargetOverridePreservationTest {
    static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    static final ArtifactBase BASE = Artifact.artifact("org.test:my-slice:2.0.0").unwrap().base();
    static final Version V1 = Version.version("1.0.0").unwrap();
    static final Version V2 = Version.version("2.0.0").unwrap();

    @Nested
    class DeploymentUpdatePreservesOverrides {
        private static final String DEPLOYMENT_ID = "deployment-1";

        private CapturingRabiaNode rabiaNode;
        private DeploymentManager manager;

        @BeforeEach
        void setUp() {
            rabiaNode = new CapturingRabiaNode(SELF);

            var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(),
                                                              stubSerializer(),
                                                              stubDeserializer());

            seed(kvStore, SliceTargetKey.sliceTargetKey(BASE), targetWithOverrides(V2));
            seed(kvStore, DeploymentKey.deploymentKey(DEPLOYMENT_ID), deployedDeploymentValue());

            manager = DeploymentManager.deploymentManager(rabiaNode, kvStore);
            manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
        }

        @Test
        void rollback_preserves_autoscaler_overrides_on_slice_target() {
            manager.rollback(DEPLOYMENT_ID).onFailure(cause -> Assertions.fail(cause.message()));

            var rewritten = capturedSliceTargets(rabiaNode.appliedCommands).stream()
                                                                           .filter(target -> target.currentVersion()
                                                                                                   .equals(V1))
                                                                           .toList();

            assertThat(rewritten).isNotEmpty();
            assertThat(rewritten).allSatisfy(target -> {
                assertThat(target.maxInstances()).isEqualTo(Option.some(5));
                assertThat(target.scaleUpThreshold()).isEqualTo(Option.some(0.8));
                assertThat(target.scaleDownThreshold()).isEqualTo(Option.some(0.2));
            });
        }

        private DeploymentValue deployedDeploymentValue() {
            var now = System.currentTimeMillis();

            return DeploymentValue.deploymentValue(DEPLOYMENT_ID,
                                                   "org.test:my-slice:2.0.0",
                                                   V1.bareVersion(),
                                                   V2.bareVersion(),
                                                   DeploymentStrategy.ROLLING.name(),
                                                   DeploymentState.DEPLOYED.name(),
                                                   VersionRouting.ALL_OLD.toString(),
                                                   "",
                                                   "",
                                                   CleanupPolicy.GRACE_PERIOD.name(),
                                                   BASE.asString(),
                                                   3,
                                                   now,
                                                   now);
        }
    }

    @Nested
    class AbTestPromotePreservesOverrides {
        private CapturingRabiaNode rabiaNode;
        private AbTestManager manager;

        @BeforeEach
        void setUp() {
            rabiaNode = new CapturingRabiaNode(SELF);

            var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(),
                                                              stubSerializer(),
                                                              stubDeserializer());

            seed(kvStore, SliceTargetKey.sliceTargetKey(BASE), targetWithOverrides(V1));

            manager = AbTestManager.abTestManager(rabiaNode,
                                                  kvStore,
                                                  InvocationMetricsCollector.invocationMetricsCollector());
            manager.activate().await().onFailure(cause -> Assertions.fail(cause.message()));
        }

        @Test
        void concludeTest_preserves_autoscaler_overrides_on_promoted_version() {
            var testId = manager.createTest(BASE,
                                            Map.of("canary", V2),
                                            SplitRule.HeaderHashSplit.headerHashSplit("X-Request-Id", 2))
                                .await()
                                .onFailure(cause -> Assertions.fail(cause.message()))
                                .unwrap()
                                .testId();

            rabiaNode.appliedCommands.clear();

            manager.concludeTest(testId, "canary")
                   .await()
                   .onFailure(cause -> Assertions.fail(cause.message()));

            var promoted = capturedSliceTargets(rabiaNode.appliedCommands).stream()
                                                                          .filter(target -> target.currentVersion()
                                                                                                  .equals(V2))
                                                                          .toList();

            assertThat(promoted).isNotEmpty();
            assertThat(promoted).allSatisfy(target -> {
                assertThat(target.maxInstances()).isEqualTo(Option.some(5));
                assertThat(target.scaleUpThreshold()).isEqualTo(Option.some(0.8));
                assertThat(target.scaleDownThreshold()).isEqualTo(Option.some(0.2));
            });
        }
    }

    static SliceTargetValue targetWithOverrides(Version version) {
        return SliceTargetValue.sliceTargetValue(version,
                                                 3,
                                                 1,
                                                 Option.none(),
                                                 Option.some(5),
                                                 Option.some(0.8),
                                                 Option.some(0.2));
    }

    static void seed(KVStore<AetherKey, AetherValue> kvStore, AetherKey key, AetherValue value) {
        kvStore.process(kvStore.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(key,
                                                                                                                    value))));
    }

    static List<SliceTargetValue> capturedSliceTargets(List<KVCommand<AetherKey>> commands) {
        return commands.stream()
                       .filter(KVCommand.Put.class::isInstance)
                       .map(command -> ((KVCommand.Put<?, ?>) command).value())
                       .filter(SliceTargetValue.class::isInstance)
                       .map(SliceTargetValue.class::cast)
                       .toList();
    }

    static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    static final class CapturingRabiaNode implements RabiaNode<KVCommand<AetherKey>> {
        private final NodeId self;
        final List<KVCommand<AetherKey>> appliedCommands = new CopyOnWriteArrayList<>();

        CapturingRabiaNode(NodeId self) {
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

        @Override
        public ClusterNetwork network() {
            return null;
        }

        @Override
        public LeaderManager leaderManager() {
            return null;
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void authorizeActivation() {}

        @Override
        public void authorizeObservation() {}

        @Override
        public boolean isObserving() {
            return false;
        }

        @Override
        public List<Entry<?>> routeEntries() {
            return List.of();
        }
    }
}
