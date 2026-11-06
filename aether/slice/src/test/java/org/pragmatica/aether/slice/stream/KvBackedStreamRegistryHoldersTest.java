// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress;

/// #1978 / #2160 (I-36): the stream registry's holders are not a shared absolute count. Two nodes that acquire at the same moment both
/// read the same count and each writes count+1, so one reference is lost; a release then removes an entry another holder still needs.
/// The schedule is made deterministic: each node's consensus apply is held until both have READ the registry, then committed in order.
class KvBackedStreamRegistryHoldersTest {
    private static final ResourceAddress ORDERS = resourceAddress("com.example.app:orders:1.0.0").unwrap();
    private static final ResourceAddress AUDIT = resourceAddress("com.example.app:audit:1.0.0").unwrap();
    private static final Artifact SLICE = Artifact.artifact("com.example:orders-slice:1.0.0").unwrap();
    private static final Artifact OTHER_SLICE = Artifact.artifact("com.example:billing-slice:1.0.0").unwrap();
    private static final NodeId REGISTRAR = NodeId.nodeId("registrar").unwrap();
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();

    private KVStore<AetherKey, AetherValue> kvStore;
    private HeldCluster registrarCluster;
    private HeldCluster nodeA;
    private HeldCluster nodeB;
    private KvBackedStreamRegistry registrar;
    private KvBackedStreamRegistry registryA;
    private KvBackedStreamRegistry registryB;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        registrarCluster = new HeldCluster("registrar", kvStore);
        registrar = new KvBackedStreamRegistry(registrarCluster, kvStore);
        nodeA = new HeldCluster("node-a", kvStore);
        nodeB = new HeldCluster("node-b", kvStore);
        registryA = new KvBackedStreamRegistry(nodeA, kvStore);
        registryB = new KvBackedStreamRegistry(nodeB, kvStore);
        registrar.register(StreamRegistryEntry.blueprint(ORDERS, RetentionPolicy.retentionPolicy(), Instant.EPOCH)).unwrap();
    }

    /// NT-172: two holders acquire concurrently, one releases. The registrar's reference and the other holder's remain: two holders, the
    /// entry present.
    @Test
    void acquireTwoConcurrentHoldersThenReleaseOneKeepsEntry() {
        var acquiredA = registryA.acquireReference(ORDERS);
        var acquiredB = registryB.acquireReference(ORDERS);

        assertThat(acquiredA.isResolved() || acquiredB.isResolved()).as("control: both applies are held until both nodes have read").isFalse();
        nodeA.commitHeld();
        nodeB.commitHeld();
        acquiredA.await().unwrap();
        acquiredB.await().unwrap();
        assertThat(registrar.lookup(ORDERS).unwrap().refCount()).as("control: registrar + both acquirers").isEqualTo(3);

        registryA.releaseReference(ORDERS).await().unwrap();

        assertThat(registrar.lookup(ORDERS).isPresent()).as("the entry another holder still needs").isTrue();
        assertThat(registrar.lookup(ORDERS).unwrap().refCount()).as("registrar + node-b").isEqualTo(2);
    }

    /// NT-173: the holder's node leaves committed membership without releasing (a crash). Its slice reference is reaped by the
    /// departure sweep; the other holders' references stay.
    @Test
    void aLeakedReference_isReapedWhenTheHoldersNodeLeaves() {
        sliceAcquire(NODE_A, SLICE, ORDERS, 1_000L);
        sliceAcquire(NODE_B, SLICE, ORDERS, 1_000L);
        assertThat(registrar.lookup(ORDERS).unwrap().refCount()).as("control: the leaked reference counts").isEqualTo(3);

        registrar.sweepHoldersOf(NODE_A).await().unwrap();

        assertThat(registrar.lookup(ORDERS).unwrap().refCount()).as("registrar + node-b").isEqualTo(2);
        assertThat(heldBy("node-a")).isEmpty();
    }

    /// K20 (#2505 mutation): the sweep removes EVERY reference the departed node held, across artifacts and streams.
    @Test
    void theSweepRemovesEveryReferenceOfTheNode() {
        registrar.register(StreamRegistryEntry.blueprint(AUDIT, RetentionPolicy.retentionPolicy(), Instant.EPOCH)).unwrap();
        sliceAcquire(NODE_A, SLICE, ORDERS, 1_000L);
        sliceAcquire(NODE_A, OTHER_SLICE, ORDERS, 1_000L);
        sliceAcquire(NODE_A, SLICE, AUDIT, 1_000L);
        assertThat(heldBy("node-a")).as("control").hasSize(3);

        registrar.sweepHoldersOf(NODE_A).await().unwrap();

        assertThat(heldBy("node-a")).isEmpty();
        assertThat(registrar.lookup(ORDERS).unwrap().refCount()).isEqualTo(1);
        assertThat(registrar.lookup(AUDIT).unwrap().refCount()).isEqualTo(1);
    }

    /// The set form sweeps every named node and no other.
    @Test
    void aSweepOfSeveralNodesRemovesTheirsAndNoOthers() {
        sliceAcquire(NODE_A, SLICE, ORDERS, 1_000L);
        sliceAcquire(NODE_B, SLICE, ORDERS, 1_000L);
        sliceAcquire(NodeId.nodeId("node-c").unwrap(), SLICE, ORDERS, 1_000L);

        registrar.sweepHoldersOf(Set.of(NODE_A, NODE_B)).await().unwrap();

        assertThat(heldBy("node-a")).isEmpty();
        assertThat(heldBy("node-b")).isEmpty();
        assertThat(heldBy("node-c")).hasSize(1);
    }

    /// F1 (#2505 review): an OPERATOR stream is a permanent reference, removable only by an explicit delete. The node that served the
    /// create leaving membership must not take it: the registrar's reference is cluster-owned, and the sweep leaves it.
    @Test
    void anOperatorStream_survivesTheSweepOfTheNodeThatRegisteredIt() {
        registrar.register(StreamRegistryEntry.operator(AUDIT, RetentionPolicy.retentionPolicy(), Instant.EPOCH)).unwrap();

        registrar.sweepHoldersOf(REGISTRAR).await().unwrap();

        assertThat(registrar.lookup(AUDIT).map(StreamRegistryEntry::refCount).or(0)).isEqualTo(1);
        assertThat(registrar.snapshot()).extracting(StreamRegistryEntry::address).contains(AUDIT, ORDERS);
    }

    /// F1, framework form: a system stream registered by the leader that then departs stays visible, and so does a reference taken by
    /// the convenience `acquireReference` (cluster-owned, released only by an explicit release).
    @Test
    void frameworkAndConvenienceReferences_surviveTheSweepOfTheirNode() {
        registrar.register(StreamRegistryEntry.framework(AUDIT, RetentionPolicy.retentionPolicy(), Instant.EPOCH)).unwrap();
        var acquired = registryA.acquireReference(AUDIT);
        nodeA.commitHeld();
        acquired.await().unwrap();
        var applies = registrarCluster.applies();

        registrar.sweepHoldersOf(Set.of(REGISTRAR, NODE_A)).await().unwrap();

        assertThat(registrar.lookup(AUDIT).unwrap().refCount()).as("registrar + node-a's convenience reference").isEqualTo(2);
        assertThat(registrarCluster.applies()).as("nothing node-owned to sweep: no apply").isEqualTo(applies);
    }

    /// Only node-owned (slice) references are reported for the level reconcile; the registrar's and convenience references are not.
    @Test
    void nodeOwnedHolderNodes_reportSliceHoldersOnly() {
        var acquired = registryB.acquireReference(ORDERS);
        nodeB.commitHeld();
        acquired.await().unwrap();
        sliceAcquire(NODE_A, SLICE, ORDERS, 1_000L);

        assertThat(registrar.nodeOwnedHolderNodes()).containsExactly(NODE_A);
    }

    /// F2 (#2505 review): node-a held a slice reference and left; a core node's sweep is built from a snapshot holding node-a's key; node-a
    /// returns and acquires again (the same key, attempt 0) before that sweep commits. The sweep carries the value it read, so it is refused
    /// and the live reference stays. Red with an unwitnessed sweep.
    @Test
    void aSweepCommittingAfterTheReturningNodesReacquire_keepsTheLiveReference() {
        sliceAcquire(NODE_A, SLICE, ORDERS, 1_000L);
        var sweep = registryB.sweepHoldersOf(NODE_A);
        sliceAcquire(NODE_A, SLICE, ORDERS, 2_000L);
        assertThat(registrar.lookup(ORDERS).unwrap().refCount()).as("control: registrar + the live slice").isEqualTo(2);

        nodeB.commitHeld();
        sweep.await().unwrap();

        assertThat(registrar.lookup(ORDERS).unwrap().refCount()).isEqualTo(2);
        assertThat(heldBy("node-a")).hasSize(1);
    }

    /// F2, the other order: a lagging sweep whose key another core's sweep already removed, landing after node-a returned and took the
    /// EMPTY key afresh. The fresh reference is a new life of the key, which the lagging sweep's witness does not match.
    @Test
    void aLaggingSweepAfterTheKeyWasTakenAfresh_keepsTheLiveReference() {
        sliceAcquire(NODE_A, SLICE, ORDERS, 1_000L);
        var lagging = registryB.sweepHoldersOf(NODE_A);
        registrar.sweepHoldersOf(NODE_A).await().unwrap();
        assertThat(heldBy("node-a")).as("control: the first sweep landed").isEmpty();
        sliceAcquire(NODE_A, SLICE, ORDERS, 2_000L);

        nodeB.commitHeld();
        lagging.await().unwrap();

        assertThat(heldBy("node-a")).hasSize(1);
    }

    /// The sweep of a node that holds nothing issues no consensus command.
    @Test
    void sweepOfANodeHoldingNothing_appliesNothing() {
        var before = registrar.lookup(ORDERS).unwrap().refCount();
        var applies = registrarCluster.applies();

        registrar.sweepHoldersOf(NodeId.nodeId("node-z").unwrap()).await().unwrap();

        assertThat(registrarCluster.applies()).isEqualTo(applies);
        assertThat(registrar.lookup(ORDERS).unwrap().refCount()).isEqualTo(before);
    }

    /// A sweep whose consensus apply fails reports the failure and removes nothing.
    @Test
    void aFailedSweep_reportsAndRemovesNothing() {
        sliceAcquire(NODE_B, SLICE, ORDERS, 1_000L);
        nodeA.failApplies();

        assertThat(registryA.sweepHoldersOf(NODE_B).await().isFailure()).isTrue();
        assertThat(registrar.lookup(ORDERS).unwrap().refCount()).as("registrar + node-b still").isEqualTo(2);
    }

    /// A stream held only by a slice (no registry reference to drop) is reported as not found by the convenience release, at once and
    /// with no consensus round. K27 (#2505 mutation): the outcome is asserted resolved BEFORE it is awaited, because node-b's applies are
    /// parked; a release that picked the slice's key would park on them, and an unbounded await would hang instead of failing.
    @Test
    void releaseReference_withNoRegistryHolder_isNotFound() {
        registrar.releaseReference(ORDERS).await().unwrap();
        sliceAcquire(NODE_A, SLICE, ORDERS, 1_000L);
        var applies = nodeB.applies();

        var outcome = registryB.releaseReference(ORDERS);

        assertThat(outcome.isResolved()).as("NOT_FOUND is decided locally, with no consensus round").isTrue();
        assertThat(nodeB.applies()).isEqualTo(applies);
        assertThat(errorOf(outcome)).isEqualTo(StreamRegistry.StreamRegistryError.General.NOT_FOUND);
        assertThat(registrar.lookup(ORDERS).unwrap().refCount()).as("the slice's reference is untouched").isEqualTo(1);
    }

    /// Two acquires by one node are two references: each takes its own attempt, so releasing one leaves the other.
    @Test
    void oneNodeAcquiringTwice_holdsTwoReferences() {
        var first = registryA.acquireReference(ORDERS);
        nodeA.commitHeld();
        first.await().unwrap();
        var second = registryA.acquireReference(ORDERS);
        nodeA.commitHeld();
        second.await().unwrap();

        assertThat(registrar.lookup(ORDERS).unwrap().refCount()).as("registrar + two of node-a").isEqualTo(3);
        assertThat(heldBy("node-a")).hasSize(2);
    }

    /// A release drops the caller's OWN reference, not another node's.
    @Test
    void releaseReference_dropsTheCallersOwnReference_notAnotherNodes() {
        var acquiredA = registryA.acquireReference(ORDERS);
        var acquiredB = registryB.acquireReference(ORDERS);

        nodeA.commitHeld();
        nodeB.commitHeld();
        acquiredA.await().unwrap();
        acquiredB.await().unwrap();

        registryB.releaseReference(ORDERS).await().unwrap();

        assertThat(heldBy("node-b")).isEmpty();
        assertThat(heldBy("node-a")).hasSize(1);
    }

    /// An operator deleting a stream another node registered drops the registrar's reference: the last reference, so the entry goes.
    @Test
    void releaseReference_fromAnotherNode_dropsTheRegistrarsReference() {
        var outcome = registryA.releaseReference(ORDERS);

        nodeA.commitHeld();

        assertThat(outcome.await().unwrap().removed()).isTrue();
        assertThat(registrar.lookup(ORDERS).isEmpty()).isTrue();
        assertThat(heldBy("registrar")).isEmpty();
    }

    private static Cause errorOf(Promise<?> promise) {
        return promise.await().fold(cause -> cause, _ -> null);
    }

    private void sliceAcquire(NodeId node, Artifact artifact, ResourceAddress address, long nowMs) {
        kvStore.process(kvStore.createBatch(registrar.acquireCommands(StreamRegistryEntry.blueprint(address, RetentionPolicy.retentionPolicy(), Instant.EPOCH),
                                                                      node,
                                                                      artifact,
                                                                      0L,
                                                                      nowMs)));
    }

    private List<String> heldBy(String node) {
        var held = new ArrayList<String>();

        kvStore.forEach(AetherKey.StreamRegistryHolderKey.class,
                        AetherValue.StreamRegistryHolderValue.class,
                        (key, _) -> addIfHeldBy(held, key, node));

        return held;
    }

    private static void addIfHeldBy(List<String> sink, AetherKey.StreamRegistryHolderKey key, String node) {
        if (key.node().id().equals(node)) {
            sink.add(key.asString());
        }
    }

    /// A consensus stub that applies at once (the registrar) or, after `hold()`, parks each apply until `commitHeld()`.
    private static final class HeldCluster implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final KVStore<AetherKey, AetherValue> kvStore;
        private final List<Runnable> held = new ArrayList<>();
        private boolean holding;
        private boolean failing;
        private int applies;

        HeldCluster(String self, KVStore<AetherKey, AetherValue> kvStore) {
            this.self = NodeId.nodeId(self).unwrap();
            this.kvStore = kvStore;
            this.holding = !"registrar".equals(self);
        }

        void commitHeld() {
            var pending = List.copyOf(held);

            held.clear();
            holding = false;
            pending.forEach(Runnable::run);
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

        int applies() {
            return applies;
        }

        void failApplies() {
            failing = true;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            applies++;
            if (failing) {
                return org.pragmatica.lang.utils.Causes.cause("consensus unavailable").promise();
            }

            if (!holding) {
                kvStore.process(kvStore.createBatch(commands));

                return Promise.success(List.of());
            }

            var applied = Promise.<List<R>> promise();

            held.add(() -> {
                kvStore.process(kvStore.createBatch(commands));
                applied.succeed(List.of());
            });

            return applied;
        }
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
