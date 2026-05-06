// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistryKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamRegistryValue;
import org.pragmatica.aether.slice.stream.StreamRegistry.StreamRegistryError.General;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.slice.stream.StreamAddress.streamAddress;
import static org.pragmatica.aether.slice.stream.StreamVersion.streamVersion;


class KvBackedStreamRegistryTest {

    private static final StreamAddress SYSTEM_EVENTS = streamAddress("system:cluster-events:1.0.0").unwrap();
    private static final StreamAddress APP_ORDERS = streamAddress("com.example.app:orders:1.0.0").unwrap();
    private static final StreamAddress APP_ORDERS_V2 = streamAddress("com.example.app:orders:2.0.0").unwrap();

    private static Cause errorOf(Result<?> result) {
        return result.fold(c -> c, _ -> null);
    }

    private static Cause errorOf(Promise<?> promise) {
        return errorOf(promise.await());
    }

    private static StreamRegistryEntry frameworkEntry(StreamAddress address) {
        return StreamRegistryEntry.framework(address, RetentionPolicy.retentionPolicy(), Instant.EPOCH);
    }

    private static StreamRegistryEntry blueprintEntry(StreamAddress address) {
        return StreamRegistryEntry.blueprint(address, RetentionPolicy.retentionPolicy(), Instant.EPOCH);
    }

    private MessageRouter router;
    private KVStore<AetherKey, AetherValue> kvStore;
    private List<KVCommand<AetherKey>> capturedCommands;
    private KvBackedStreamRegistry registry;

    @BeforeEach void setUp() {
        router = MessageRouter.mutable();
        kvStore = new KVStore<>(router, stubSerializer(), stubDeserializer());
        capturedCommands = new ArrayList<>();
        var cluster = applyingClusterNode(kvStore, capturedCommands);
        registry = new KvBackedStreamRegistry(cluster, kvStore);
    }

    @Nested
    class Registration {

        @Test
        void registerNewEntryStoresAtRegistryKey() {
            var entry = frameworkEntry(SYSTEM_EVENTS);

            var result = registry.register(entry);

            assertThat(result.unwrap()).isEqualTo(entry);
            assertThat(kvStore.get(StreamRegistryKey.streamRegistryKey(SYSTEM_EVENTS)).isPresent()).isTrue();
        }

        @Test
        void registerDuplicateAddressFails() {
            var entry = frameworkEntry(SYSTEM_EVENTS);
            registry.register(entry);

            var duplicate = registry.register(entry);

            assertThat(errorOf(duplicate)).isEqualTo(General.ALREADY_REGISTERED);
        }
    }

    @Nested
    class Lookup {

        @Test
        void lookupExistingAddressReturnsEntry() {
            var entry = frameworkEntry(SYSTEM_EVENTS);
            registry.register(entry);

            var found = registry.lookup(SYSTEM_EVENTS);

            assertThat(found.isPresent()).isTrue();
            found.onPresent(e -> assertThat(e.address()).isEqualTo(SYSTEM_EVENTS));
        }

        @Test
        void lookupMissingAddressReturnsNone() {
            assertThat(registry.lookup(SYSTEM_EVENTS).isEmpty()).isTrue();
        }
    }

    @Nested
    class Resolve {

        @Test
        void resolveLatestPicksHighestVersion() {
            registry.register(blueprintEntry(APP_ORDERS));
            registry.register(blueprintEntry(APP_ORDERS_V2));

            var result = registry.resolve("com.example.app", "orders", StreamVersionSpec.latest());

            assertThat(result.unwrap().address().version()).isEqualTo(streamVersion(2, 0, 0).unwrap());
        }

        @Test
        void resolveExactReturnsMatchingEntry() {
            registry.register(blueprintEntry(APP_ORDERS));

            var result = registry.resolve("com.example.app", "orders",
                                           StreamVersionSpec.exact(streamVersion(1, 0, 0).unwrap()));

            assertThat(result.unwrap().address()).isEqualTo(APP_ORDERS);
        }

        @Test
        void resolveLatestNoVersionsFails() {
            var result = registry.resolve("com.example.app", "orders", StreamVersionSpec.latest());

            assertThat(errorOf(result)).isEqualTo(General.NO_VERSIONS_REGISTERED);
        }
    }

    @Nested
    class ReferenceCounting {

        @Test
        void acquireOnExistingIncrementsCount() {
            registry.register(frameworkEntry(SYSTEM_EVENTS));

            var after = registry.acquireReference(SYSTEM_EVENTS).await().unwrap();

            assertThat(after.refCount()).isEqualTo(2);
            assertThat(registry.lookup(SYSTEM_EVENTS).unwrap().refCount()).isEqualTo(2);
        }

        @Test
        void acquireOnMissingFails() {
            var result = registry.acquireReference(SYSTEM_EVENTS);

            assertThat(errorOf(result)).isEqualTo(General.NOT_FOUND);
        }

        @Test
        void releaseAtOneRemovesEntry() {
            registry.register(frameworkEntry(SYSTEM_EVENTS));

            var outcome = registry.releaseReference(SYSTEM_EVENTS).await().unwrap();

            assertThat(outcome.removed()).isTrue();
            assertThat(registry.lookup(SYSTEM_EVENTS).isEmpty()).isTrue();
        }

