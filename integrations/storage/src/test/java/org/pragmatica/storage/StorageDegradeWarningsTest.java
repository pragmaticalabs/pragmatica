package org.pragmatica.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.DemotionConfig.demotionConfig;
import static org.pragmatica.storage.InMemoryMetadataStore.inMemoryMetadataStore;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;

/// A0b: every storage degrade path raises an operator warning once, on the transition, and the paired recovery code when it
/// ends. The sink here records what the node's event layer would be handed, so "exactly one" is counted, not assumed.
class StorageDegradeWarningsTest {
    @TempDir
    Path tempDir;

    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private final DegradeReporter degrade = DegradeReporter.degradeReporter("vault");

    private long raisedOf(OperatorWarningCode code) {
        return raised.stream().filter(warning -> warning.code() == code).count();
    }

    private void bind() {
        degrade.bind(OperatorWarningSink.handingOffTo(raised::add));
    }

    @Test
    void degraded_sameConditionTwice_raisesOnce() throws Exception {
        bind();

        degrade.degraded(OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE, "no disk");
        degrade.degraded(OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE, "no disk");
        awaitRaised(1);

        assertThat(raisedOf(OperatorWarningCode.STORAGE_DISK_TIER_UNAVAILABLE)).isEqualTo(1);
        assertThat(raised.getFirst().subject()).isEqualTo("vault");
    }

    @Test
    void recovered_withoutAnOpenCondition_raisesNothing() throws Exception {
        bind();

        var transition = degrade.recovered(OperatorWarningCode.STORAGE_DEMOTION_REFUSED, "never refused");
        Thread.sleep(100);

        assertThat(transition).isFalse();
        assertThat(raised).as("a recovery with nothing to recover from is not published").isEmpty();
    }

    @Test
    void bind_offersAConditionRaisedBeforeTheSinkExisted_once() throws Exception {
        degrade.degraded(OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED, "cut");

        bind();
        awaitRaised(1);

        assertThat(raisedOf(OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED)).isEqualTo(1);
    }

    @Test
    void demotion_refusedEpisode_raisesOnceThenItsRecovery() throws Exception {
        bind();
        var durable = new FullDurableTier();
        var manager = new DefaultDemotionManager(List.of(durable, MemoryTier.memoryTier(1000, TierLevel.REMOTE)),
                                                  inMemoryMetadataStore("demo"),
                                                  demotionConfig(DemotionStrategy.LRU, 0.5, 0.25, 10),
                                                  degrade);
        manager.activate();

        manager.demote();
        manager.demote();
        awaitRaised(1);
        durable.used = 0;
        manager.demote();
        awaitRaised(2);

        assertThat(raisedOf(OperatorWarningCode.STORAGE_DEMOTION_REFUSED)).as("one warning for the episode").isEqualTo(1);
        assertThat(raisedOf(OperatorWarningCode.STORAGE_DEMOTION_RESUMED)).as("one recovery when it ends").isEqualTo(1);
    }

    @Test
    void snapshot_writeFailure_raisesOnceThenRecovers() throws Exception {
        bind();
        var failing = new boolean[] {true};
        var manager = new DefaultSnapshotManager(inMemoryMetadataStore("snap"),
                                                  snapshotConfig(tempDir, 100, 600_000, 5, "node-1"),
                                                  _ -> failing[0]
                                                       ? Causes.cause("disk full").<Unit> result()
                                                       : Result.unitResult(),
                                                  degrade);

        manager.forceSnapshot();
        manager.forceSnapshot();
        awaitRaised(1);
        failing[0] = false;
        manager.forceSnapshot();
        awaitRaised(2);

        assertThat(raisedOf(OperatorWarningCode.STORAGE_SNAPSHOT_WRITE_FAILED)).isEqualTo(1);
        assertThat(raisedOf(OperatorWarningCode.STORAGE_SNAPSHOT_WRITE_RECOVERED)).isEqualTo(1);
    }

