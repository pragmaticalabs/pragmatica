// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.stream;

import org.pragmatica.aether.slice.resource.ResourceVersion;

import org.pragmatica.aether.slice.resource.ResourceAddress;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistryHolderKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamRegistryKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.stream.StreamRegistry.StreamRegistryError.General;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
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
import static org.pragmatica.aether.slice.resource.ResourceAddress.resourceAddress;
import static org.pragmatica.aether.slice.resource.ResourceVersion.resourceVersion;


class KvBackedStreamRegistryTest {

    private static final ResourceAddress SYSTEM_EVENTS = resourceAddress("system:cluster-events:1.0.0").unwrap();
    private static final ResourceAddress APP_ORDERS = resourceAddress("com.example.app:orders:1.0.0").unwrap();
    private static final ResourceAddress APP_ORDERS_V2 = resourceAddress("com.example.app:orders:2.0.0").unwrap();

    private static Cause errorOf(Result<?> result) {
        return result.fold(c -> c, _ -> null);
    }

    private static Cause errorOf(Promise<?> promise) {
        return errorOf(promise.await());
    }

    private static StreamRegistryEntry frameworkEntry(ResourceAddress address) {
        return StreamRegistryEntry.framework(address, RetentionPolicy.retentionPolicy(), Instant.EPOCH);
    }

    private static StreamRegistryEntry blueprintEntry(ResourceAddress address) {
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

            assertThat(result.unwrap().address().version()).isEqualTo(resourceVersion(2, 0, 0).unwrap());
        }

        /// Latest is chosen among the entries of THIS namespace and stream only. Red when the match is namespace OR stream: a higher
        /// version of another stream in the same namespace would win.
        @Test
        void resolveLatestIgnoresAnotherStreamOfTheSameNamespace() {
            registry.register(blueprintEntry(APP_ORDERS));
            registry.register(blueprintEntry(resourceAddress("com.example.app:invoices:9.0.0").unwrap()));

            var result = registry.resolve("com.example.app", "orders", StreamVersionSpec.latest());

            assertThat(result.unwrap().address()).isEqualTo(APP_ORDERS);
        }

