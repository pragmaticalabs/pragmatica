// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.aether.config.StreamingConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.MountClaim;
import org.pragmatica.testlogging.LogCapture;
import org.pragmatica.storage.ReadinessState;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// A0b storage boot and degrade hygiene: what `StorageFactory` does when a disk tier cannot open (an operator-written
/// `disk_path` refuses the boot, a tier the node placed degrades loudly), when a second node meets a held mount, when the
/// memory tiers do not fit the heap, and when the node reports ready.
///
/// Hermetic tests (CTO ruling 2): no production key exists for tests. [HermeticStorage] builds its configurations
/// programmatically, and `diskPathExplicit` is set only by the config loader, so those configurations take the degrade path
/// exactly as before; the refusal is exercised with a configuration marked as the loader would mark it.
class StorageBootHygieneTest {
    private static final String NODE = "node-a0b";
    private static final long MIB = 1024L * 1024;

    @TempDir
    Path tempDir;

    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();

    private Result<Map<String, StorageFactory.StorageSetup>> boot(Map<String, StorageConfig> configs, String nodeId) {
        return StorageFactory.createAll(configs,
                                        nodeId,
                                        Option.none(),
                                        Option.none(),
                                        HermeticStorage.synthesisDefaultsIn(tempDir));
    }

    private StorageConfig configAt(Path root) {
        return HermeticStorage.storageConfigAt(root, false);
    }

    private long raisedOf(OperatorWarningCode code, String subject) {
        return raised.stream()
                     .filter(warning -> warning.code() == code && warning.subject().equals(subject))
                     .count();
    }

    private void awaitRaised(int count) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (raised.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(raised).as("operator warnings handed to the sink within 5 s").hasSizeGreaterThanOrEqualTo(count);
    }

    /// Waits for `count` warnings of `code` for `subject`: a sink that was bound late is first offered the conditions already
    /// open, so the size of everything raised says nothing about whether THIS warning has arrived.
    private void awaitRaisedOf(OperatorWarningCode code, String subject, int count) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (raisedOf(code, subject) < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(raisedOf(code, subject)).as("%s warnings for %s handed to the sink within 5 s", code, subject).isGreaterThanOrEqualTo(count);
    }

