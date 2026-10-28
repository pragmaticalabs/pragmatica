package org.pragmatica.storage;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.storage.AnchorTierTest.await;
import static org.pragmatica.storage.AnchorTierTest.causeOf;
import static org.pragmatica.storage.AnchorTierTest.nameOf;
import static org.pragmatica.storage.AnchorTierTest.valueOf;

/// A3-0: the anchor operations of a storage instance -- every durable tier acknowledges a write, the newest version over
/// all durable tiers is read, a corrupt one fails closed and raises one operator warning per tier and anchor, and old
/// versions are pruned to the instance's retention.
@Timeout(30)
class StorageInstanceAnchorTest {
    private static final AnchorName NAME = nameOf("ahse-dir/content");

    @TempDir
    Path tempDir;

    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();

    @Test
    void aWriteResolvesOnlyOnceEveryDurableTierHas() {
        var slow = new HeldTier(disk("slow"));
        var instance = instanceOver(List.of(disk("fast"), slow), AnchorRetention.DEFAULT);

        var write = instance.putAnchor(NAME, 1, valueOf(1));

        assertThat(write.await(timeSpan(300).millis()).isFailure()).as("held while the slow tier has not answered").isTrue();
        slow.openGate();
        await(write);
    }

    @Test
    void aWriteThatOneDurableTierRefuses_fails() {
        var failing = new HeldTier(disk("failing"));
        var instance = instanceOver(List.of(disk("ok"), failing), AnchorRetention.DEFAULT);

        failing.fail();

        assertThat(causeOf(instance.putAnchor(NAME, 1, valueOf(1)))).isInstanceOf(StorageError.WriteError.class);
    }

    /// Non-durable tiers are no cold-boot source: a write skips them and a read ignores them.
    @Test
    void theLatestIsTheHighestVersionOverTheDurableTiers() {
        var older = disk("older");
        var newer = disk("newer");
        var instance = instanceOver(List.of(MemoryTier.memoryTier(1 << 20), older, newer), AnchorRetention.DEFAULT);

        await(older.putAnchor(NAME, 5, valueOf(5)));
        await(newer.putAnchor(NAME, 7, valueOf(7)));

        var latest = await(instance.latestAnchor(NAME)).unwrap();

        assertThat(latest.version()).isEqualTo(7);
        assertThat(latest.value()).isEqualTo(valueOf(7));
    }

    @Test
    void anAnchorNoTierHolds_isNone() {
        assertThat(await(instanceOver(List.of(disk("a"), disk("b")), AnchorRetention.DEFAULT).latestAnchor(NAME)).isEmpty())
            .isTrue();
    }

    /// No silent partial view: a tier that cannot answer fails the read, since it may hold the newest version.
    @Test
    void aTierThatCannotAnswer_failsTheRead() {
        var failing = new HeldTier(disk("failing"));
        var instance = instanceOver(List.of(disk("ok"), failing), AnchorRetention.DEFAULT);

        failing.fail();

        assertThat(causeOf(instance.latestAnchor(NAME))).isInstanceOf(StorageError.ReadError.class);
    }

    /// AF1: fail closed, one warning per (tier, anchor) on transition, nothing older read in its place.
    @Test
    void aCorruptNewestVersion_failsEveryRead_andRaisesOneWarningPerTierAndAnchor() throws Exception {
        var corrupted = disk("corrupted");
        var instance = instanceOver(List.of(corrupted, disk("healthy")), AnchorRetention.DEFAULT);
        var other = nameOf("ahse-dir/streams");

        await(instance.putAnchor(NAME, 1, valueOf(1)));
        await(instance.putAnchor(NAME, 2, valueOf(2)));
        await(instance.putAnchor(other, 1, valueOf(1)));
        await(instance.putAnchor(other, 2, valueOf(2)));
        corrupt(tempDir.resolve("corrupted"), NAME, 2);

        for (int read = 0; read < 3; read++) {
            assertThat(causeOf(instance.latestAnchor(NAME))).isInstanceOfSatisfying(AnchorError.AnchorCorrupt.class,
                                                                                   corrupt -> assertThat(corrupt.version()).isEqualTo(2));
        }
        assertThat(anchorWarnings()).hasSize(1);
        assertThat(anchorWarnings().getFirst().message()).contains(NAME.value()).contains("version 2");
        assertThat(await(instance.latestAnchor(other)).unwrap().version()).as("another anchor is unaffected").isEqualTo(2);

        corrupt(tempDir.resolve("corrupted"), other, 2);
        assertThat(causeOf(instance.latestAnchor(other))).isInstanceOf(AnchorError.AnchorCorrupt.class);
        assertThat(anchorWarnings()).as("a second anchor is its own condition").hasSize(2);
    }