        @Test
        void resolveExactReturnsMatchingEntry() {
            registry.register(blueprintEntry(APP_ORDERS));

            var result = registry.resolve("com.example.app", "orders",
                                           StreamVersionSpec.exact(resourceVersion(1, 0, 0).unwrap()));

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

        /// The entry record outlives its last holder, invisible; `register` must treat it as absent and overwrite it. Red when the
        /// already-registered check reads the raw record instead of the visible entry.
        @Test
        void registerAfterTheLastReleaseSucceedsOverTheHolderlessRecord() {
            registry.register(frameworkEntry(SYSTEM_EVENTS));
            registry.releaseReference(SYSTEM_EVENTS).await().unwrap();

            var again = registry.register(frameworkEntry(SYSTEM_EVENTS));

            assertThat(again.isSuccess()).isTrue();
            assertThat(registry.lookup(SYSTEM_EVENTS).unwrap().refCount()).isEqualTo(1);
        }

        /// `register` reports the count the entry now has: the registrar's one reference, whatever count the caller's record carried.
        @Test
        void registerReportsOneReferenceWhateverCountTheRecordCarried() {
            var registered = registry.register(frameworkEntry(SYSTEM_EVENTS).withRefCount(5)).unwrap();

            assertThat(registered.refCount()).isEqualTo(1);
        }

        /// Registry attempts are seeded from the clock, so a restarted node does not re-use the attempt of a reference its previous run left.
        @Test
        void registryAttemptsAreSeededFromTheClock() {
            registry.register(frameworkEntry(SYSTEM_EVENTS));
            registry.acquireReference(SYSTEM_EVENTS).await().unwrap();

            var attempts = new ArrayList<Long>();
            kvStore.forEach(StreamRegistryHolderKey.class, AetherValue.StreamRegistryHolderValue.class, (key, _) -> attempts.add(key.attempt()));
            assertThat(attempts).anyMatch(attempt -> attempt > 1_000_000_000_000L);
        }

        /// `releaseReference` drops this node's most recent registry reference, not an older one. Red when it picks the lowest attempt
        /// (the registrar's own, attempt 0, would go while the newer acquires stay).
        @Test
        void releaseReferenceDropsTheLatestAttemptNotTheRegistrars() {
            registry.register(frameworkEntry(SYSTEM_EVENTS));
            registry.acquireReference(SYSTEM_EVENTS).await().unwrap();
            registry.acquireReference(SYSTEM_EVENTS).await().unwrap();

            registry.releaseReference(SYSTEM_EVENTS).await().unwrap();

            var attempts = new ArrayList<Long>();
            kvStore.forEach(StreamRegistryHolderKey.class, AetherValue.StreamRegistryHolderValue.class, (key, _) -> attempts.add(key.attempt()));
            assertThat(attempts).hasSize(2).contains(0L);
        }

        /// With no reference of its own, `releaseReference` falls back to another node's registry reference, the one whose key sorts first, so
        /// every node picks the same one. Red when the fallback picks the key that sorts last.
        @Test
        void releaseReferenceWithoutAnOwnReferenceDropsTheFirstSortingOthers() {
            var nodeB = NodeId.nodeId("node-b").unwrap();
            var nodeC = NodeId.nodeId("node-c").unwrap();
            kvStore.process(kvStore.createBatch(KvBackedStreamRegistry.registerCommands(frameworkEntry(SYSTEM_EVENTS), nodeB, 1L)));
            kvStore.process(kvStore.createBatch(KvBackedStreamRegistry.registerCommands(frameworkEntry(SYSTEM_EVENTS), nodeC, 1L)));

            registry.releaseReference(SYSTEM_EVENTS).await().unwrap();

            var remaining = new ArrayList<String>();
            kvStore.forEach(StreamRegistryHolderKey.class, AetherValue.StreamRegistryHolderValue.class, (key, _) -> remaining.add(key.node().id()));
            assertThat(remaining).containsExactly("node-c");
        }

        /// K26 (#2505 mutation): references are counted per address. Holders of one stream neither count toward nor make visible another.
        @Test
        void holdersOfOneAddressDoNotCountTowardAnother() {
            var slice = Artifact.artifact("com.example:orders-slice:1.0.0").unwrap();
            registry.register(blueprintEntry(APP_ORDERS));
            registry.register(blueprintEntry(APP_ORDERS_V2));
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), NodeId.nodeId("node-1").unwrap(), slice, 0L, 1L)));
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), NodeId.nodeId("node-2").unwrap(), slice, 0L, 1L)));

            assertThat(registry.lookup(APP_ORDERS).unwrap().refCount()).as("registrar + two slices").isEqualTo(3);
            assertThat(registry.lookup(APP_ORDERS_V2).unwrap().refCount()).as("its registrar only").isEqualTo(1);
            assertThat(registry.lookup(SYSTEM_EVENTS).isEmpty()).as("no holder of its own: not visible").isTrue();
        }

        /// K13 (#2505 mutation): `acquireReference` on a record no holder references is NOT_FOUND, as on a missing one. Red when it reads
        /// the raw record.
        @Test
        void acquireReferenceOnAHolderlessRecordIsNotFound() {
            registry.register(frameworkEntry(SYSTEM_EVENTS));
            registry.releaseReference(SYSTEM_EVENTS).await().unwrap();

            assertThat(errorOf(registry.acquireReference(SYSTEM_EVENTS))).isEqualTo(General.NOT_FOUND);
        }

        /// K22 (#2505 mutation): a snapshot lists only entries with a holder; a holder-less record is invisible there as in `lookup`.
        @Test
        void snapshotOmitsAHolderlessRecord() {
            registry.register(frameworkEntry(SYSTEM_EVENTS));
            registry.register(frameworkEntry(APP_ORDERS));
            registry.releaseReference(SYSTEM_EVENTS).await().unwrap();

            assertThat(registry.snapshot()).extracting(StreamRegistryEntry::address).containsExactly(APP_ORDERS);
        }

        /// A snapshot reports each entry's holder count, as `lookup` does. Red when the snapshot reports a constant.
        @Test
        void snapshotReportsTheHolderCount() {
            registry.register(frameworkEntry(SYSTEM_EVENTS));
            registry.acquireReference(SYSTEM_EVENTS).await().unwrap();

            assertThat(registry.snapshot()).singleElement().satisfies(entry -> assertThat(entry.refCount()).isEqualTo(2));
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
        /// Since #1978 each `acquireReference` puts its own holder key (this node, [KvBackedStreamRegistry#REGISTRY_ARTIFACT],
        /// a fresh attempt) and reads no count, so two concurrent calls keep both references: the registrar's plus two is
        /// exactly 3. Red when an acquire writes a count read from the store (both would write 2) or reuses an attempt.
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
            assertThat(registry.lookup(SYSTEM_EVENTS).unwrap().refCount()).as("the registrar's reference and both acquires").isEqualTo(3);
        }
    }

    @Nested
    class CommandBuilders {
        private final NodeId holder = NodeId.nodeId("node-1").unwrap();
        private final Artifact slice = Artifact.artifact("com.example:orders-slice:1.0.0").unwrap();

        @Test
        void acquireCommandsOnNewAddressPutTheEntryTemplateAndTheHoldersOwnKey() {
            var template = blueprintEntry(APP_ORDERS);

            var commands = registry.acquireCommands(template, holder, slice, 0L, 1_000L);

            assertThat(commands).hasSize(2);
            assertThat(commands.get(0).key()).isEqualTo(StreamRegistryKey.streamRegistryKey(APP_ORDERS));
            assertThat(commands.get(1).key()).isEqualTo(StreamRegistryHolderKey.streamRegistryHolderKey(APP_ORDERS, holder, slice, 0L));
            assertThat(commands).allMatch(KVCommand.Put.class::isInstance);
        }

        @Test
        void acquireCommandsOnExistingEntryPutOnlyTheHoldersOwnKey() {
            registry.register(blueprintEntry(APP_ORDERS));

            var commands = registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 1_000L);

            assertThat(commands).singleElement()
                                .satisfies(command -> assertThat(command.key()).isEqualTo(StreamRegistryHolderKey.streamRegistryHolderKey(APP_ORDERS, holder, slice, 0L)));
        }

        /// A release of a key that is not held removes only that key, and carries no witness: a no-op while the key stays empty.
        @Test
        void releaseCommandRemovesOnlyTheHoldersOwnKey() {
            var command = KvBackedStreamRegistry.releaseCommand(kvStore, APP_ORDERS, holder, slice, 3L);

            assertThat(command).isEqualTo(new KVCommand.Remove<>(StreamRegistryHolderKey.streamRegistryHolderKey(APP_ORDERS, holder, slice, 3L),
                                                                 Option.none()));
        }

        /// A release of a held key carries the value the key holds as its witness. Red when the release is a bare `Remove(key)`.
        @Test
        void releaseCommandOfAHeldKeyIsWitnessedByTheValueItHolds() {
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 1_000L)));

            var command = KvBackedStreamRegistry.releaseCommand(kvStore, APP_ORDERS, holder, slice, 0L);

            assertThat(command).isEqualTo(new KVCommand.Remove<>(StreamRegistryHolderKey.streamRegistryHolderKey(APP_ORDERS, holder, slice, 0L),
                                                                 Option.<Object> some(AetherValue.StreamRegistryHolderValue.streamRegistryHolderValue(1_000L, 1L))));
        }

        @Test
        void acquireCommandsStampTheHolderWithTheAcquireTime() {
            var commands = registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 1_234L);

            assertThat(commands.getLast()).isEqualTo(new KVCommand.Put<>(StreamRegistryHolderKey.streamRegistryHolderKey(APP_ORDERS, holder, slice, 0L),
                                                                         AetherValue.StreamRegistryHolderValue.streamRegistryHolderValue(1_234L, 1L)));
        }

        /// A re-acquire of a held key writes the key's NEXT version with the new acquire time: what makes a value read before it a stale
        /// witness. Red when the re-acquire writes the first version again (the applier would refuse it, and a stale remover would match).
        @Test
        void aReAcquireWritesTheNextVersionOfTheKey() {
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 1_000L)));

            var commands = registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 2_000L);
            kvStore.process(kvStore.createBatch(commands));

            assertThat(commands).singleElement()
                                .isEqualTo(new KVCommand.Put<>(StreamRegistryHolderKey.streamRegistryHolderKey(APP_ORDERS, holder, slice, 0L),
                                                               AetherValue.StreamRegistryHolderValue.streamRegistryHolderValue(2_000L, 2L)));
            assertThat(kvStore.get(StreamRegistryHolderKey.streamRegistryHolderKey(APP_ORDERS, holder, slice, 0L)).unwrap())
                .as("the applier admitted the successor version")
                .isEqualTo(AetherValue.StreamRegistryHolderValue.streamRegistryHolderValue(2_000L, 2L));
        }

        /// F4 (#2505 review): a release of the previous activation that lands after the next activation of the same artifact on the same
        /// node acquired again (one key, attempt 0) is refused, so the ACTIVE instance keeps its reference. Red with a bare `Remove(key)`.
        @Test
        void aLateReleaseOfThePreviousActivationKeepsTheCurrentActivationsReference() {
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 1_000L)));
            var lateRelease = KvBackedStreamRegistry.releaseCommand(kvStore, APP_ORDERS, holder, slice, 0L);
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 2_000L)));
            assertThat(registry.lookup(APP_ORDERS).unwrap().refCount()).as("control: the current activation holds it").isEqualTo(1);

            kvStore.process(kvStore.createBatch(List.of(lateRelease)));

            assertThat(registry.lookup(APP_ORDERS).map(StreamRegistryEntry::refCount).or(0)).as("the current activation's reference stays")
                                                                                          .isEqualTo(1);
        }

        /// The same late release, retried after the previous activation's release already landed and the next activation acquired the empty
        /// key afresh: the new life of the key is not the one the release read. Red when the witness is compared by version alone.
        @Test
        void aRetriedReleaseAfterTheKeyWasTakenAfreshIsRefused() {
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 1_000L)));
            var release = KvBackedStreamRegistry.releaseCommand(kvStore, APP_ORDERS, holder, slice, 0L);
            kvStore.process(kvStore.createBatch(List.of(release)));
            assertThat(registry.lookup(APP_ORDERS).isEmpty()).as("control: the first copy of the release landed").isTrue();
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 2_000L)));

            kvStore.process(kvStore.createBatch(List.of(release)));

            assertThat(registry.lookup(APP_ORDERS).map(StreamRegistryEntry::refCount).or(0)).isEqualTo(1);
        }

        /// F5 (#2505 review): an acquire of a stream whose record no holder references any more writes its own template, so the deleted
        /// entry's metadata (an OPERATOR record here) does not come back. Red when the template is written only when no record exists.
        @Test
        void anAcquireOverAHolderlessRecordWritesItsTemplate() {
            registry.register(StreamRegistryEntry.operator(APP_ORDERS, RetentionPolicy.retentionPolicy(), Instant.EPOCH)).unwrap();
            registry.releaseReference(APP_ORDERS).await().unwrap();
            assertThat(registry.lookup(APP_ORDERS).isEmpty()).as("control: deleted").isTrue();

            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 2_000L)));

            assertThat(registry.lookup(APP_ORDERS).unwrap().registeredBy()).isEqualTo(StreamRegistryEntry.RegisteredByKind.BLUEPRINT);
        }

        @Test
        void registerCommandsCarryTheRegistrarsOwnReferenceAtAttemptZero() {
            var commands = KvBackedStreamRegistry.registerCommands(blueprintEntry(APP_ORDERS), holder, 5_000L);

            assertThat(commands).hasSize(2);
            assertThat(commands.get(0).key()).isEqualTo(StreamRegistryKey.streamRegistryKey(APP_ORDERS));
            assertThat(commands.get(1)).isEqualTo(new KVCommand.Put<>(StreamRegistryHolderKey.streamRegistryHolderKey(APP_ORDERS,
                                                                                                                     holder,
                                                                                                                     KvBackedStreamRegistry.REGISTRY_ARTIFACT,
                                                                                                                     0L),
                                                                      AetherValue.StreamRegistryHolderValue.streamRegistryHolderValue(5_000L, 1L)));
        }

        @Test
        void aReleaseOfALaterAttemptDoesNotTouchAnEarlierOne() {
            registry.register(blueprintEntry(APP_ORDERS));
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 1L, 1_000L)));
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 2L, 1_001L)));

            kvStore.process(kvStore.createBatch(List.of(KvBackedStreamRegistry.releaseCommand(kvStore, APP_ORDERS, holder, slice, 1L))));

            assertThat(registry.lookup(APP_ORDERS).unwrap().refCount()).as("registrar + attempt 2").isEqualTo(2);
        }

        /// Acquiring again as the same holder `(node, artifact, attempt)` puts the same key: one reference, not two. Red when a
        /// re-acquire adds a reference (a rebuilt or re-driven activation would then leak one).
        @Test
        void aReAcquireBySameHolderIsTheSameReference() {
            registry.register(blueprintEntry(APP_ORDERS));
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 1_000L)));
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 2_000L)));

            assertThat(registry.lookup(APP_ORDERS).unwrap().refCount()).as("registrar + the one holder").isEqualTo(2);
        }

        /// Releasing as a holder that never acquired removes nothing: neither another holder's key nor the registrar's. Red when
        /// a release removes anything but the named holder's own key.
        @Test
        void aReleaseByAHolderThatNeverAcquiredChangesNothing() {
            var stranger = NodeId.nodeId("node-2").unwrap();

            registry.register(blueprintEntry(APP_ORDERS));
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 1_000L)));

            kvStore.process(kvStore.createBatch(List.of(KvBackedStreamRegistry.releaseCommand(kvStore, APP_ORDERS, stranger, slice, 0L))));

            assertThat(registry.lookup(APP_ORDERS).unwrap().refCount()).as("registrar + the holder, both untouched").isEqualTo(2);
        }

        @Test
        void aSliceHolderKeepsTheEntryVisibleAfterTheRegistrarLetsGo() {
            registry.register(blueprintEntry(APP_ORDERS));
            kvStore.process(kvStore.createBatch(registry.acquireCommands(blueprintEntry(APP_ORDERS), holder, slice, 0L, 1_000L)));

            registry.releaseReference(APP_ORDERS).await().unwrap();

            assertThat(registry.lookup(APP_ORDERS).unwrap().refCount()).isEqualTo(1);
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
                // rc1 KVStore.process takes a batched StateMachine.Batch; wrap the commands via createBatch.
                kvStore.process(kvStore.createBatch(commands));
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
