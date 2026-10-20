// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.dht.DHTClient;
import org.pragmatica.dht.Partition;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.PrefetchConfig;
import org.pragmatica.storage.PrefetchHint;
import org.pragmatica.storage.PrefetchManager;
import org.pragmatica.storage.StorageInstance.TierInfo;
import org.pragmatica.storage.TierLevel;

import static java.util.Arrays.copyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// Cross-node prefetch is wired through the REAL production path, not a manager a test builds for itself:
/// `StorageFactory.createAll` decides which instances have one, the instance's own `get` reports every read hit to
/// it, the node's ready/stop path wakes and sleeps it, and a hint handed to a second node's manager copies the block
/// into that node's cache tier.
class StorageFactoryPrefetchWiringTest {
    private static final String ARTIFACTS = "artifacts";
    private static final String CONTENT = "content";
    private static final int READS = 12;

    @TempDir
    Path nodeADir;

    @TempDir
    Path nodeBDir;

    private Map<String, StorageFactory.StorageSetup> createNode(Path dir, String nodeId, DHTClient dht, boolean artifactsPrefetch) {
        return createNode(dir, nodeId, dht, artifactsPrefetch, StorageConfig.DEFAULT_PREFETCH_MAX_IN_FLIGHT);
    }

    private Map<String, StorageFactory.StorageSetup> createNode(Path dir,
                                                                String nodeId,
                                                                DHTClient dht,
                                                                boolean artifactsPrefetch,
                                                                int prefetchMaxInFlight) {
        var defaults = StorageConfig.storageConfig();
        var artifactsConfig = StorageConfig.storageConfig(defaults.memoryMaxBytes(),
                                                          defaults.diskMaxBytes(),
                                                          dir.resolve("artifacts").toString(),
                                                          dir.resolve("artifacts-snapshots").toString(),
                                                          defaults.snapshotMutationThreshold(),
                                                          defaults.snapshotMaxInterval(),
                                                          defaults.snapshotRetentionCount(),
                                                          defaults.walPath(),
                                                          false,
                                                          true,
                                                          artifactsPrefetch,
                                                          prefetchMaxInFlight);
        var setups = StorageFactory.createAll(Map.of(ARTIFACTS, artifactsConfig), nodeId, Option.some(dht), Option.none())
                                   .onFailure(cause -> fail("createAll must succeed: " + cause.message()))
                                   .unwrap();

        setups.values().forEach(setup -> admitDht(dht, setup));

        return setups;
    }

    /// The DHT tier refuses every operation until its post-formation encryption-marker check admits it, the order
    /// `AetherNode.start()` uses: verify markers, then serve.
    private static void admitDht(DHTClient dht, StorageFactory.StorageSetup setup) {
        setup.dhtMarkerCheck()
             .onPresent(check -> StorageFactory.verifyDhtMarker(dht, check)
                                               .await()
                                               .onFailure(cause -> fail("admitting " + setup.name() + "'s DHT tier failed: " + cause.message())));
    }

    @Test
    void createAll_artifactsAndContentCarryRealManagers_streamsAndAnOptedOutInstanceDoNot() {
        var dht = new InMemoryDHTClient();
        var enabled = createNode(nodeADir, "node-a", dht, true);
        var optedOut = createNode(nodeBDir, "node-b", dht, false);
        var streams = StorageFactory.defaultStreamStorage(Option.none(), nodeADir.resolve("streams"), "node-a").unwrap();

        assertThat(enabled.get(ARTIFACTS).prefetchManager()).isNotSameAs(PrefetchManager.NOOP);
        assertThat(enabled.get(CONTENT).prefetchManager()).isNotSameAs(PrefetchManager.NOOP);
        assertThat(streams.prefetchManager()).as("streams prefetches its own segments").isSameAs(PrefetchManager.NOOP);
        assertThat(optedOut.get(ARTIFACTS).prefetchManager()).as("[storage.artifacts] prefetch = false").isSameAs(PrefetchManager.NOOP);
        assertThat(optedOut.get(CONTENT).prefetchManager()).as("the other instance is unaffected").isNotSameAs(PrefetchManager.NOOP);
        assertThat(StorageFactory.prefetchManagers(enabled).keySet()).containsExactlyInAnyOrder(ARTIFACTS, CONTENT);
        assertThat(StorageFactory.prefetchManagers(optedOut).keySet()).containsExactly(CONTENT);
    }

