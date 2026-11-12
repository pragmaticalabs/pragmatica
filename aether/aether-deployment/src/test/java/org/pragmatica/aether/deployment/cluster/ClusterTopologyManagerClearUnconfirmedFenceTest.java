// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.DeploymentMap;
import org.pragmatica.aether.environment.AutoHealConfig;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.InstanceStatus;
import org.pragmatica.aether.environment.InstanceType;
import org.pragmatica.aether.environment.ProvisionSpec;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.UnconfirmedTerminationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #2944: the operator's clear of an unconfirmed-termination mark deletes the mark through the leader's conditional-commit fence. A fence CONFLICT
/// used to be reported as `Changed` ("changed or not confirmed committed"), which hides that the leader refused a transient conflict; it is now
/// reported as itself, while any other failure of the delete is still `Changed`.
class ClusterTopologyManagerClearUnconfirmedFenceTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();
    private static final NodeId RETIRED = nodeId("core-old").unwrap();
    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());

    private KVStore<AetherKey, AetherValue> kvStore;
    private Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(SELF, 1)))));
        commandApplier = commands -> Promise.success(kvStore.<Object>process(kvStore.createBatch(commands)));
    }

    /// This node read a mark the committed store does not hold (its applied view lagged): the delete expects that mark, the fence refuses.
    @Test
    void clearUnconfirmed_whenTheFenceRefusesTheDelete_reportsTheConflict_notChanged() {
        var ctm = newCtm();

        var outcome = ctm.clearUnconfirmed(RETIRED, "alice", "unknown", true).await();

        outcome.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("a refused delete must not report a clear"))
               .onFailure(cause -> {
                   assertThat(cause).isInstanceOf(ClusterTopologyManager.UnconfirmedClearRefusal.class)
                                    .isNotInstanceOf(ClusterTopologyManager.UnconfirmedClearRefusal.Changed.class);
                   assertThat(cause.message()).contains("conditional commit refused");
               });
    }

    /// Control: a delete that fails for any reason other than the fence CONFLICT is still a clear not confirmed committed.
    @Test
    void clearUnconfirmed_whenTheDeleteFailsOtherwise_isStillChanged() {
        var ctm = newCtm();

        ctm.setHierarchyStateWriter(HierarchyStateWriter.unavailable());

        var outcome = ctm.clearUnconfirmed(RETIRED, "alice", "unknown", true).await();

        outcome.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("an inactive writer must not report a clear"))
               .onFailure(cause -> assertThat(cause).isEqualTo(new ClusterTopologyManager.UnconfirmedClearRefusal.Changed(RETIRED)));
    }

    private ClusterTopologyManager newCtm() {
        var config = new TopologyConfig(SELF, 5, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(INFO_SELF));
        var snapshotSource = new StubSnapshotSource();
        var observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshotSource).unwrap();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(1).millis(), AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT).unwrap();
        var manager = ClusterTopologyManager.clusterTopologyManager(observer,
                                                                    new ExternalLifecycleManager(),
                                                                    autoHeal,
                                                                    DeploymentMap.deploymentMap(),
                                                                    snapshotSource,
                                                                    Option::none,
                                                                    commandApplier,
                                                                    () -> ClusterPhase.NORMAL,
                                                                    _ -> {},
                                                                    _ -> {},
                                                                    Option::none,
                                                                    Option::none,
                                                                    MembershipLiveness.UNWIRED);

        manager.setHierarchyStateWriter(HierarchyStateWriter.hierarchyStateWriter(() -> Option.some(new LeaderValue(SELF, 1)),
                                                                                  kvStore::get,
                                                                                  commandApplier));
        manager.setUnconfirmedMarks(() -> Map.of(RETIRED, new UnconfirmedTerminationValue("test mark", false, false)));
        manager.activate();
        return manager;
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();

        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
        return router;
    }

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        @Override
        public Option<MembershipView> currentMembershipView() {
            return Option.none();
        }

        @Override
        public long observedRabiaTerm() {
            return 0L;
        }
    }

    /// Every node is external, so a forced clear has no provider to ask and reaches the delete.
    private static final class ExternalLifecycleManager implements NodeLifecycleManager {
        @Override
        public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(stubInstance()));
        }

        @Override
        public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            return Promise.success(stubInstance());
        }

        @Override
        public Promise<Unit> terminateNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override
        public boolean isCloudManaged() {
            return false;
        }

        @Override
        public boolean externalNode(NodeId nodeId) {
            return true;
        }

        private static InstanceInfo stubInstance() {
            return InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                             InstanceStatus.RUNNING,
                                             List.of("127.0.0.1"),
                                             InstanceType.ON_DEMAND).unwrap();
        }
    }

    private static org.pragmatica.serialization.Serializer stubSerializer() {
        return new org.pragmatica.serialization.Serializer() {
            @Override
            public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
        };
    }

    private static org.pragmatica.serialization.Deserializer stubDeserializer() {
        return new org.pragmatica.serialization.Deserializer() {
            @Override
            public <T> T read(io.netty.buffer.ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
