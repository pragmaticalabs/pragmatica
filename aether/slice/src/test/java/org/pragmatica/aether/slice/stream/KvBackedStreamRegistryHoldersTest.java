// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
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

    private KVStore<AetherKey, AetherValue> kvStore;
    private HeldCluster nodeA;
    private HeldCluster nodeB;
    private KvBackedStreamRegistry registrar;
    private KvBackedStreamRegistry registryA;
    private KvBackedStreamRegistry registryB;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        registrar = new KvBackedStreamRegistry(new HeldCluster("registrar", kvStore), kvStore);
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

    /// A consensus stub that applies at once (the registrar) or, after `hold()`, parks each apply until `commitHeld()`.
    private static final class HeldCluster implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final KVStore<AetherKey, AetherValue> kvStore;
        private final List<Runnable> held = new ArrayList<>();
        private boolean holding;

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

        @SuppressWarnings("unchecked")
        @Override
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
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
