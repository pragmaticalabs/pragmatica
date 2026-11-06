// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress;

/// #2505 F6 (owner ruling Q19 (a)): the level reconcile removes the references of nodes no longer in membership, whether or not their
/// departure edge was seen and whether or not an earlier sweep applied. A node is swept after two consecutive passes absent; the leader
/// alone acts; unknown membership, the local node and cluster-owned references are never swept.
class StreamRegistryHolderReconcilerTest {
    private static final ResourceAddress ORDERS = resourceAddress("com.example.app:orders:1.0.0").unwrap();
    private static final Artifact SLICE = Artifact.artifact("com.example:orders-slice:1.0.0").unwrap();
    private static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    private static final NodeId CORE_2 = NodeId.nodeId("core-2").unwrap();
    private static final NodeId WORKER = NodeId.nodeId("worker-1").unwrap();

    private KVStore<AetherKey, AetherValue> kvStore;
    private KvBackedStreamRegistry registry;
    private final AtomicBoolean leader = new AtomicBoolean(true);
    private final AtomicBoolean failing = new AtomicBoolean(false);
    private final AtomicReference<Option<Set<NodeId>>> members = new AtomicReference<>(Option.some(Set.of(SELF, CORE_2, WORKER)));
    private StreamRegistryHolderReconciler reconciler;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        registry = new KvBackedStreamRegistry(cluster(), kvStore);
        reconciler = StreamRegistryHolderReconciler.streamRegistryHolderReconciler(registry, SELF, members::get, leader::get);
        registry.register(StreamRegistryEntry.operator(ORDERS, RetentionPolicy.retentionPolicy(), Instant.EPOCH)).unwrap();
        sliceAcquire(SELF);
        sliceAcquire(CORE_2);
        sliceAcquire(WORKER);
    }

    /// The cadence is also the debounce (the minimum absence before a sweep): a change to it changes the safety margin.
    @Test
    void theInterval_isThirtySeconds() {
        assertThat(StreamRegistryHolderReconciler.INTERVAL.millis()).isEqualTo(30_000L);
    }

    /// A worker whose leave edge was never seen is swept after it was absent on two passes; nobody else is touched.
    @Test
    void aNodeAbsentOnTwoPasses_isSwept() {
        members.set(Option.some(Set.of(SELF, CORE_2)));

        reconciler.tick().await().unwrap();
        assertThat(holders()).as("one absent pass is only a suspicion").contains(WORKER);

        reconciler.tick().await().unwrap();

        assertThat(holders()).containsExactlyInAnyOrder(SELF, CORE_2);
        assertThat(registry.lookup(ORDERS).unwrap().refCount()).as("operator registrar + two slices").isEqualTo(3);
    }

    /// A node seen again between two passes is forgotten: absent, present, absent is one suspicion, not a departure.
    @Test
    void aNodeSeenAgainBetweenPasses_isForgotten() {
        members.set(Option.some(Set.of(SELF, CORE_2)));
        reconciler.tick().await().unwrap();
        members.set(Option.some(Set.of(SELF, CORE_2, WORKER)));
        reconciler.tick().await().unwrap();
        members.set(Option.some(Set.of(SELF, CORE_2)));

        reconciler.tick().await().unwrap();

        assertThat(holders()).contains(WORKER);
    }

    /// A sweep whose apply failed is repeated by the next pass, which still finds the node absent and holding: the pass converges.
    @Test
    void aFailedSweep_isRetriedByTheNextPass() {
        members.set(Option.some(Set.of(SELF, CORE_2)));
        reconciler.tick().await().unwrap();
        failing.set(true);
        assertThat(reconciler.tick().await().isFailure()).as("control: the sweep apply failed").isTrue();
        assertThat(holders()).contains(WORKER);
        failing.set(false);

        reconciler.tick().await().unwrap();

        assertThat(holders()).doesNotContain(WORKER);
    }

    /// A non-leader does nothing, and forgets its suspicions: a node that becomes leader sweeps nothing before its second pass.
    @Test
    void aNonLeader_sweepsNothing_andForgets() {
        members.set(Option.some(Set.of(SELF, CORE_2)));
        reconciler.tick().await().unwrap();
        leader.set(false);
        reconciler.tick().await().unwrap();
        reconciler.tick().await().unwrap();
        assertThat(holders()).as("a non-leader sweeps nothing").contains(WORKER);
        leader.set(true);

        reconciler.tick().await().unwrap();

        assertThat(holders()).as("the suspicion from before the lost leadership was forgotten").contains(WORKER);
    }

    /// While membership is unknown the pass does nothing and forgets, so an unwired membership never reads as everybody departed.
    @Test
    void unknownMembership_sweepsNothing_andForgets() {
        members.set(Option.some(Set.of(SELF)));
        reconciler.tick().await().unwrap();
        members.set(Option.none());
        reconciler.tick().await().unwrap();
        members.set(Option.some(Set.of(SELF)));

        reconciler.tick().await().unwrap();

        assertThat(holders()).containsExactlyInAnyOrder(SELF, CORE_2, WORKER);
    }

    /// The local node is never swept, even when its own membership projection does not list it.
    @Test
    void theLocalNode_isNeverSwept() {
        members.set(Option.some(Set.of(CORE_2, WORKER)));

        reconciler.tick().await().unwrap();
        reconciler.tick().await().unwrap();

        assertThat(holders()).contains(SELF);
    }

    /// Cluster-owned references are not node-owned: the operator stream's registrar reference stays even when membership is empty.
    @Test
    void clusterOwnedReferences_areNeverSwept() {
        members.set(Option.some(Set.of()));

        reconciler.tick().await().unwrap();
        reconciler.tick().await().unwrap();

        assertThat(holders()).containsExactly(SELF);
        assertThat(registry.lookup(ORDERS).unwrap().refCount()).as("the registrar's reference + the local slice").isEqualTo(2);
    }

    private void sliceAcquire(NodeId node) {
        kvStore.process(kvStore.createBatch(registry.acquireCommands(StreamRegistryEntry.blueprint(ORDERS, RetentionPolicy.retentionPolicy(), Instant.EPOCH),
                                                                     node,
                                                                     SLICE,
                                                                     0L,
                                                                     1_000L)));
    }

    /// The nodes holding a slice reference now.
    private List<NodeId> holders() {
        var nodes = new ArrayList<NodeId>();

        kvStore.forEach(AetherKey.StreamRegistryHolderKey.class,
                        AetherValue.StreamRegistryHolderValue.class,
                        (key, _) -> addIfSlice(nodes, key));

        return nodes;
    }

    private static void addIfSlice(List<NodeId> sink, AetherKey.StreamRegistryHolderKey key) {
        if (SLICE.equals(key.artifact())) {
            sink.add(key.node());
        }
    }

    private ClusterNode<KVCommand<AetherKey>> cluster() {
        return new ClusterNode<>() {
            @Override
            public NodeId self() {
                return SELF;
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
            public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                if (failing.get()) {
                    return Causes.cause("consensus unavailable").promise();
                }

                kvStore.process(kvStore.createBatch(commands));

                return Promise.success(List.of());
            }
        };
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
}