    /// The recovery: once that tier's newest version reads back whole (here a newer version was written), the condition
    /// closes with one `storage-anchor-readable`, and only for that anchor.
    @Test
    void aCorruptAnchorThatReadsAgain_raisesOneRecovery() throws Exception {
        var instance = instanceOver(List.of(disk("corrupted")), AnchorRetention.DEFAULT);

        await(instance.putAnchor(NAME, 1, valueOf(1)));
        corrupt(tempDir.resolve("corrupted"), NAME, 1);
        assertThat(causeOf(instance.latestAnchor(NAME))).isInstanceOf(AnchorError.AnchorCorrupt.class);
        assertThat(recoveries()).as("nothing recovers while the newest version is corrupt").isEmpty();

        await(instance.putAnchor(NAME, 2, valueOf(2)));
        assertThat(await(instance.latestAnchor(NAME)).unwrap().version()).isEqualTo(2);
        assertThat(await(instance.latestAnchor(NAME)).unwrap().version()).isEqualTo(2);

        assertThat(recoveries()).hasSize(1);
        assertThat(anchorWarnings()).hasSize(1);
    }

    @Test
    void oneVersionWithDifferentValuesOnTwoTiers_failsClosed() {
        var first = disk("first");
        var second = disk("second");
        var instance = instanceOver(List.of(first, second), AnchorRetention.DEFAULT);

        await(first.putAnchor(NAME, 3, valueOf(3)));
        await(second.putAnchor(NAME, 3, valueOf(4)));

        assertThat(causeOf(instance.latestAnchor(NAME))).isInstanceOf(AnchorError.AnchorDiverged.class);
        assertThat(anchorWarnings()).hasSize(1);
    }

    @Test
    void anInstanceWithNoDurableTier_refuses() {
        var instance = instanceOver(List.of(MemoryTier.memoryTier(1 << 20)), AnchorRetention.DEFAULT);

        assertThat(causeOf(instance.putAnchor(NAME, 1, valueOf(1)))).isInstanceOf(AnchorError.NoDurableTier.class);
        assertThat(causeOf(instance.latestAnchor(NAME))).isInstanceOf(AnchorError.NoDurableTier.class);
    }

    /// AF3: after writing `v`, versions below `v - K` go; the newest K+1 stay, the one just written always among them.
    @Test
    void prunesKeepTheNewestRetentionPlusOneVersions() {
        var tier = disk("pruned");
        var instance = instanceOver(List.of(tier), AnchorRetention.anchorRetention(2).unwrap());

        for (long version = 1; version <= 10; version++) {
            await(instance.putAnchor(NAME, version, valueOf(version)));
            awaitPrune(instance);
        }

        assertThat(versionsOn(tempDir.resolve("pruned"))).containsExactlyInAnyOrder(8L, 9L, 10L);
    }

    /// A lagging writer's prune is bounded by ITS version, so it never removes what a newer writer keeps.
    @Test
    void aLaggingWritersPrune_neverRemovesWhatANewerWriterKeeps() {
        var tier = disk("lagging");
        var instance = instanceOver(List.of(tier), AnchorRetention.anchorRetention(2).unwrap());

        for (long version = 10; version <= 12; version++) {
            await(instance.putAnchor(NAME, version, valueOf(version)));
            awaitPrune(instance);
        }
        await(instance.putAnchor(NAME, 4, valueOf(4)));
        awaitPrune(instance);

        assertThat(versionsOn(tempDir.resolve("lagging"))).containsExactlyInAnyOrder(4L, 10L, 11L, 12L);
        assertThat(await(instance.latestAnchor(NAME)).unwrap().version()).isEqualTo(12);
    }

    /// A prune failure is counted and logged, never returned: the write it followed has already succeeded.
    @Test
    void aFailedPrune_isCountedAndDoesNotFailTheWrite() {
        var tier = new HeldTier(disk("unprunable"));
        var instance = instanceOver(List.of(tier), AnchorRetention.anchorRetention(2).unwrap());

        tier.failPrunes();
        for (long version = 1; version <= 5; version++) {
            await(instance.putAnchor(NAME, version, valueOf(version)));
            awaitPrune(instance);
        }

        assertThat(instance.anchorPruneFailures()).as("versions 3, 4 and 5 each started a prune").isEqualTo(3);
    }

    @Test
    void retentionBelowTwo_isRefused() {
        assertThat(AnchorRetention.anchorRetention(1).isFailure()).isTrue();
        assertThat(AnchorRetention.anchorRetention(2).isSuccess()).isTrue();
    }

