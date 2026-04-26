// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.InvocationHandler;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeRequest;
import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.SliceActionConfig;
import org.pragmatica.aether.slice.SliceBridge;
import org.pragmatica.aether.slice.SliceStore;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;

import io.netty.buffer.ByteBuf;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Theme/Fix B (SSOT topology) — verifies the first ON_DUTY [`NodeLifecycleValue`] atom
/// written by [`NodeDeploymentManager`] seeds `observedCoreEpoch` from the injected
/// `currentEpochSupplier` instead of forcing [`Epoch#ZERO`]. Without this seed,
/// [`org.pragmatica.aether.deployment.cluster.ClusterTopologyManagerRecord`]'s
/// `nodeJoinEpoch` reader returns [`Epoch#ZERO`] for every node and the surplus-termination
/// "newest-first" tiebreak is structurally inert.
class NodeDeploymentManagerEpochSeedingTest {
    private static final NodeId SELF = NodeId.nodeId("node-self").unwrap();
    private static final NodeAddress SELF_ADDRESS = NodeAddress.nodeAddress("10.0.0.1", 9000).unwrap();

    private MessageRouter.MutableRouter router;
    private RecordingClusterNode clusterNode;
    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        router = MessageRouter.mutable();
        clusterNode = new RecordingClusterNode(SELF);
        kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
    }

    @Nested
    class WriteLifecycleOnDuty {
        @Test
        void writeLifecycleOnDuty_leaderEpochAvailable_seedsRealEpoch() {
            var expected = Epoch.epoch(5L, 17L);
            Supplier<Option<Epoch>> supplier = () -> Option.some(expected);

            var manager = buildManager(supplier);
            manager.onQuorumStateChange(QuorumStateNotification.established());

            var written = clusterNode.lastLifecycleValue();
            assertThat(written).isNotNull();
            assertThat(written.state()).isEqualTo(NodeLifecycleState.ON_DUTY);
            assertThat(written.observedCoreEpoch()).isEqualTo(expected);
            assertThat(written.host()).isEqualTo(SELF_ADDRESS.host());
            assertThat(written.port()).isEqualTo(SELF_ADDRESS.port());
        }

        @Test
        void writeLifecycleOnDuty_noLeaderEpoch_fallsBackToZero() {
            Supplier<Option<Epoch>> supplier = Option::none;

            var manager = buildManager(supplier);
            manager.onQuorumStateChange(QuorumStateNotification.established());

            var written = clusterNode.lastLifecycleValue();
            assertThat(written).isNotNull();
            assertThat(written.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
        }

        @Test
        void writeLifecycleOnDuty_supplierReadAtWriteTime_capturesLatestEpoch() {
            // Confirms the supplier is read at write time (not at construction), so
            // the captured epoch reflects the cluster state when ON_DUTY is emitted.
            var ref = new AtomicReference<Epoch>(Epoch.ZERO);
            Supplier<Option<Epoch>> supplier = () -> Option.some(ref.get());
            ref.set(Epoch.epoch(7L, 42L));

            var manager = buildManager(supplier);
            manager.onQuorumStateChange(QuorumStateNotification.established());

            var written = clusterNode.lastLifecycleValue();
            assertThat(written.observedCoreEpoch()).isEqualTo(Epoch.epoch(7L, 42L));
        }
    }

    /// Theme/Fix B (SSOT topology) — integration-flavoured: after the first ON_DUTY write
    /// with a real epoch, a `lifecycleReader` wired against the same value (the same shape
    /// CTM uses at `ClusterTopologyManagerRecord#nodeJoinEpoch`) returns the real epoch
    /// instead of [`Epoch#ZERO`].
    @Nested
    class NodeJoinEpochAfterFirstOnDutyWrite {
        @Test
        void nodeJoinEpoch_afterFirstOnDutyWriteWithRealEpoch_returnsRealEpoch() {
            var expected = Epoch.epoch(5L, 17L);
            Supplier<Option<Epoch>> supplier = () -> Option.some(expected);

            var manager = buildManager(supplier);
            manager.onQuorumStateChange(QuorumStateNotification.established());

            var atom = clusterNode.lastLifecycleValue();
            // Mirrors `ClusterTopologyManagerRecord.nodeJoinEpoch(nodeId)`:
            //   lifecycleReader.apply(nodeId).map(NodeLifecycleValue::observedCoreEpoch).or(Epoch.ZERO)
            Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader =
                    id -> id.equals(SELF) ? Option.some(atom) : Option.none();
            var observed = lifecycleReader.apply(SELF)
                                          .map(NodeLifecycleValue::observedCoreEpoch)
                                          .or(Epoch.ZERO);
            assertThat(observed)
                    .as("CTM-shaped reader returns the seeded epoch, not Epoch.ZERO")
                    .isEqualTo(expected);
        }

        /// Surplus-termination tiebreak verification: with two ON_DUTY atoms carrying
        /// different epochs, the newest-first comparator (`Comparator.reverseOrder()` on
        /// `observedCoreEpoch`) selects the NEWER node first for termination — the older
        /// node survives. This is the architectural intent that the [`Epoch#ZERO`] regression
        /// silently broke.
        @Test
        void surplusTerminationOrdering_picksNewerEpochFirst_whenSeedsAreDistinct() {
            var older = Epoch.epoch(1L, 5L);
            var newer = Epoch.epoch(5L, 17L);
            var nodeOlder = NodeId.nodeId("node-older").unwrap();
            var nodeNewer = NodeId.nodeId("node-newer").unwrap();
            var atomOlder = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                   System.currentTimeMillis(),
                                                                   "10.0.0.2",
                                                                   9000,
                                                                   older,
                                                                   HlcTimestamp.ZERO,
                                                                   AetherValue.ProvisioningSource.MANUAL);
            var atomNewer = NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                   System.currentTimeMillis(),
                                                                   "10.0.0.3",
                                                                   9000,
                                                                   newer,
                                                                   HlcTimestamp.ZERO,
                                                                   AetherValue.ProvisioningSource.MANUAL);
            Map<NodeId, NodeLifecycleValue> atoms = new HashMap<>();
            atoms.put(nodeOlder, atomOlder);
            atoms.put(nodeNewer, atomNewer);
            Function<NodeId, Option<NodeLifecycleValue>> reader =
                    id -> Option.option(atoms.get(id));

            // Reproduces CTM's reverse-order comparator on observedCoreEpoch.
            var sorted = new ArrayList<NodeId>();
            sorted.add(nodeOlder);
            sorted.add(nodeNewer);
            sorted.sort(java.util.Comparator.<NodeId, Epoch>comparing(
                    id -> reader.apply(id).map(NodeLifecycleValue::observedCoreEpoch).or(Epoch.ZERO),
                    java.util.Comparator.reverseOrder()));

            assertThat(sorted)
                    .as("newest-first ordering: newer-epoch node sorts before older-epoch node")
                    .containsExactly(nodeNewer, nodeOlder);
        }
    }

    private NodeDeploymentManager buildManager(Supplier<Option<Epoch>> currentEpochSupplier) {
        return NodeDeploymentManager.nodeDeploymentManager(SELF,
                                                            SELF_ADDRESS,
                                                            router,
                                                            stubSliceStore(),
                                                            clusterNode,
                                                            kvStore,
                                                            stubInvocationHandler(),
                                                            SliceActionConfig.sliceActionConfig(),
                                                            SliceCodec.sliceCodec(List.of()),
                                                            Option.none(),
                                                            Option.none(),
                                                            timeSpan(120_000).millis(),
                                                            timeSpan(2_000).millis(),
                                                            currentEpochSupplier);
    }

    // --- test fixtures ---

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
        };
    }

    private static InvocationHandler stubInvocationHandler() {
        return new InvocationHandler() {
            @Override public void onInvokeRequest(InvokeRequest request) {}
            @Override public void registerSlice(Artifact artifact, SliceBridge bridge) {}
            @Override public void unregisterSlice(Artifact artifact) {}
            @Override public Option<SliceBridge> localSlice(Artifact artifact) {
                return Option.none();
            }
            @Override public Option<SliceBridge> findBridgeByClassLoader(ClassLoader classLoader) {
                return Option.none();
            }
            @Override public Option<InvocationMetricsCollector> metricsCollector() {
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

    /// `ClusterNode` stub that captures the most recent
    /// [`KVCommand.Put`] targeting a [`NodeLifecycleKey`] for assertion.
    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final List<NodeLifecycleValue> lifecycleValues = Collections.synchronizedList(new ArrayList<>());

        RecordingClusterNode(NodeId self) {
            this.self = self;
        }

        NodeLifecycleValue lastLifecycleValue() {
            synchronized (lifecycleValues) {
                return lifecycleValues.isEmpty() ? null : lifecycleValues.get(lifecycleValues.size() - 1);
            }
        }

        @Override public NodeId self() {
            return self;
        }

        @Override public TopologyManager topologyManager() {
            return new TopologyManager() {
                @Override public NodeInfo self() {
                    return NodeInfo.nodeInfo(RecordingClusterNode.this.self,
                                             NodeAddress.nodeAddress("localhost", 9000).unwrap());
                }
                @Override public Option<NodeInfo> get(NodeId id) {
                    return Option.some(NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("localhost", 9000).unwrap()));
                }
                @Override public int clusterSize() {
                    return 1;
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
                    return List.of(RecordingClusterNode.this.self);
                }
            };
        }

        @Override public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            for (var command : commands) {
                if (command instanceof KVCommand.Put<?, ?> put
                    && put.key() instanceof NodeLifecycleKey
                    && put.value() instanceof NodeLifecycleValue value) {
                    lifecycleValues.add(value);
                }
            }
            return Promise.success(Collections.emptyList());
        }
    }
}