    // --- items 1 and 2: operator-set disk_path refuses, node-placed tiers degrade ---
    @Test
    void operatorWrittenDiskPath_thatCannotOpen_failsTheBootNamingInstanceAndPath() {
        // A sibling of the synthesized defaults' root, so the refusal cannot come from StoragePathsOverlap.
        var unopenable = configAt(HermeticStorage.uncreatableRootIn(tempDir).resolveSibling("vault-root")).withDiskPathExplicit();

        var result = boot(Map.of("vault", unopenable), NODE);

        assertThat(result.isFailure()).as("an explicit disk_path that cannot open must refuse the boot").isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("vault").contains("disk_path").contains("cannot be opened").doesNotContain("overlap"));
    }

    @Test
    void synthesizedDefaults_thatCannotOpen_degradeWithOneWarningPerInstanceAndAGaugeState() throws Exception {
        var setups = boot(Map.of(), NODE).unwrap();

        setups.get("artifacts").degrade().bind(OperatorWarningSink.handingOffTo(raised::add));
        setups.get("content").degrade().bind(OperatorWarningSink.handingOffTo(raised::add));
        awaitRaised(2);

        assertThat(setups).containsKeys("artifacts", "content");
        assertThat(setups.get("artifacts").degrade().isDegraded(OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE)).isTrue();
        assertThat(setups.get("content").degrade().isDegraded(OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE)).isTrue();
        assertThat(raisedOf(OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE, "artifacts")).isEqualTo(1);
        assertThat(raisedOf(OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE, "content")).isEqualTo(1);
    }

    /// The node binds every instance's reporter with one call once its sink exists; the conditions raised while storage was
    /// being built reach that sink then, once per instance.
    @Test
    void bindOperatorWarnings_offersEveryInstancesBootConditionsToTheNodesSink() throws Exception {
        var setups = boot(Map.of(), NODE).unwrap();

        StorageFactory.bindOperatorWarnings(setups, OperatorWarningSink.handingOffTo(raised::add));
        awaitRaised(2);

        assertThat(raisedOf(OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE, "artifacts")).isEqualTo(1);
        assertThat(raisedOf(OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE, "content")).isEqualTo(1);
    }

    /// The hermetic fixture: a programmatically built configuration whose path cannot open still degrades, so the node tests
    /// that root storage under a regular file keep booting without any test-only configuration key.
    @Test
    void hermeticStorage_programmaticallyBuiltConfiguration_stillDegrades() {
        var setups = boot(HermeticStorage.nodeStorageIn(tempDir, false), NODE).unwrap();

        assertThat(setups.get("artifacts").degrade().isDegraded(OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE)).isTrue();
    }

    // --- item 7: streams ---
    @Test
    void streamsDiskTier_thatCannotOpen_raisesItsOperatorWarningOnce() throws Exception {
        var streamsDir = HermeticStorage.uncreatableRootIn(tempDir).resolve("streams");
        var setups = StorageFactory.createAll(HermeticStorage.nodeStorageIn(tempDir, false),
                                              NODE,
                                              Option.none(),
                                              Option.none(),
                                              new StorageFactory.StreamSetupRequest(Option.none(),
                                                                                    streamsDir,
                                                                                    NODE,
                                                                                    Option.none())).unwrap();

        setups.get("streams").degrade().bind(OperatorWarningSink.handingOffTo(raised::add));
        awaitRaised(1);

        assertThat(raisedOf(OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE, "streams")).isEqualTo(1);
    }

    /// The snapshot manager of a built instance reports through the instance's own reporter: a snapshot that cannot be written
    /// (the hermetic snapshot path sits under a regular file) opens the write-failed condition on that instance.
    @Test
    void builtInstance_snapshotWriteFailure_opensItsConditionOnTheInstancesReporter() {
        var setups = boot(Map.of(), NODE).unwrap();
        var artifacts = setups.get("artifacts");

        artifacts.snapshotManager().forceSnapshot();

        assertThat(artifacts.degrade().isDegraded(OperatorWarningCode.STORAGE_SNAPSHOT_WRITE_FAILED)).isTrue();
    }

    /// The streams instance is assembled by its own builder: its snapshot manager reports through the streams reporter too (the
    /// streams snapshot directory sits under a regular file here, so no snapshot can be written).
    @Test
    void streamsInstance_snapshotWriteFailure_opensItsConditionOnTheStreamsReporter() {
        var request = new StorageFactory.StreamSetupRequest(Option.none(),
                                                            HermeticStorage.uncreatableRootIn(tempDir).resolve("streams"),
                                                            NODE,
                                                            Option.none());
        var streams = StorageFactory.createAll(HermeticStorage.nodeStorageIn(tempDir, false), NODE, Option.none(), Option.none(), request).unwrap()
                                    .get("streams");

        streams.snapshotManager().forceSnapshot();

        assertThat(streams.degrade().isDegraded(OperatorWarningCode.STORAGE_SNAPSHOT_WRITE_FAILED)).isTrue();
    }

    /// Both builders hand the instance's reporter to the demotion manager; the manager's own transition is tested in the
    /// storage module with a tier that is full, which a built instance cannot be put in without 10 GiB of writes.
    @Test
    void factory_handsTheInstancesReporterToEveryDemotionManager() throws Exception {
        var source = java.nio.file.Files.readString(Path.of("src/main/java/org/pragmatica/aether/node/StorageFactory.java"))
                                         .replaceAll("\\s+", "");

        assertThat(source.split(java.util.regex.Pattern.quote("DemotionManager.demotionManager(tiers,metadataStore,DemotionConfig.demotionConfig(),degrade)"), -1))
            .as("one per instance builder (configured instances, streams)").hasSize(3);
    }

    // --- item 7 remainder: tier full, maintenance activation, torn WAL tail ---
    /// A full disk tier of a built instance reaches the operator through the instance's own reporter: the wiring from the
    /// factory to the tier, which the storage module's test cannot see.
    @Test
    void builtInstance_fullDiskTier_opensTierFullOnTheInstancesReporter() {
        var config = StorageConfig.storageConfig(8 * MIB, 16, tempDir.resolve("disk").toString(), tempDir.resolve("snap").toString(), 1000, "60s", 5, "", false);
        var setup = boot(Map.of("vault", config), NODE).unwrap().get("vault");

        var refused = setup.instance().put(new byte[64]).await();

        assertThat(refused.isFailure()).as("64 bytes do not fit a 16 byte disk tier").isTrue();
        assertThat(setup.degrade().isDegraded(OperatorWarningCode.STORAGE_TIER_FULL)).isTrue();
    }

    @Test
    void streamsInstance_fullDiskTier_opensTierFullOnTheStreamsReporter() {
        var request = new StorageFactory.StreamSetupRequest(Option.none(), tempDir.resolve("streams"), NODE, Option.none(), 16, Option.none());
        var streams = StorageFactory.createAll(HermeticStorage.nodeStorageIn(tempDir, false), NODE, Option.none(), Option.none(), request).unwrap()
                                    .get("streams");

        var refused = streams.instance().put(new byte[64]).await();

        assertThat(refused.isFailure()).isTrue();
        assertThat(streams.degrade().isDegraded(OperatorWarningCode.STORAGE_TIER_FULL)).isTrue();
    }

    /// The streams instance has ONE reporter: the one its DHT marker check carries is the one its other conditions use, or a
    /// marker that cannot be verified would be raised under a subject nothing else of the instance shares.
    @Test
    void streamsInstance_hasOneReporter_theOneItsDhtMarkerCheckCarries() {
        var request = new StorageFactory.StreamSetupRequest(Option.some(new SilentDhtClient()), tempDir.resolve("streams"), NODE, Option.none());
        var streams = StorageFactory.createAll(HermeticStorage.nodeStorageIn(tempDir, false), NODE, Option.none(), Option.none(), request).unwrap()
                                    .get("streams");

        assertThat(streams.dhtMarkerCheck().isPresent()).as("a streams instance with a DHT client carries a marker check").isTrue();
        streams.dhtMarkerCheck().onPresent(check -> assertThat(check.degrade()).isSameAs(streams.degrade()));
    }

    private static final class SilentDhtClient implements org.pragmatica.dht.DHTClient {
        @Override
        public org.pragmatica.lang.Promise<Option<byte[]>> get(byte[] key) {
            return org.pragmatica.lang.Promise.success(Option.none());
        }

        @Override
        public org.pragmatica.lang.Promise<org.pragmatica.lang.Unit> put(byte[] key, byte[] value) {
            return org.pragmatica.lang.Promise.success(org.pragmatica.lang.Unit.unit());
        }

        @Override
        public org.pragmatica.lang.Promise<Boolean> remove(byte[] key) {
            return org.pragmatica.lang.Promise.success(false);
        }

        @Override
        public org.pragmatica.lang.Promise<Boolean> exists(byte[] key) {
            return org.pragmatica.lang.Promise.success(false);
        }

        @Override
        public org.pragmatica.dht.Partition partitionFor(byte[] key) {
            return null;
        }
    }

    /// A manager that cannot start is one operator warning for its instance, however often the ready signal repeats, and the
    /// recovery when a later activation starts it. The other two managers are still attempted.
    @Test
    void activateNodeMaintenance_failingManager_raisesOnceThenRecoversOnALaterSuccess() throws Exception {
        var built = boot(Map.of(), NODE).unwrap().get("artifacts");
        var broken = new AtomicBoolean(true);
        var setup = new StorageFactory.StorageSetup(built.name(),
                                                    built.instance(),
                                                    built.snapshotManager(),
                                                    built.readinessGate(),
                                                    built.metadataStore(),
                                                    new FlakyDemotion(built.demotionManager(), broken),
                                                    built.garbageCollector(),
                                                    built.dhtMarkerCheck(),
                                                    built.committedDiskMarker(),
                                                    built.promotionManager(),
                                                    built.prefetchManager(),
                                                    built.degrade());
        built.degrade().bind(OperatorWarningSink.handingOffTo(raised::add));

        var first = StorageFactory.activateNodeMaintenance(Map.of("artifacts", setup));
        var gcActiveAfterTheFailure = built.garbageCollector().isActive();
        StorageFactory.activateNodeMaintenance(Map.of("artifacts", setup));
        awaitRaisedOf(OperatorWarningCode.STORAGE_MAINTENANCE_NOT_STARTED, "artifacts", 1);
        broken.set(false);
        var recovered = StorageFactory.activateNodeMaintenance(Map.of("artifacts", setup));
        awaitRaisedOf(OperatorWarningCode.STORAGE_MAINTENANCE_STARTED, "artifacts", 1);

        assertThat(first.isFailure()).isTrue();
        assertThat(recovered.isSuccess()).isTrue();
        assertThat(gcActiveAfterTheFailure).as("a failed demotion activation must not leave GC unattempted").isTrue();
        assertThat(raisedOf(OperatorWarningCode.STORAGE_MAINTENANCE_NOT_STARTED, "artifacts")).isEqualTo(1);
        assertThat(raisedOf(OperatorWarningCode.STORAGE_MAINTENANCE_STARTED, "artifacts")).isEqualTo(1);
    }

    @Test
    void activateNodeMaintenance_everyManagerStarting_raisesNothing() throws Exception {
        var setups = boot(Map.of(), NODE).unwrap();
        setups.get("artifacts").degrade().bind(OperatorWarningSink.handingOffTo(raised::add));
        setups.get("content").degrade().bind(OperatorWarningSink.handingOffTo(raised::add));
        awaitRaised(2);
        raised.clear();

        StorageFactory.activateNodeMaintenance(setups).onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
        Thread.sleep(100);

        assertThat(raised).isEmpty();
    }

    /// A torn tail cut when the streams instance opens a partition log reaches the operator through the instance's reporter, once.
    @Test
    void streamsInstance_openingATornLog_raisesTheWalTailCutWarningOnce() throws Exception {
        var logRoot = tempDir.resolve("logs");
        var file = logRoot.resolve("orders").resolve("0.wal");
        Files.createDirectories(file.getParent());
        var wal = org.pragmatica.storage.AppendLog.open(file).unwrap();
        wal.append(0, "e0".getBytes(), 1L).await().unwrap();
        wal.close();
        Files.write(file, new byte[] {0, 0, 0, 40, 1, 2, 3}, java.nio.file.StandardOpenOption.APPEND);
        var request = new StorageFactory.StreamSetupRequest(Option.none(), tempDir.resolve("streams"), NODE, Option.none(),
                                                            StreamingConfig.DERIVE_SEGMENT_DISK_MAX_BYTES, Option.some(logRoot));
        var streams = StorageFactory.createAll(HermeticStorage.nodeStorageIn(tempDir, false), NODE, Option.none(), Option.none(), request).unwrap()
                                    .get("streams");
        streams.degrade().bind(OperatorWarningSink.handingOffTo(raised::add));

        streams.instance().openLog("orders/0").unwrap().close();
        streams.instance().openLog("orders/0").unwrap().close();
        awaitRaisedOf(OperatorWarningCode.STORAGE_WAL_TAIL_CUT, "streams", 1);
        Thread.sleep(100);

        assertThat(raisedOf(OperatorWarningCode.STORAGE_WAL_TAIL_CUT, "streams")).isEqualTo(1);
        assertThat(raised.getFirst().message()).contains("torn tail").contains("0.wal");
    }

    /// A demotion manager whose activation fails while `broken` is set; everything else is the real one's.
    private record FlakyDemotion(org.pragmatica.storage.DemotionManager real, AtomicBoolean broken) implements org.pragmatica.storage.DemotionManager {
        @Override
        public int demote() {
            return real.demote();
        }

        @Override
        public DemotionStats stats() {
            return real.stats();
        }

        @Override
        public Result<org.pragmatica.lang.Unit> activate() {
            return broken.get() ? org.pragmatica.lang.utils.Causes.cause("demotion cannot start").result() : real.activate();
        }

        @Override
        public Result<org.pragmatica.lang.Unit> deactivate() {
            return real.deactivate();
        }

        @Override
        public boolean isActive() {
            return real.isActive();
        }
    }

    // --- item 5: mount claim at boot ---
    @Test
    void secondNodeOnTheSameMount_refusesBoot_andIsAdmittedAfterTheFirstShutsDown() {
        var shared = Map.of("vault", configAt(tempDir.resolve("mount")));
        var first = boot(shared, "node-1").unwrap();

        var second = boot(shared, "node-2");

        assertThat(second.isFailure()).as("two nodes must not share one mount").isTrue();
        second.onFailure(cause -> assertThat(cause.message()).contains("node-1").contains("already claimed"));
        first.values().forEach(setup -> setup.instance().shutdown());
        assertThat(boot(shared, "node-2").isSuccess()).as("a graceful shutdown releases the claim").isTrue();
    }

    /// A boot refused for one instance must not leave the claims of the instances built before it held by this still-living process.
    @Test
    void bootRefusedForALaterInstance_releasesTheClaimsOfTheEarlierOnes() {
        var other = boot(Map.of("held", configAt(tempDir.resolve("held"))), "node-other").unwrap();
        var configs = new java.util.LinkedHashMap<String, StorageConfig>();
        configs.put("first", configAt(tempDir.resolve("first")));
        configs.put("second", configAt(tempDir.resolve("held")));

        var refused = boot(configs, "node-2");

        assertThat(refused.isFailure()).as("the second instance meets the other node's claim").isTrue();
        assertThat(boot(Map.of("again", configAt(tempDir.resolve("first"))), "node-3").isSuccess())
            .as("the first instance's mount was given back when the boot was refused").isTrue();
        other.values().forEach(setup -> setup.instance().shutdown());
    }

    @Test
    void claimOfAProcessThatIsGone_isReclaimedAtBoot() throws Exception {
        var mount = tempDir.resolve("mount");
        Files.createDirectories(mount);
        var gone = new ProcessBuilder("true").start();
        gone.waitFor();
        Files.writeString(mount.resolve(MountClaim.CLAIM_FILE),
                          "node=node-dead\npid=" + gone.pid() + "\nstarted="
                          + gone.toHandle().info().startInstant().map(java.time.Instant::toEpochMilli).orElse(0L) + "\n");

        var result = boot(Map.of("vault", configAt(mount)), "node-2");

        assertThat(result.isSuccess()).as("a stale claim must not refuse the boot: %s", result).isTrue();
    }

    // --- item 6: readiness gate ---
    @Test
    void readyNode_opensTheWriteSideOfEveryInstance() {
        var setups = boot(Map.of(), NODE).unwrap();
        var gate = setups.get("artifacts").readinessGate();
        assertThat(gate.isWriteReady()).as("before the node reports ready").isFalse();
        assertThat(gate.state()).isEqualTo(ReadinessState.SNAPSHOT_LOADED);

        AetherNode.signalReadyActivatingStorage(() -> {}, setups, () -> false);

        assertThat(gate.isWriteReady()).as("after the node reports ready").isTrue();
        assertThat(setups.get("content").readinessGate().isWriteReady()).isTrue();
        assertThat(gate.state()).isEqualTo(ReadinessState.READY);
    }

    @Test
    void readySignalFiringAgain_doesNotReopenOrWarn() {
        var setups = boot(Map.of(), NODE).unwrap();
        AetherNode.signalReadyActivatingStorage(() -> {}, setups, () -> false);

        try (var capture = LogCapture.capture("org.pragmatica.storage.DefaultStorageReadinessGate", Level.ALL, true)) {
            AetherNode.signalReadyActivatingStorage(() -> {}, setups, () -> false);

            assertThat(capture.events()).as("a repeated ready signal must not reach a gate that already synced").isEmpty();
        }
        assertThat(setups.get("artifacts").readinessGate().state()).isEqualTo(ReadinessState.READY);
    }

    // --- item 8: memory tier clamp ---
    @Test
    void memoryTiers_underA256MibHeap_stayWithinTheHeapFraction() throws Exception {
        var heap = 256 * MIB;
        var setups = StorageFactory.createAll(Map.of(),
                                              NODE,
                                              Option.none(),
                                              Option.none(),
                                              HermeticStorage.synthesisDefaultsIn(tempDir),
                                              heap).unwrap();
        var total = setups.values().stream().mapToLong(StorageBootHygieneTest::memoryBytes).sum();

        setups.get("artifacts").degrade().bind(OperatorWarningSink.handingOffTo(raised::add));
        setups.get("content").degrade().bind(OperatorWarningSink.handingOffTo(raised::add));
        awaitRaised(4);

        assertThat(total).as("memory tiers of all instances").isLessThanOrEqualTo((long) (heap * StorageFactory.MEMORY_TIER_HEAP_FRACTION));
        assertThat(raisedOf(OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED, "artifacts")).isEqualTo(1);
        assertThat(raisedOf(OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED, "content")).isEqualTo(1);
    }

    @Test
    void memoryTiers_thatFit_areLeftAlone() {
        var setups = StorageFactory.createAll(Map.of(),
                                              NODE,
                                              Option.none(),
                                              Option.none(),
                                              HermeticStorage.synthesisDefaultsIn(tempDir),
                                              16 * 1024 * MIB).unwrap();

        assertThat(setups.get("artifacts").degrade().isDegraded(OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED)).isFalse();
        assertThat(memoryBytes(setups.get("artifacts"))).isEqualTo(StorageConfig.storageConfig().memoryMaxBytes());
    }

    @Test
    void clamp_cutsProportionally_andNeverBelowTheFloor() {
        var big = configAt(tempDir).withMemoryMaxBytes(300 * MIB);
        var small = configAt(tempDir).withMemoryMaxBytes(100 * MIB);

        var clamps = StorageFactory.memoryClamps(Map.of("big", big, "small", small), 16 * MIB, 512 * MIB);

        var budget = (long) (512 * MIB * StorageFactory.MEMORY_TIER_HEAP_FRACTION) - 16 * MIB;
        assertThat(clamps.get("big").effectiveBytes() + clamps.get("small").effectiveBytes()).isLessThanOrEqualTo(budget);
        assertThat(clamps.get("big").effectiveBytes()).as("cut in proportion to what each asked for")
                                                      .isBetween(3 * clamps.get("small").effectiveBytes() - 3, 3 * clamps.get("small").effectiveBytes() + 3);
        var starved = StorageFactory.memoryClamps(Map.of("big", big), 16 * MIB, 8 * MIB);
        assertThat(starved.get("big").effectiveBytes()).isEqualTo(StorageFactory.MIN_MEMORY_TIER_BYTES);
    }

    private static long memoryBytes(StorageFactory.StorageSetup setup) {
        return setup.instance()
                    .tierInfo()
                    .stream()
                    .filter(tier -> tier.level() == org.pragmatica.storage.TierLevel.MEMORY)
                    .mapToLong(StorageInstance.TierInfo::maxBytes)
                    .sum();
    }
}
