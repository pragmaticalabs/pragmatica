// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.fsm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceNodeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue.RouteEntry;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.consensus.fsm.ClusterFsmEvent.QuorumEstablished;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.statemachine.Fsm;
import org.pragmatica.statemachine.FsmTestHarness;

import java.net.SocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Covers FIX 2c (#325): on entry into the `Active` state, the node seeds the
/// [`org.pragmatica.aether.deployment.node.RoutingEpochAckTracker`] with the epoch-ack
/// expectations recovered from the KV store, so a node that already published routes before a
/// rejoin can still observe the cross-node acks that drive ROUTING → ACTIVE.
///
/// `seedEpochAckExpectationsFromKvStore()` runs inside [`NodeDeploymentState.Active#onEntry`],
/// reached deterministically via [`FsmTestHarness`] on a `QuorumEstablished` dispatch. The live
/// tracker (the `Active` record component) is inspected by replaying the acks the seeded
/// expectation expects and asserting that the ack threshold fires (or never does).
class NodeDeploymentStateSeedEpochAckTest {
    private static final NodeId SELF = NodeId.nodeId("self").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();
    private static final NodeId UNRELATED = NodeId.nodeId("unrelated").unwrap();
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:slice-a:1.0.0").unwrap();
    private static final Epoch SEED_EPOCH = Epoch.epoch(7L, 42L);

    private KVStore<AetherKey, AetherValue> kvStore;
    private NodeDeploymentContext ctx;
    private FsmTestHarness<NodeDeploymentState, ClusterFsmEvent> harness;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
        ClusterNode<KVCommand<AetherKey>> cluster = stubClusterNode(SELF);
        SliceStore sliceStore = stubSliceStore();
        var ctxHolder = new AtomicReference<NodeDeploymentContext>();
        Function<Fsm<NodeDeploymentState, ClusterFsmEvent>, NodeDeploymentState> factory =
                fsm -> buildContext(fsm, ctxHolder, router, kvStore, cluster, sliceStore);
        harness = FsmTestHarness.harness("ndm-seed-epoch-ack-test-" + SELF.id(), factory);
        ctx = ctxHolder.get();
    }

    @Nested
    class SeedingOnEntry {
        @Test
        void onEntry_ownNodeRoutesWithResolvableTargets_registersExpectationSatisfiableByTargetAck() {
            seedNodeArtifact(OTHER, ARTIFACT, SliceState.ACTIVE);
            seedNodeRoutes(SELF, ARTIFACT, SEED_EPOCH, routeEntry());

            harness.dispatch(new QuorumEstablished());

            // The seeding path registers the expectation against Epoch.ZERO (matching the live
            // ROUTING registration), so any target ack at-or-above ZERO satisfies it. Use the KV
            // epoch as a representative observed ack.
            var ready = tracker().observeAck(sliceKey(SELF, ARTIFACT), OTHER, SEED_EPOCH);

            assertThat(ready.isPresent())
                    .as("seeded expectation must be satisfiable by the sole target's ack")
                    .isTrue();
            assertThat(ready.unwrap()).isEqualTo(sliceKey(SELF, ARTIFACT));
        }

        @Test
        void onEntry_seededExpectation_ackFromUntargetedNode_isIgnored() {
            seedNodeArtifact(OTHER, ARTIFACT, SliceState.ACTIVE);
            seedNodeRoutes(SELF, ARTIFACT, SEED_EPOCH, routeEntry());

            harness.dispatch(new QuorumEstablished());

            // OTHER is the only seeded target; an ack from a node outside the target set must never
            // satisfy the seeded expectation (epoch-independent invariant).
            var untargeted = tracker().observeAck(sliceKey(SELF, ARTIFACT), UNRELATED, SEED_EPOCH);

            assertThat(untargeted.isPresent())
                    .as("an ack from a node outside the seeded target set must be ignored")
                    .isFalse();
        }
    }

    @Nested
    class NotSeeded {
        @Test
        void onEntry_otherNodeRoutesEntry_isNotRegistered() {
            seedNodeArtifact(OTHER, ARTIFACT, SliceState.ACTIVE);
            seedNodeRoutes(OTHER, ARTIFACT, SEED_EPOCH, routeEntry());

            harness.dispatch(new QuorumEstablished());

            assertThat(tracker().observeAck(sliceKey(OTHER, ARTIFACT), OTHER, SEED_EPOCH).isPresent())
                    .as("a NodeRoutesKey owned by another node must not seed a self expectation")
                    .isFalse();
            assertThat(tracker().observeAck(sliceKey(SELF, ARTIFACT), OTHER, SEED_EPOCH).isPresent())
                    .as("no self expectation exists for the other node's routes")
                    .isFalse();
        }

        @Test
        void onEntry_ownNodeRoutesWithEmptyRoutes_isNotRegistered() {
            seedNodeArtifact(OTHER, ARTIFACT, SliceState.ACTIVE);
            seedNodeRoutes(SELF, ARTIFACT, SEED_EPOCH);

            harness.dispatch(new QuorumEstablished());

            assertThat(tracker().observeAck(sliceKey(SELF, ARTIFACT), OTHER, SEED_EPOCH).isPresent())
                    .as("own routes with an empty route list must not seed an expectation")
                    .isFalse();
        }

        @Test
        void onEntry_ownNodeRoutesWithNoResolvableTargets_isNotRegistered() {
            seedNodeRoutes(SELF, ARTIFACT, SEED_EPOCH, routeEntry());

            harness.dispatch(new QuorumEstablished());

            assertThat(tracker().observeAck(sliceKey(SELF, ARTIFACT), OTHER, SEED_EPOCH).isPresent())
                    .as("with no other-node artifact target, no expectation is registered")
                    .isFalse();
        }
    }

    /// #325 — the node-local ROUTING-timeout remediation arm. A slice that has published its
    /// routes locally and is wedged in ROUTING (cross-node epoch-acks never arrived on a freshly
    /// reformed cluster) is force-progressed to ACTIVE by [`NodeDeploymentState.Active#
    /// remediateStuckRouting`] when its routing timeout fires, instead of wedging indefinitely
    /// (the 2/3-active signature). The remediation contract is verified directly (the production
    /// arm schedules it at [`SliceState#ROUTING`]'s timeout, exercised here without the real wait).
    @Nested
    class RoutingRemediation {
        @Test
        void remediateStuckRouting_sliceStillRouting_clearsAckExpectation() {
            seedNodeArtifact(OTHER, ARTIFACT, SliceState.ACTIVE);
            seedNodeRoutes(SELF, ARTIFACT, SEED_EPOCH, routeEntry());
            harness.dispatch(new QuorumEstablished());
            var key = sliceKey(SELF, ARTIFACT);
            seedDeploymentState(key, SliceState.ROUTING);

            activeState().remediateStuckRouting(key);

            // The expectation is gone: an ack that previously would have met the single-target
            // threshold now finds no pending expectation (cleared by the remediation).
            assertThat(activeState().routingEpochAckTracker().observeAck(key, OTHER, SEED_EPOCH).isPresent())
                    .as("remediation of a still-ROUTING slice clears the now-moot ack expectation")
                    .isFalse();
        }

        @Test
        void remediateStuckRouting_sliceNotRouting_isNoOp() {
            seedNodeArtifact(OTHER, ARTIFACT, SliceState.ACTIVE);
            seedNodeRoutes(SELF, ARTIFACT, SEED_EPOCH, routeEntry());
            harness.dispatch(new QuorumEstablished());
            var key = sliceKey(SELF, ARTIFACT);
            // Slice is already ACTIVE — remediation must not touch the (still-live) ack expectation.
            seedDeploymentState(key, SliceState.ACTIVE);

            activeState().remediateStuckRouting(key);

            assertThat(activeState().routingEpochAckTracker().observeAck(key, OTHER, SEED_EPOCH).isPresent())
                    .as("remediation no-ops for a slice that is no longer ROUTING — the expectation survives")
                    .isTrue();
        }

        @Test
        void remediateStuckRouting_unknownSlice_isNoOp() {
            harness.dispatch(new QuorumEstablished());
            var key = sliceKey(SELF, ARTIFACT);

            activeState().remediateStuckRouting(key);

            assertThat(activeState().routingEpochAckTracker().observeAck(key, OTHER, SEED_EPOCH).isPresent())
                    .as("remediation no-ops for a slice absent from the deployment map")
                    .isFalse();
        }
    }

    private NodeDeploymentState.Active activeState() {
        assertThat(harness.state()).isInstanceOf(NodeDeploymentState.Active.class);

        return (NodeDeploymentState.Active) harness.state();
    }

    private org.pragmatica.aether.deployment.node.RoutingEpochAckTracker tracker() {
        return activeState().routingEpochAckTracker();
    }

    private static SliceNodeKey sliceKey(NodeId node, Artifact artifact) {
        return SliceNodeKey.sliceNodeKey(artifact, node);
    }

    private static RouteEntry routeEntry() {
        return RouteEntry.activeRoute("GET", "/users/", "list");
    }

    private void seedNodeRoutes(NodeId node, Artifact artifact, Epoch epoch, RouteEntry... routes) {
        var key = NodeRoutesKey.nodeRoutesKey(node, artifact);
        var value = NodeRoutesValue.nodeRoutesValue(List.of(routes), epoch);

        applyToKvStore(new KVCommand.Put<>(key, value));
    }

    private void seedNodeArtifact(NodeId node, Artifact artifact, SliceState state) {
        var key = NodeArtifactKey.nodeArtifactKey(node, artifact);
        var value = NodeArtifactValue.nodeArtifactValue(state);

        applyToKvStore(new KVCommand.Put<>(key, value));
    }

    private void applyToKvStore(KVCommand<AetherKey> command) {
        kvStore.process(kvStore.createBatch(List.of(command)));
    }

    /// Seed the live `Active.deployments` map with a deployment in `state` so the remediation
    /// arm observes the slice in the desired (e.g. ROUTING) state without driving the full
    /// load/activate chain (the stub SliceStore fails those by design).
    private void seedDeploymentState(SliceNodeKey key, SliceState state) {
        activeState().deployments()
                     .put(key, NodeDeploymentManager.SliceDeployment.sliceDeployment(key, state, 0L));
    }

    private NodeDeploymentState buildContext(Fsm<NodeDeploymentState, ClusterFsmEvent> fsm,
                                             AtomicReference<NodeDeploymentContext> ctxHolder,
                                             MessageRouter router,
                                             KVStore<AetherKey, AetherValue> store,
                                             ClusterNode<KVCommand<AetherKey>> cluster,
                                             SliceStore sliceStore) {
        var context = new NodeDeploymentContext(fsm,
                                                SELF,
                                                new NodeAddress("localhost", 9000),
                                                sliceStore,
                                                SliceActionConfig.sliceActionConfig(),
                                                SliceCodec.sliceCodec(List.of()),
                                                cluster,
                                                store,
                                                stubInvocationHandler(),
                                                router,
                                                Option.none(),
                                                Option.none(),
                                                timeSpan(120_000).millis(),
                                                timeSpan(2_000).millis());

        ctxHolder.set(context);

        return context.dormant();
    }

    private static SliceStore stubSliceStore() {
        return new SliceStore() {
            @Override public List<LoadedSlice> loaded() {
                return List.of();
            }

            @Override public Promise<LoadedSlice> loadSlice(Artifact artifact) {
                return org.pragmatica.lang.utils.Causes.cause("stub").promise();
            }

            @Override public Promise<LoadedSlice> activateSlice(Artifact artifact) {
                return org.pragmatica.lang.utils.Causes.cause("stub").promise();
            }

            @Override public Promise<LoadedSlice> deactivateSlice(Artifact artifact) {
                return org.pragmatica.lang.utils.Causes.cause("stub").promise();
            }

            @Override public Promise<Unit> unloadSlice(Artifact artifact) {
                return Promise.unitPromise();
            }

            @Override public Option<org.pragmatica.config.ConfigurationProvider> sliceComposite(Artifact artifact) {
                return Option.none();
            }
        };
    }

    private static ClusterNode<KVCommand<AetherKey>> stubClusterNode(NodeId self) {
        return new ClusterNode<>() {
            @Override public NodeId self() {
                return self;
            }

            @Override public TopologyManager topologyManager() {
                return stubTopologyManager(self);
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                return Promise.success(Collections.emptyList());
            }
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 2;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self, OTHER);
            }
        };
    }

    private static org.pragmatica.aether.invoke.InvocationHandler stubInvocationHandler() {
        return new org.pragmatica.aether.invoke.InvocationHandler() {
            @Override public void onInvokeRequest(org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest request) {}

            @Override public void registerSlice(Artifact artifact, org.pragmatica.aether.slice.SliceBridge bridge) {}

            @Override public void unregisterSlice(Artifact artifact) {}

            @Override public Option<org.pragmatica.aether.slice.SliceBridge> localSlice(Artifact artifact) {
                return Option.none();
            }

            @Override public Option<org.pragmatica.aether.slice.SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
                return Option.none();
            }

            @Override public Option<org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector> metricsCollector() {
                return Option.none();
            }
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