    @Test
    void managers_startDormant_andTheNodesReadyAndStopPathsWakeAndSleepThem() {
        var setups = createNode(nodeADir, "node-a", new InMemoryDHTClient(), true);
        var managers = StorageFactory.prefetchManagers(setups).values();

        assertThat(managers).allSatisfy(manager -> assertThat(manager.isActive()).isFalse());

        StorageFactory.activatePrefetch(setups);

        assertThat(managers).allSatisfy(manager -> assertThat(manager.isActive()).isTrue());

        StorageFactory.deactivatePrefetch(setups);

        assertThat(managers).allSatisfy(manager -> assertThat(manager.isActive()).isFalse());
    }

    @Test
    void get_onTheRealInstance_reportsEveryReadHitToItsManager() {
        var setups = createNode(nodeADir, "node-a", new InMemoryDHTClient(), true);
        var artifacts = setups.get(ARTIFACTS);
        var content = "read-me-often".getBytes(StandardCharsets.UTF_8);
        var id = artifacts.instance().put(content).await().unwrap();

        artifacts.prefetchManager().activate();
        for (var i = 0; i < READS; i++) {
            artifacts.instance().get(id).await().unwrap();
        }

        assertThat(artifacts.prefetchManager().collectHints())
            .extracting(PrefetchHint::blockIdHex, PrefetchHint::accessCount)
            .containsExactly(tuple(id.hexString(), READS));
        assertThat(setups.get(CONTENT).prefetchManager().collectHints()).as("another instance's reads are not this one's").isEmpty();
    }

    @Test
    void processHints_onAnotherNodesManager_copiesTheBlockIntoItsCacheTier_andRecordsItAsACache() {
        var dht = new InMemoryDHTClient();
        var nodeA = createNode(nodeADir, "node-a", dht, true);
        var nodeB = createNode(nodeBDir, "node-b", dht, true);
        var content = "written-on-a-wanted-on-b".getBytes(StandardCharsets.UTF_8);
        var id = nodeA.get(ARTIFACTS).instance().put(content).await().unwrap();
        var b = nodeB.get(ARTIFACTS);

        b.prefetchManager().activate();
        assertThat(memoryBytes(b)).as("control: nothing cached on B before the hint").isZero();

        b.prefetchManager().processHints(List.of(PrefetchHint.prefetchHint(id.hexString(), 50)));

        // The shared tier answers on its own thread, so the copy lands a moment after processHints returns.
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(memoryBytes(b)).isEqualTo(content.length));

        var record = b.metadataStore().getLifecycle(id).unwrap();