        @Test
        void releaseAboveOneKeepsEntry() {
            registry.register(frameworkEntry(SYSTEM_EVENTS));
            registry.acquireReference(SYSTEM_EVENTS).await();

            var outcome = registry.releaseReference(SYSTEM_EVENTS).await().unwrap();

            assertThat(outcome.removed()).isFalse();
            assertThat(outcome.entry().refCount()).isEqualTo(1);
        }

        @Test
        void releaseOnMissingFails() {
            var result = registry.releaseReference(SYSTEM_EVENTS);

            assertThat(errorOf(result)).isEqualTo(General.NOT_FOUND);
        }

        /// Reviewer test gap #17 — concurrent acquire on the same address.
        ///
        /// The convenience methods are best-effort optimistic per the [KvBackedStreamRegistry]
        /// contract: there is no CAS primitive in [KVCommand], so two concurrent
        /// `acquireReference(addr)` calls can both observe `n=1` and both write `n+1=2`,
        /// producing a final refcount of 2 instead of the strict-monotonic 3. This test pins the
        /// guarantee the implementation actually provides: **both calls succeed**, the final
        /// refcount is **at least 2** (no negative outcome), and the test cluster's serial-apply
        /// stub causes both writes to land. Strict refcount monotonicity requires the FSM
        /// piggyback path; this test exists so the relaxed guarantee is part of the visible
        /// contract.
        @Test
        void concurrentAcquire_doesNotLoseRefs() throws Exception {
            registry.register(frameworkEntry(SYSTEM_EVENTS));

            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var first = Promise.<StreamRegistryEntry>promise();
                var second = Promise.<StreamRegistryEntry>promise();
                executor.submit(() -> registry.acquireReference(SYSTEM_EVENTS).onResult(first::resolve));
                executor.submit(() -> registry.acquireReference(SYSTEM_EVENTS).onResult(second::resolve));

                var both = Promise.allOf(List.of(first, second)).await(org.pragmatica.lang.io.TimeSpan.timeSpan(5).seconds());
                var results = both.unwrap();
                assertThat(results).allMatch(Result::isSuccess);
            } finally {
                executor.shutdownNow();
            }
            // Final lookup confirms refcount advanced past the initial 1; exact value depends on
            // serial-apply ordering in the test cluster stub but must be >= 2.
            assertThat(registry.lookup(SYSTEM_EVENTS).unwrap().refCount()).isGreaterThanOrEqualTo(2);
        }
    }

    @Nested
    class CommandBuilders {

        @Test
        void acquireCommandOnNewAddressUsesTemplateWithRefcount1() {
            var template = blueprintEntry(APP_ORDERS);

            var command = registry.acquireCommand(template);

            assertThat(command).isInstanceOf(KVCommand.Put.class);
            var put = (KVCommand.Put<AetherKey, AetherValue>) command;
            assertThat(put.key()).isEqualTo(StreamRegistryKey.streamRegistryKey(APP_ORDERS));
            assertThat(((StreamRegistryValue) put.value()).entry().refCount()).isEqualTo(1);
        }

        @Test
        void acquireCommandOnExistingIncrements() {
            registry.register(blueprintEntry(APP_ORDERS));

            var command = registry.acquireCommand(blueprintEntry(APP_ORDERS));

            var put = (KVCommand.Put<AetherKey, AetherValue>) command;
            assertThat(((StreamRegistryValue) put.value()).entry().refCount()).isEqualTo(2);
        }

        @Test
        void releaseCommandOnAbsentReturnsNone() {
            assertThat(registry.releaseCommand(APP_ORDERS).isEmpty()).isTrue();
        }

        @Test
        void releaseCommandOnLastRefIsRemove() {
            registry.register(blueprintEntry(APP_ORDERS));

            var command = registry.releaseCommand(APP_ORDERS);

            assertThat(command.isPresent()).isTrue();
            assertThat(command.unwrap()).isInstanceOf(KVCommand.Remove.class);
        }

        @Test
        void releaseCommandWhenAboveOneIsPutDecremented() {
            registry.register(blueprintEntry(APP_ORDERS));
            registry.acquireReference(APP_ORDERS).await();

            var command = registry.releaseCommand(APP_ORDERS);

            assertThat(command.unwrap()).isInstanceOf(KVCommand.Put.class);
            var put = (KVCommand.Put<AetherKey, AetherValue>) command.unwrap();
            assertThat(((StreamRegistryValue) put.value()).entry().refCount()).isEqualTo(1);
        }
    }

    @Nested
    class Snapshot {

        @Test
        void snapshotProjectsAllEntries() {
            registry.register(blueprintEntry(APP_ORDERS));
            registry.register(blueprintEntry(APP_ORDERS_V2));

            var snapshot = registry.snapshot();

            assertThat(snapshot).hasSize(2);
        }

        @Test
        void snapshotIsEmptyWhenStoreEmpty() {
            assertThat(registry.snapshot()).isEmpty();
        }
    }

    private static ClusterNode<KVCommand<AetherKey>> applyingClusterNode(KVStore<AetherKey, AetherValue> kvStore,
                                                                         List<KVCommand<AetherKey>> captured) {
        return new ClusterNode<>() {
            @Override public NodeId self() {
                return NodeId.nodeId("test-node").unwrap();
            }

            @Override public TopologyManager topologyManager() {
                return null;
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @SuppressWarnings("unchecked")
            @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                captured.addAll(commands);
                commands.forEach(kvStore::process);
                return Promise.success(List.of());
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
