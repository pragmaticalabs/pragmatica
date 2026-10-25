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

import org.pragmatica.aether.config.StorageConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.MountClaim;
import org.pragmatica.storage.ReadinessState;
import org.pragmatica.storage.StorageInstance;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

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

    // --- items 1 and 2: operator-set disk_path refuses, node-placed tiers degrade ---
    @Test
    void operatorWrittenDiskPath_thatCannotOpen_failsTheBootNamingInstanceAndPath() {
        var unopenable = configAt(HermeticStorage.uncreatableRootIn(tempDir)).withDiskPathExplicit();

        var result = boot(Map.of("vault", unopenable), NODE);

        assertThat(result.isFailure()).as("an explicit disk_path that cannot open must refuse the boot").isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("vault").contains("disk_path").contains("refusing to boot"));
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

        AetherNode.signalReadyActivatingStorage(() -> {}, setups, () -> false);

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