    // --- fixtures ---

    private DefaultStorageInstance instanceOver(List<StorageTier> tiers, AnchorRetention retention) {
        var instance = (DefaultStorageInstance) StorageInstance.storageInstance("content",
                                                                                tiers,
                                                                                InMemoryMetadataStore.inMemoryMetadataStore("content"),
                                                                                WritePolicy.WRITE_THROUGH,
                                                                                none(),
                                                                                AppendLog.TornTailSink.logOnly(),
                                                                                _ -> {},
                                                                                StorageInstance.DEFAULT_MAX_BLOCK_BYTES,
                                                                                retention);
        var reporter = DegradeReporter.degradeReporter("content");

        reporter.bind(OperatorWarningSink.handingOffTo(raised::add));
        instance.reportTo(reporter);

        return instance;
    }

    private LocalDiskTier disk(String directory) {
        return LocalDiskTier.localDiskTier(tempDir.resolve(directory), 1 << 20).unwrap();
    }

    private List<OperatorWarning> anchorWarnings() {
        return raised.stream().filter(warning -> warning.code() == OperatorWarningCode.STORAGE_ANCHOR_CORRUPT).toList();
    }

    private List<OperatorWarning> recoveries() {
        return raised.stream().filter(warning -> warning.code() == OperatorWarningCode.STORAGE_ANCHOR_READABLE).toList();
    }

    private static void awaitPrune(DefaultStorageInstance instance) {
        instance.anchorPruneInFlight(NAME).onPresent(AnchorTierTest::await);
    }

    private static void corrupt(Path base, AnchorName name, long version) throws Exception {
        var file = base.resolve(AnchorFrame.DIRECTORY).resolve(name.storageSegment()).resolve(AnchorFrame.key(version));
        var bytes = java.nio.file.Files.readAllBytes(file);

        bytes[bytes.length - 1] ^= 0x01;
        java.nio.file.Files.write(file, bytes);
    }

    private static List<Long> versionsOn(Path base) {
        return org.pragmatica.lang.io.FileOps.list(base.resolve(AnchorFrame.DIRECTORY).resolve(NAME.storageSegment()))
                                             .unwrap()
                                             .stream()
                                             .flatMap(path -> AnchorFrame.versionOf(path.getFileName().toString()).stream())
                                             .toList();
    }

    /// A durable tier whose anchor writes wait for [#openGate], or fail after [#fail]; reads fail after [#fail] too.
    static final class HeldTier implements StorageTier {
        private final StorageTier delegate;
        private final Promise<Unit> gate = Promise.promise();
        private final AtomicBoolean failing = new AtomicBoolean();
        private final AtomicBoolean failingPrunes = new AtomicBoolean();

        HeldTier(StorageTier delegate) {
            this.delegate = delegate;
        }

        void openGate() {
            gate.succeed(Unit.unit());
        }

        void fail() {
            failing.set(true);
            openGate();
        }

        void failPrunes() {
            failingPrunes.set(true);
            openGate();
        }

        @Override
        public Promise<Unit> putAnchor(AnchorName name, long version, byte[] value) {
            return gate.flatMap(_ -> failing.get()
                                     ? StorageError.WriteError.writeError("refused").promise()
                                     : delegate.putAnchor(name, version, value));
        }

        @Override
        public Promise<Option<Anchor>> latestAnchor(AnchorName name) {
            return failing.get()
                   ? StorageError.ReadError.readError("unreachable").promise()
                   : delegate.latestAnchor(name);
        }

        @Override
        public Promise<Unit> pruneAnchors(AnchorName name, long below, int scanLimit) {
            return failingPrunes.get()
                   ? StorageError.WriteError.writeError("prune refused").promise()
                   : delegate.pruneAnchors(name, below, scanLimit);
        }

        @Override
        public Promise<Option<byte[]>> get(BlockId id) {
            return delegate.get(id);
        }

        @Override
        public Promise<Unit> put(BlockId id, byte[] content) {
            return delegate.put(id, content);
        }

        @Override
        public Promise<Unit> delete(BlockId id) {
            return delegate.delete(id);
        }

        @Override
        public Promise<Boolean> exists(BlockId id) {
            return delegate.exists(id);
        }

        @Override
        public TierLevel level() {
            return delegate.level();
        }

        @Override
        public long usedBytes() {
            return delegate.usedBytes();
        }

        @Override
        public long maxBytes() {
            return delegate.maxBytes();
        }

        @Override
        public boolean isDurable() {
            return true;
        }
    }
}
