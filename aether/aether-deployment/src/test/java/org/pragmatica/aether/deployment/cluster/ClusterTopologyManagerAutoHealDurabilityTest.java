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
import org.pragmatica.aether.slice.kvstore.AetherKey.AutoHealStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AutoHealStateValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// #685 — the operator's auto-heal disable/enable is a DURABLE cluster fact stored in consensus KV
/// (`AetherKey.AutoHealStateKey`), never a leader's in-memory mood. Prior to this fix,
/// `ClusterTopologyManagerRecord` cached the flag in a local `AtomicBoolean`: a leader failover
/// silently reverted an operator's disable, because the newly-elected leader's fresh CTM instance
/// never saw the in-memory flag the old leader held.
///
/// This test proves the fix with two INDEPENDENT `ClusterTopologyManager` instances sharing ONE
/// real `KVStore<AetherKey, AetherValue>` — modeling exactly the failover scenario: an operator's
/// disable, applied through one node's CTM (`ctmA`, standing in for the pre-failover leader), must
/// be visible to a second CTM (`ctmB`, standing in for the newly-elected leader) that NEVER received
/// the call directly and only ever reads the shared, consensus-materialized KV. Every other CTM test
/// in this package uses a single instance with a per-test fake KV reader/writer — this file is the
/// one place the actual `KVStore` read-after-write path is exercised, because the defect this ticket
/// fixes is specifically about visibility ACROSS instances, not within one.
class ClusterTopologyManagerAutoHealDurabilityTest {
    private static final NodeId SELF = nodeId("node-self").unwrap();

    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("localhost", 5000).unwrap());

    private KVStore<AetherKey, AetherValue> kvStore;
    private Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier;
    private ClusterTopologyManager ctmA;
    private ClusterTopologyManager ctmB;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new KVStore<AetherKey, AetherValue>(router, stubSerializer(), stubDeserializer());
        commandApplier = commands -> Promise.success(kvStore.<Object>process(kvStore.createBatch(commands)));

        ctmA = newCtm();
        ctmB = newCtm();
    }

    /// Builds a fresh, independent CTM instance wired to the SAME `kvStore`/`commandApplier` as every
    /// other instance built by this method within a test — the "#841 pattern": one shared KV, many
    /// CTM instances, none aware of each other, exactly as separate nodes never share process memory
    /// but do share consensus-materialized state.
    private ClusterTopologyManager newCtm() {
        var config = new TopologyConfig(SELF, 5, timeSpan(60).seconds(), timeSpan(1).seconds(), List.of(INFO_SELF));
        var snapshotSource = new StubSnapshotSource();
        var observer = TopologyObserver.topologyObserver(config, quietRouter(), snapshotSource).unwrap();
        var autoHeal = AutoHealConfig.autoHealConfig(timeSpan(60).seconds(),
                                                      timeSpan(1).millis(),
                                                      AutoHealConfig.DEFAULT_STALE_OBSERVATION_TTL,
                                                      AutoHealConfig.DEFAULT_QUIC_MISS_PROMOTION_THRESHOLD,
                                                      AutoHealConfig.DEFAULT_PROVISIONING_TIMEOUT,
                                                      timeSpan(0).millis())
                                            .unwrap();

        return ClusterTopologyManager.clusterTopologyManager(observer,
                                                             new NoOpLifecycleManager(),
                                                             autoHeal,
                                                             DeploymentMap.deploymentMap(),
                                                             snapshotSource,
                                                             Option::none,
                                                             commandApplier,
                                                             () -> ClusterPhase.NORMAL,
                                                             _ -> {},
                                                             _ -> {},
                                                             Option::none,
                                                             () -> kvStore.getTyped(AutoHealStateKey.SINGLETON, AutoHealStateValue.class));
    }

    /// Condition 1 — a fresh/empty KV (no operator has ever touched the flag; also the state of
    /// every pre-#685 cluster) answers enabled, on every instance that reads it.
    @Test
    void freshEmptyKv_answersEnabled_onEveryInstance() {
        assertThat(ctmA.isAutoHealEnabled()).isTrue();
        assertThat(ctmB.isAutoHealEnabled()).isTrue();
    }

    @Test
    void disableOnOneInstance_isVisibleOnAnInstanceThatNeverReceivedTheCall() {
        var priorState = ctmA.setAutoHealEnabled(false, "operator: incident response").await().unwrap();

        assertThat(priorState).as("prior state was the default enabled").isTrue();
        assertThat(ctmA.isAutoHealEnabled()).isFalse();
        assertThat(ctmB.isAutoHealEnabled())
                .as("ctmB never saw setAutoHealEnabled — it must read the disable from the shared KV, not an in-memory flag")
                .isFalse();
    }

    @Test
    void enableAfterDisable_isVisibleOnTheOtherInstanceToo() {
        ctmA.setAutoHealEnabled(false, "operator: incident response").await().unwrap();

        var priorState = ctmB.setAutoHealEnabled(true, "operator: incident resolved").await().unwrap();

        assertThat(priorState).as("prior state was disabled").isFalse();
        assertThat(ctmB.isAutoHealEnabled()).isTrue();
        assertThat(ctmA.isAutoHealEnabled())
                .as("ctmA never saw the re-enable call — it must read it from the shared KV")
                .isTrue();
    }

    /// A same-state toggle is a documented no-op (see `ClusterTopologyManagerRecord#setAutoHealEnabled`)
    /// and must not perturb what every reader observes.
    @Test
    void redundantEnable_isNoOp_andLeavesStateUnchangedEverywhere() {
        var priorState = ctmA.setAutoHealEnabled(true, "operator: no-op enable").await().unwrap();

        assertThat(priorState).isTrue();
        assertThat(ctmA.isAutoHealEnabled()).isTrue();
        assertThat(ctmB.isAutoHealEnabled()).isTrue();
    }

    private static MessageRouter.MutableRouter quietRouter() {
        var router = MessageRouter.mutable();
        router.addRoute(NetworkServiceMessage.ListConnectedNodes.class, _ -> {});
        return router;
    }

    private static final class StubSnapshotSource implements GenerationSnapshotSource {
        private final AtomicReference<Option<MembershipView>> view = new AtomicReference<>(Option.none());

        @Override public Option<MembershipView> currentMembershipView() {
            return view.get();
        }

        @Override public long observedRabiaTerm() {
            return 0L;
        }
    }

    /// Auto-heal disable/enable never reaches `NodeLifecycleManager` — every method here is
    /// unreachable from this test's exercised surface and exists only to satisfy the interface.
    private static final class NoOpLifecycleManager implements NodeLifecycleManager {
        @Override public Promise<ActionResult> executeAction(NodeAction action) {
            return Promise.success(new ActionResult.NodeStarted(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                                                           InstanceStatus.RUNNING,
                                                                                           List.of("127.0.0.1"),
                                                                                           InstanceType.ON_DEMAND).unwrap()));
        }

        @Override public Promise<InstanceInfo> provisionNode(ProvisionSpec spec) {
            return Promise.success(InstanceInfo.instanceInfo(InstanceId.instanceId("stub").unwrap(),
                                                              InstanceStatus.RUNNING,
                                                              List.of("127.0.0.1"),
                                                              InstanceType.ON_DEMAND).unwrap());
        }

        @Override public Promise<Unit> terminateNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> restartNode(NodeId nodeId) {
            return Promise.success(Unit.unit());
        }

        @Override public boolean isCloudManaged() {
            return true;
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
