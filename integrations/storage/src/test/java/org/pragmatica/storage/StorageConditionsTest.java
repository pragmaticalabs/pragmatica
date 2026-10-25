package org.pragmatica.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.DemotionConfig.demotionConfig;
import static org.pragmatica.storage.InMemoryMetadataStore.inMemoryMetadataStore;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.storage.SnapshotManager.snapshotManager;
import static org.pragmatica.storage.StorageCondition.Kind;

/// A0b: the storage managers report what needs an operator as [StorageCondition]s. Each test names its mutation.
class StorageConditionsTest {
    @TempDir
    Path dir;

    /// Mutation: `refuseDurableDemotion` never sets `refusing` -- the episode is invisible.
    @Test
    void demotion_durableTierAboveWatermarkWithNoDurableBelow_isReportedUntilItDrains() {
        var durable = new DurableTier(MemoryTier.memoryTier(100, TierLevel.LOCAL_DISK));
        var cache = MemoryTier.memoryTier(1_000, TierLevel.DHT);
        var store = inMemoryMetadataStore("cond");
        var manager = DemotionManager.demotionManager(List.of(durable, cache), store, demotionConfig(DemotionStrategy.LRU, 0.5, 0.2, 10));
        var block = "x".repeat(80).getBytes(StandardCharsets.UTF_8);
        var id = BlockId.blockId(block).unwrap();

        manager.activate();
        durable.put(id, block).await();
        manager.demote();

        assertThat(manager.conditions()).extracting(StorageCondition::kind).containsExactly(Kind.DEMOTION_REFUSED);
        assertThat(manager.conditions().getFirst().detail()).contains("only durable copy");

        durable.delete(id).await();
        manager.demote();

        assertThat(manager.conditions()).as("the episode ends when the tier is back under its watermark").isEmpty();
    }

    /// Mutation: `recordWriteFailure` does not set `writeFailure`; or `clearWriteConditions` is not run on success.
    @Test
    void snapshot_writeFailure_isReported_andClearedByTheNextGoodWrite() throws IOException {
        var blocker = Files.writeString(dir.resolve("not-a-directory"), "x");
        var store = inMemoryMetadataStore("snap");
        var failing = snapshotManager(store, snapshotConfig(blocker.resolve("snapshots"), 1, 600_000, 3, "n1"));

        store.createLifecycle(BlockLifecycle.blockLifecycle(BlockId.blockId("a".getBytes(StandardCharsets.UTF_8)).unwrap(), TierLevel.MEMORY));
        failing.forceSnapshot();

        assertThat(failing.conditions()).extracting(StorageCondition::kind).containsExactly(Kind.SNAPSHOT_WRITE_FAILING);

        var healthy = snapshotManager(store, snapshotConfig(dir.resolve("snapshots"), 1, 600_000, 3, "n1"));

        assertThat(healthy.conditions()).isEmpty();
        healthy.forceSnapshot();
        assertThat(healthy.conditions()).isEmpty();
    }

    /// Mutation: `reportFallback` does not set `fellBack`; or the next good write does not clear it.
    @Test
    void snapshot_olderRetainedRestored_isReported_andClearedByTheNextGoodWrite() throws IOException {
        var config = snapshotConfig(dir, 1, 600_000, 5, "n1");
        var store = inMemoryMetadataStore("snap");
        var writer = snapshotManager(store, config);

        store.createLifecycle(BlockLifecycle.blockLifecycle(BlockId.blockId("a".getBytes(StandardCharsets.UTF_8)).unwrap(), TierLevel.MEMORY));
        writer.forceSnapshot();
        store.createLifecycle(BlockLifecycle.blockLifecycle(BlockId.blockId("b".getBytes(StandardCharsets.UTF_8)).unwrap(), TierLevel.MEMORY));
        writer.forceSnapshot();
        newestSnapshot().forEach(this::tear);

        var restarted = snapshotManager(inMemoryMetadataStore("restarted"), config);

        assertThat(restarted.restoreFromLatest().unwrap().isPresent()).isTrue();
        assertThat(restarted.conditions()).extracting(StorageCondition::kind).containsExactly(Kind.SNAPSHOT_FELL_BACK);

        restarted.forceSnapshot();
        assertThat(restarted.conditions()).isEmpty();
    }

    private List<Path> newestSnapshot() throws IOException {
        try (var files = Files.list(dir)) {
            return files.filter(path -> path.getFileName().toString().startsWith("snapshot-"))
                        .sorted()
                        .toList()
                        .reversed()
                        .stream()
                        .limit(1)
                        .toList();
        }
    }

    private void tear(Path file) {
        try {
            Files.writeString(file, "torn");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /// A tier that reports itself durable, over a memory tier standing in for the disk.
    private record DurableTier(MemoryTier delegate) implements StorageTier {
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
