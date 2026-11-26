package org.pragmatica.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
    private final java.util.concurrent.atomic.AtomicInteger barriers = new java.util.concurrent.atomic.AtomicInteger();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(raised::add);
    private final DegradeReporter degrade = DegradeReporter.degradeReporter("vault");

    private long raisedOf(OperatorWarningCode code) {
        return raised.stream().filter(warning -> warning.code() == code).count();
    }

    private void bind() {
        degrade.bind(sink);
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
        drain();

        assertThat(transition).isFalse();
        assertThat(published()).as("a recovery with nothing to recover from is not published").isEmpty();
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
        drain();

        assertThat(published()).as("a write that fits is not an operator event, and neither is the absence of a refusal").isEmpty();
    }

    /// Returns once everything handed to the sink before the call has been published: the hand-off is one FIFO worker, so a marker
    /// warning that has come out of it was preceded by every earlier one. A "nothing was raised" assertion made after this is
    /// decided by the queue, not by a pause that happened to be long enough.
    private void drain() throws InterruptedException {
        var subject = "barrier-" + barriers.incrementAndGet();
        var barrier = DegradeReporter.degradeReporter(subject);

        barrier.bind(sink);
        barrier.degraded(OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED, "barrier");
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (raised.stream().noneMatch(warning -> warning.subject().equals(subject)) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(raised).extracting(OperatorWarning::subject).as("the barrier came out of the hand-off within 5 s").contains(subject);
    }

    private List<OperatorWarning> published() {
        return raised.stream().filter(warning -> !warning.subject().startsWith("barrier-")).toList();
    }

    /// A full tier that alternates large and small writes: the small ones fit, the large ones are refused. That is one episode
    /// (it ends when a write as large as the smallest refused one fits), so the operator gets one warning and no recovery.
    @Test
    void diskTier_alternatingLargeAndSmallPutsWhileFull_isOneEpisode() throws Exception {
        bind();
        var tier = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), 10).unwrap().open().unwrap();
        var held = BlockId.blockId("12345678".getBytes()).unwrap();
        var large = BlockId.blockId("abcdefgh".getBytes()).unwrap();
        tier.reportTo(degrade);

        tier.put(held, "12345678".getBytes()).await().unwrap();
        for (var i = 0; i < 3; i++) {
            assertThat(tier.put(large, "abcdefgh".getBytes()).await().isFailure()).as("large put %d does not fit", i).isTrue();
            var small = ("s" + i).getBytes();
            if (i == 0) {
                tier.put(BlockId.blockId(small).unwrap(), small).await().unwrap();
            } else {
                tier.delete(BlockId.blockId(("s" + (i - 1)).getBytes()).unwrap()).await().unwrap();
                tier.put(BlockId.blockId(small).unwrap(), small).await().unwrap();
            }
        }
        drain();

        assertThat(published().stream().filter(w -> w.code() == OperatorWarningCode.STORAGE_TIER_FULL).count())
            .as("one warning for the episode, however the writes alternate").isEqualTo(1);
        assertThat(published().stream().filter(w -> w.code() == OperatorWarningCode.STORAGE_TIER_WRITABLE).count())
            .as("a small write fitting does not end the episode of a large one").isZero();

        tier.delete(held).await().unwrap();
        tier.put(large, "abcdefgh".getBytes()).await().unwrap();
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (raisedOf(OperatorWarningCode.STORAGE_TIER_WRITABLE) < 1 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(raisedOf(OperatorWarningCode.STORAGE_TIER_WRITABLE)).as("a write as large as the refused one fits: the episode ends").isEqualTo(1);
    }

    /// The episode ends at the SMALLEST size refused in it, not the largest or the latest.
    @Test
    void diskTier_episodeEndsWhenAWriteAsLargeAsTheSmallestRefusedOneFits() throws Exception {
        bind();
        var tier = LocalDiskTier.localDiskTier(tempDir.resolve("blocks"), 10).unwrap().open().unwrap();
        var four = BlockId.blockId("1234".getBytes()).unwrap();
        tier.reportTo(degrade);

        tier.put(four, "1234".getBytes()).await().unwrap();
        assertThat(tier.put(BlockId.blockId("123456789".getBytes()).unwrap(), "123456789".getBytes()).await().isFailure()).isTrue();
        assertThat(tier.put(BlockId.blockId("abcdefgh".getBytes()).unwrap(), "abcdefgh".getBytes()).await().isFailure()).isTrue();
        tier.delete(four).await().unwrap();
        tier.put(BlockId.blockId("12".getBytes()).unwrap(), "12".getBytes()).await().unwrap();
        drain();
        assertThat(raisedOf(OperatorWarningCode.STORAGE_TIER_WRITABLE)).as("a 2-byte write is smaller than the smallest refused (8)").isZero();

        tier.put(BlockId.blockId("abcdefgh".getBytes()).unwrap(), "abcdefgh".getBytes()).await().unwrap();
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (raisedOf(OperatorWarningCode.STORAGE_TIER_WRITABLE) < 1 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertThat(raisedOf(OperatorWarningCode.STORAGE_TIER_FULL)).isEqualTo(1);
        assertThat(raisedOf(OperatorWarningCode.STORAGE_TIER_WRITABLE)).as("8 bytes fit: as large as the smallest refused").isEqualTo(1);

        // A second episode starts from nothing: what the first one refused is forgotten.
        tier.delete(BlockId.blockId("12".getBytes()).unwrap()).await().unwrap();
        assertThat(tier.put(BlockId.blockId("987654321".getBytes()).unwrap(), "987654321".getBytes()).await().isFailure()).isTrue();
        tier.delete(BlockId.blockId("abcdefgh".getBytes()).unwrap()).await().unwrap();
        tier.put(BlockId.blockId("ABCDEFGH".getBytes()).unwrap(), "ABCDEFGH".getBytes()).await().unwrap();
        drain();

        assertThat(raisedOf(OperatorWarningCode.STORAGE_TIER_FULL)).as("the second episode raised again").isEqualTo(2);
        assertThat(raisedOf(OperatorWarningCode.STORAGE_TIER_WRITABLE)).as("8 bytes do not end an episode that refused 9").isEqualTo(1);
    }

    /// Rounds run in chunks that fit the hand-off queue (256), and every chunk is drained before the next: a full queue drops
    /// events, which would read as a missing delivery.
    private static final int CHUNK = 100;
    private static final int CHUNKS = 20;

    /// Binding while a condition is being raised must deliver it once, whichever side wins the race.
    @Test
    void degraded_racingWithBind_deliversTheConditionExactlyOnce() throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        try {
            for (var chunk = 0; chunk < CHUNKS; chunk++) {
                var reporters = new ArrayList<DegradeReporter>();
                for (var i = 0; i < CHUNK; i++) {
                    reporters.add(DegradeReporter.degradeReporter("race-" + chunk + "-" + i));
                }
                var raising = pool.submit(() -> {
                    for (var reporter : reporters) {
                        barrier.await();
                        reporter.degraded(OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED, "cut");
                    }
                    return null;
                });
                var binding = pool.submit(() -> {
                    for (var reporter : reporters) {
                        barrier.await();
                        reporter.bind(sink);
                    }
                    return null;
                });

                raising.get();
                binding.get();
                drain();
            }
        } finally {
            pool.shutdown();
        }

        for (var chunk = 0; chunk < CHUNKS; chunk++) {
            for (var i = 0; i < CHUNK; i++) {
                var subject = "race-" + chunk + "-" + i;
                assertThat(raised.stream().filter(w -> w.subject().equals(subject)).count()).as("deliveries for %s", subject).isEqualTo(1);
            }
        }
    }

    /// A recovery raced against the condition it closes never reaches the sink first: raise and recover are serialised, so the
    /// recovery either finds nothing open (and is silent) or follows the warning it closes.
    @Test
    void recovered_racingWithDegraded_neverPublishesTheRecoveryBeforeItsWarning() throws Exception {
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);

        try {
            for (var chunk = 0; chunk < CHUNKS; chunk++) {
                var reporters = new ArrayList<DegradeReporter>();
                for (var i = 0; i < CHUNK; i++) {
                    var reporter = DegradeReporter.degradeReporter("order-" + chunk + "-" + i);
                    reporter.bind(sink);
                    reporters.add(reporter);
                }
                var raising = pool.submit(() -> {
                    for (var reporter : reporters) {
                        barrier.await();
                        reporter.degraded(OperatorWarningCode.STORAGE_DEMOTION_REFUSED, "refused");
                    }
                    return null;
                });
                var recovering = pool.submit(() -> {
                    for (var reporter : reporters) {
                        barrier.await();
                        reporter.recovered(OperatorWarningCode.STORAGE_DEMOTION_REFUSED, "resumed");
                    }
                    return null;
                });

                raising.get();
                recovering.get();
                drain();
            }
        } finally {
            pool.shutdown();
        }

        var seenWarning = new java.util.HashSet<String>();
        for (var warning : raised) {
            if (warning.code() == OperatorWarningCode.STORAGE_DEMOTION_REFUSED) {
                seenWarning.add(warning.subject());
            } else if (warning.code() == OperatorWarningCode.STORAGE_DEMOTION_RESUMED) {
                assertThat(seenWarning).as("recovery for %s published before its warning", warning.subject()).contains(warning.subject());
            }
        }
    }

    /// A code with no recovery stays open for the life of the instance: recovering it is a no-op and raising it again does nothing.
    @Test
    void recovered_ofACodeWithNoRecovery_keepsTheConditionOpen() throws Exception {
        bind();
        degrade.degraded(OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED, "cut");

        var transition = degrade.recovered(OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED, "no such recovery");
        var again = degrade.degraded(OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED, "cut");
        drain();

        assertThat(transition).isFalse();
        assertThat(degrade.isDegraded(OperatorWarningCode.STORAGE_MEMORY_TIER_CLAMPED)).as("still open").isTrue();
        assertThat(again).as("an open condition is not raised again").isFalse();
        assertThat(published()).hasSize(1);
    }

    /// A condition raised and recovered before the sink was bound is published neither way, so no recovery can precede its warning.
    @Test
    void recovered_beforeBind_publishesNeitherTheWarningNorTheRecovery() throws Exception {
        degrade.degraded(OperatorWarningCode.STORAGE_DEMOTION_REFUSED, "refused");
        degrade.recovered(OperatorWarningCode.STORAGE_DEMOTION_REFUSED, "resumed");

        bind();
        drain();

        assertThat(published()).isEmpty();
    }

    /// A block whose stored copy no longer hashes to its id fails the read; the operator hears of it once per instance, however many
    /// reads fail after it.
    @Test
    void integrityFailure_onRead_raisesOnceForTheInstance() throws Exception {
        var instance = StorageInstance.storageInstance("vault", List.of(new ScriptedTier(TierLevel.MEMORY, true)), WritePolicy.WRITE_THROUGH);

        instance.reportTo(degrade);
        bind();
        var id = instance.put("some block".getBytes()).await().unwrap();

        assertThat(instance.get(id).await().isFailure()).as("control: the tampered copy fails its integrity check").isTrue();
        assertThat(instance.get(id).await().isFailure()).isTrue();
        drain();

        assertThat(published().stream().filter(w -> w.code() == OperatorWarningCode.STORAGE_INTEGRITY_FAILED).count()).isEqualTo(1);
        assertThat(published().getFirst().subject()).isEqualTo("vault");
        assertThat(published().getFirst().message()).contains("MEMORY").contains(id.toString());
    }

    @Test
    void integrityFailure_beforeTheInstanceHasAReporter_isNotPublished() throws Exception {
        var instance = StorageInstance.storageInstance("vault", List.of(new ScriptedTier(TierLevel.MEMORY, true)), WritePolicy.WRITE_THROUGH);

        bind();
        var id = instance.put("some block".getBytes()).await().unwrap();

        assertThat(instance.get(id).await().isFailure()).isTrue();
        drain();

        assertThat(published()).as("only the reporter the node handed the instance publishes").isEmpty();
    }

    /// A write-behind flush that fails raises once for the episode; the first flush that lands after it closes the episode.
    @Test
    void writeBehindFlush_failingThenSucceeding_raisesOnceThenItsRecovery() throws Exception {
        var slow = new ScriptedTier(TierLevel.REMOTE, false);
        var instance = StorageInstance.storageInstance("vault",
                                                       List.of(MemoryTier.memoryTier(1 << 20), slow),
                                                       WritePolicy.WRITE_BEHIND);

        try {
            instance.reportTo(degrade);
            bind();
            slow.failing = true;

            instance.put("first".getBytes()).await().unwrap();
            awaitRaisedOf(OperatorWarningCode.STORAGE_WRITE_BEHIND_FLUSH_FAILED, 1);
            instance.put("second".getBytes()).await().unwrap();
            awaitFlushAttempts(slow, 2);
            slow.failing = false;
            instance.put("third".getBytes()).await().unwrap();
            awaitRaisedOf(OperatorWarningCode.STORAGE_WRITE_BEHIND_FLUSHED, 1);
            drain();

            assertThat(raisedOf(OperatorWarningCode.STORAGE_WRITE_BEHIND_FLUSH_FAILED)).as("one warning for the episode").isEqualTo(1);
            assertThat(raisedOf(OperatorWarningCode.STORAGE_WRITE_BEHIND_FLUSHED)).isEqualTo(1);
        } finally {
            instance.shutdown();
        }
    }

    private void awaitRaisedOf(OperatorWarningCode code, int count) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (raisedOf(code) < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(raisedOf(code)).as("%s warnings handed to the sink within 5 s", code).isGreaterThanOrEqualTo(count);
    }

    private static void awaitFlushAttempts(ScriptedTier tier, int count) throws InterruptedException {
        var deadline = System.nanoTime() + 5_000_000_000L;

        while (tier.puts.get() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(tier.puts.get()).as("flush attempts reached the slow tier within 5 s").isGreaterThanOrEqualTo(count);
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
    /// A tier that keeps what it is given, optionally hands back a different block than it stored (a corrupt copy), and can be
    /// told to fail its puts.
    private static final class ScriptedTier implements StorageTier {
        final java.util.concurrent.ConcurrentHashMap<BlockId, byte[]> blocks = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.concurrent.atomic.AtomicInteger puts = new java.util.concurrent.atomic.AtomicInteger();
        final TierLevel level;
        final boolean tamper;
        volatile boolean failing;

        ScriptedTier(TierLevel level, boolean tamper) {
            this.level = level;
            this.tamper = tamper;
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return Promise.success(Option.option(blocks.get(id)).map(bytes -> tamper ? flipped(bytes) : bytes));
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            puts.incrementAndGet();

            if (failing) {
                return Causes.cause("scripted put failure").promise();
            }

            blocks.put(id, content);

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            blocks.remove(id);

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return Promise.success(blocks.containsKey(id));
        }

        @Override
        public TierLevel level() {
            return level;
        }

        @Override
        public long usedBytes() {
            return blocks.values().stream().mapToLong(bytes -> bytes.length).sum();
        }

        @Override
        public long maxBytes() {
            return 1L << 30;
        }

        private static byte[] flipped(byte[] bytes) {
            var copy = bytes.clone();

            copy[0] ^= 0x01;

            return copy;
        }
    }
}