        assertThat(record.cacheOnly()).isTrue();
        assertThat(record.presentIn()).contains(TierLevel.MEMORY);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(b.prefetchManager().stats().warmed()).isEqualTo(1));
    }

    @Test
    void processHints_aBurstOnTheRealInstance_neverHasMoreFetchesRunningThanTheConfiguredCap() {
        var dht = new HoldableDHTClient();
        var nodeA = createNode(nodeADir, "node-a", dht, true);
        var nodeB = createNode(nodeBDir, "node-b", dht, true, 2);

        assertBurstIsCapped(nodeA, nodeB, ARTIFACTS, dht, 2);
    }

    @Test
    void createAll_synthesizedInstances_takeTheCapFromTheSuppliedDefaults() {
        var dht = new HoldableDHTClient();
        var nodeA = createNodeFromDefaults(nodeADir, "node-a", dht, 4);
        var nodeB = createNodeFromDefaults(nodeBDir, "node-b", dht, 2);

        assertBurstIsCapped(nodeA, nodeB, ARTIFACTS, dht, 2);
        assertBurstIsCapped(nodeA, nodeB, CONTENT, dht, 2);
    }

    /// Hands seven hints at once to `name`'s manager on node B while every fetch to the shared tier is held, so the
    /// fetches genuinely overlap: only `cap` may start, the rest are dropped and counted, and no more than `cap` ever
    /// reach the shared tier together.
    private static void assertBurstIsCapped(Map<String, StorageFactory.StorageSetup> nodeA,
                                            Map<String, StorageFactory.StorageSetup> nodeB,
                                            String name,
                                            HoldableDHTClient dht,
                                            int cap) {
        var ids = IntStream.range(0, 7)
                           .mapToObj(i -> nodeA.get(name).instance().put((name + "-burst-block-" + i).getBytes(StandardCharsets.UTF_8)).await().unwrap())
                           .toList();
        var manager = nodeB.get(name).prefetchManager();

        manager.activate();
        dht.holdGets();
        manager.processHints(ids.stream().map(id -> PrefetchHint.prefetchHint(id.hexString(), 50)).toList());

        var stats = manager.stats();

        assertThat(stats.started()).as("%s: prefetch_max_in_flight = %d", name, cap).isEqualTo(cap);
        assertThat(stats.droppedInFlight()).as("%s: the excess is dropped and counted, not queued", name).isEqualTo(ids.size() - cap);
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(dht.heldGets()).isEqualTo(cap));
        assertThat(dht.peakHeldGets()).as("%s: fetches that reached the shared tier at once", name).isLessThanOrEqualTo(cap);

        dht.releaseGets();
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(manager.stats().warmed()).isEqualTo(cap));
    }

    /// No `[storage.*]` section at all: both instances are synthesized from the `defaults` the caller supplies.
    private Map<String, StorageFactory.StorageSetup> createNodeFromDefaults(Path dir, String nodeId, DHTClient dht, int cap) {
        var base = StorageConfig.storageConfig();
        var defaults = StorageConfig.storageConfig(base.memoryMaxBytes(),
                                                   base.diskMaxBytes(),
                                                   dir.resolve("artifacts").toString(),
                                                   dir.resolve("artifacts-snapshots").toString(),
                                                   base.snapshotMutationThreshold(),
                                                   base.snapshotMaxInterval(),
                                                   base.snapshotRetentionCount(),
                                                   base.walPath(),
                                                   false,
                                                   true,
                                                   true,
                                                   cap);
        var setups = StorageFactory.createAll(Map.of(), nodeId, Option.some(dht), Option.none(), defaults)
                                   .onFailure(cause -> fail("createAll must succeed: " + cause.message()))
                                   .unwrap();

        setups.values().forEach(setup -> admitDht(dht, setup));

        return setups;
    }

    @Test
    void defaultCap_inTheConfigRecord_matchesTheManagersOwnDefault() {
        assertThat(StorageConfig.DEFAULT_PREFETCH_MAX_IN_FLIGHT).isEqualTo(PrefetchConfig.DEFAULT_MAX_IN_FLIGHT);
        assertThat(StorageConfig.storageConfig().prefetchMaxInFlight()).isEqualTo(PrefetchConfig.DEFAULT_MAX_IN_FLIGHT);
    }

    private static long memoryBytes(StorageFactory.StorageSetup setup) {
        return setup.instance()
                    .tierInfo()
                    .stream()
                    .filter(info -> info.level() == TierLevel.MEMORY)
                    .mapToLong(TierInfo::usedBytes)
                    .sum();
    }

    /// An in-memory DHT whose `get` can be held pending, so a burst of fetches genuinely overlaps.
    private static final class HoldableDHTClient implements DHTClient {
        private final InMemoryDHTClient delegate = new InMemoryDHTClient();
        private final List<Runnable> held = new CopyOnWriteArrayList<>();
        private final AtomicInteger peak = new AtomicInteger();
        private volatile boolean holding;

        void holdGets() {
            holding = true;
        }

        void releaseGets() {
            holding = false;
            held.forEach(Runnable::run);
            held.clear();
        }

        int heldGets() {
            return held.size();
        }

        int peakHeldGets() {
            return peak.get();
        }

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return holding ? holdGet(key) : delegate.get(key);
        }

        private Promise<Option<byte[]>> holdGet(byte[] key) {
            var pending = Promise.<Option<byte[]>> promise();

            held.add(() -> delegate.get(key).onResult(pending::resolve));
            peak.accumulateAndGet(held.size(), Math::max);

            return pending;
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            return delegate.put(key, value);
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return delegate.remove(key);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return delegate.exists(key);
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }
    }

    private static final class InMemoryDHTClient implements DHTClient {
        private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

        @Override
        public Promise<Option<byte[]>> get(byte[] key) {
            return Promise.success(option(store.get(keyString(key))).map(v -> copyOf(v, v.length)));
        }

        @Override
        public Promise<Unit> put(byte[] key, byte[] value) {
            store.put(keyString(key), copyOf(value, value.length));

            return Promise.success(unit());
        }

        @Override
        public Promise<Boolean> remove(byte[] key) {
            return Promise.success(store.remove(keyString(key)) != null);
        }

        @Override
        public Promise<Boolean> exists(byte[] key) {
            return Promise.success(store.containsKey(keyString(key)));
        }

        @Override
        public Partition partitionFor(byte[] key) {
            return null;
        }

        private static String keyString(byte[] key) {
            return new String(key, StandardCharsets.UTF_8);
        }
    }
}