    @Test
    void snapshot_restoredFromAnOlderRetainedFile_raisesOnce() throws IOException, InterruptedException {
        bind();
        var store = inMemoryMetadataStore("snap");
        var config = snapshotConfig(tempDir, 100, 600_000, 5, "node-1");
        var writer = SnapshotManager.snapshotManager(store, config);
        writer.forceSnapshot();
        store.createLifecycle(BlockLifecycle.blockLifecycle(BlockId.blockId("x".getBytes()).unwrap(), TierLevel.MEMORY));
        writer.forceSnapshot();
        var newest = tempDir.resolve(Files.readString(tempDir.resolve("LATEST")).trim());
        Files.writeString(newest, "torn");

        var restored = SnapshotManager.snapshotManager(inMemoryMetadataStore("snap"), config, degrade).restoreFromLatest();
        awaitRaised(1);

        assertThat(restored.unwrap().isPresent()).as("the older snapshot restores").isTrue();
        assertThat(raisedOf(OperatorWarningCode.STORAGE_SNAPSHOT_FELL_BACK)).isEqualTo(1);
    }

    @Test
    void diskTier_full_raisesOnceForTheEpisodeThenRecoversWhenAWriteFitsAgain() throws Exception {
        bind();
        var tier = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), 10).unwrap().open().unwrap();
        var first = BlockId.blockId("12345678".getBytes()).unwrap();
        var second = BlockId.blockId("abcdefgh".getBytes()).unwrap();
        tier.reportTo(degrade);

        tier.put(first, "12345678".getBytes()).await().unwrap();
        var refusedOnce = tier.put(second, "abcdefgh".getBytes()).await();
        var refusedTwice = tier.put(second, "abcdefgh".getBytes()).await();
        awaitRaised(1);
        tier.delete(first).await().unwrap();
        tier.put(second, "abcdefgh".getBytes()).await().unwrap();
        awaitRaised(2);

        refusedOnce.onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("the second block does not fit"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.TierFull.class));
        assertThat(refusedTwice.isFailure()).isTrue();
        assertThat(raisedOf(OperatorWarningCode.STORAGE_TIER_FULL)).as("one warning for the episode, not one per refused put").isEqualTo(1);
        assertThat(raisedOf(OperatorWarningCode.STORAGE_TIER_WRITABLE)).as("one recovery when a write fits again").isEqualTo(1);
        assertThat(raised.getFirst().subject()).isEqualTo("vault");
    }

    /// An encrypted instance's disk tier is the wrapper: the full tier reports through it, or an encrypted node would stay silent.
    @Test
    void encryptedDiskTier_full_reportsThroughTheWrapper() throws Exception {
        bind();
        var key = new byte[32];
        new java.security.SecureRandom().nextBytes(key);
        var keyring = EncryptionKeyring.encryptionKeyring(java.util.Map.of("k", BlockEncryptor.aesGcm(key, "k").unwrap()), "k").unwrap();
        var disk = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), 8).unwrap().open().unwrap();
        var wrapper = EncryptingStorageTier.armLocalDisk(disk, tempDir.resolve("blocks"), keyring).unwrap().tier();
        wrapper.reportTo(degrade);

        var refused = wrapper.put(BlockId.blockId("0123456789".getBytes()).unwrap(), "0123456789".getBytes()).await();
        awaitRaised(1);

        assertThat(refused.isFailure()).isTrue();
        assertThat(raisedOf(OperatorWarningCode.STORAGE_TIER_FULL)).isEqualTo(1);
    }

    @Test
    void diskTier_withRoom_raisesNothing() throws Exception {
        bind();
        var tier = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), 1 << 20).unwrap().open().unwrap();
        tier.reportTo(degrade);

        tier.put(BlockId.blockId("fits".getBytes()).unwrap(), "fits".getBytes()).await().unwrap();
        Thread.sleep(100);

        assertThat(raised).as("a write that fits is not an operator event, and neither is the absence of a refusal").isEmpty();
    }

    private void awaitRaised(int count) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (raised.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(raised).as("warnings handed to the sink within 5 s").hasSizeGreaterThanOrEqualTo(count);
    }

    /// A durable tier at 90% with nothing below it to demote into.
    private static final class FullDurableTier implements StorageTier {
        volatile long used = 90;

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return Promise.success(Option.none());
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return Promise.success(false);
        }

        @Override
        public TierLevel level() {
            return TierLevel.LOCAL_DISK;
        }

        @Override
        public long usedBytes() {
            return used;
        }

        @Override
        public long maxBytes() {
            return 100;
        }

        @Override
        public boolean isDurable() {
            return true;
        }
    }
}
