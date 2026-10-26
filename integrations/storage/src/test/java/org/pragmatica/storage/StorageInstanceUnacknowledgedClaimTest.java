package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.storage.SnapshotManager.snapshotManager;

/// SB-02: a block's record asserts "durably held per the ack point", so the claim a put makes before its
/// write is not a record of the block: no snapshot captures it, `exists` does not read it, and a put after a
/// restore of such a snapshot writes instead of deduplicating onto bytes no tier holds.
class StorageInstanceUnacknowledgedClaimTest {
    private static final byte[] CONTENT = "claimed before written".getBytes(StandardCharsets.UTF_8);
    private static final String NODE_ID = "sb02-node";

    @TempDir
    Path dir;

    /// A snapshot lands while the durable write is parked, the node is lost, and a new node restores that
    /// snapshot over an empty disk. The retried put must write the block, not acknowledge it unwritten.
    @Test
    void put_afterRestoringASnapshotTakenBetweenClaimAndWrite_writesTheBlock() throws InterruptedException {
        var parked = new GatedWriter(true);
        var storeA = MetadataStore.inMemoryMetadataStore("a");
        var nodeA = instance(storeA, diskTier("a", parked));
        var inFlight = nodeA.put(CONTENT);

        assertThat(parked.entered.await(5, TimeUnit.SECONDS)).isTrue();

        var snapshots = dir.resolve("snapshots");

        snapshotManager(storeA, snapshotConfig(snapshots, 100, 600_000, 5, NODE_ID)).snapshotNow().unwrap();

        var storeB = restoredStore(snapshots);
        var counting = new CountingWriter();
        var nodeB = instance(storeB, diskTier("b", counting));
        var id = nodeB.put(CONTENT).await().onFailure(cause -> fail(cause.message())).unwrap();

        assertThat(counting.calls.get()).as("the retried put wrote the block").isEqualTo(1);
        assertThat(nodeB.get(id).await().unwrap().isPresent()).isTrue();
        assertThat(storeB.getLifecycle(id).unwrap().refCount()).as("one reference, not a dedup credit").isEqualTo(1);

        parked.release.countDown();
        inFlight.await();
    }

    /// In the window, and again after a restore of a snapshot taken in it, `exists` and `get` give one answer.
    @Test
    void exists_agreesWithGet_inTheWindowAndAfterRestore() throws InterruptedException {
        var parked = new GatedWriter(true);
        var storeA = MetadataStore.inMemoryMetadataStore("a");
        var nodeA = instance(storeA, diskTier("a", parked));
        var id = BlockId.blockId(CONTENT).unwrap();
        var inFlight = nodeA.put(CONTENT);

        assertThat(parked.entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(nodeA.exists(id).await().unwrap()).as("in the window").isEqualTo(nodeA.get(id).await().unwrap().isPresent());
        assertThat(nodeA.exists(id).await().unwrap()).as("no tier holds it yet").isFalse();

        var snapshots = dir.resolve("snapshots");

        snapshotManager(storeA, snapshotConfig(snapshots, 100, 600_000, 5, NODE_ID)).snapshotNow().unwrap();

        var nodeB = instance(restoredStore(snapshots), diskTier("b", new CountingWriter()));

        assertThat(nodeB.exists(id).await().unwrap()).as("after restore").isEqualTo(nodeB.get(id).await().unwrap().isPresent());
        assertThat(nodeB.exists(id).await().unwrap()).isFalse();

        parked.release.countDown();
        inFlight.await();
    }

    /// Single flight is kept: eight puts of one block write once, are all acknowledged, and none of them is
    /// visible to a snapshot while the write is open.
    @Test
    void concurrentPutsOfOneBlock_writeOnce_andStayOutOfTheSnapshotUntilTheWriteLands() throws InterruptedException {
        var parked = new GatedWriter(false);
        var store = MetadataStore.inMemoryMetadataStore("flight");
        var node = instance(store, diskTier("flight", parked));
        var puts = new ArrayList<org.pragmatica.lang.Promise<BlockId>>();

        puts.add(node.put(CONTENT));
        assertThat(parked.entered.await(5, TimeUnit.SECONDS)).isTrue();

        for (var i = 0; i < 7; i++) {
            puts.add(node.put(CONTENT));
        }

        assertThat(store.listAllLifecycles()).as("the open write is not in a snapshot").isEmpty();

        parked.release.countDown();

        var ids = puts.stream().map(p -> p.await().unwrap()).distinct().toList();

        assertThat(ids).hasSize(1);
        assertThat(parked.calls.get()).as("one write for eight puts").isEqualTo(1);
        assertThat(store.getLifecycle(ids.getFirst()).unwrap().refCount()).isEqualTo(8);
        assertThat(store.listAllLifecycles()).as("acknowledged, so captured").hasSize(1);
    }

    /// A failed write leaves no record: not in the store, not claimed, not in a later snapshot.
    @Test
    void failedWrite_leavesNoRecord() throws InterruptedException {
        var parked = new GatedWriter(true);
        var store = MetadataStore.inMemoryMetadataStore("failed");
        var node = instance(store, diskTier("failed", parked));
        var id = BlockId.blockId(CONTENT).unwrap();
        var inFlight = node.put(CONTENT);

        assertThat(parked.entered.await(5, TimeUnit.SECONDS)).isTrue();
        parked.release.countDown();
        inFlight.await().onSuccess(_ -> fail("the write was injected to fail"));

        assertThat(store.getLifecycle(id).isPresent()).isFalse();
        assertThat(store.isUnacknowledged(id)).isFalse();
        assertThat(store.listAllLifecycles()).isEmpty();
    }

    /// The positive control: once the write is acknowledged the record IS captured, and a restore of that
    /// snapshot deduplicates a later put onto a block that is really held.
    @Test
    void acknowledgedBlock_isCapturedByASnapshot() {
        var store = MetadataStore.inMemoryMetadataStore("acked");
        var node = instance(store, diskTier("acked", new CountingWriter()));
        var id = node.put(CONTENT).await().unwrap();
        var snapshots = dir.resolve("snapshots");

        snapshotManager(store, snapshotConfig(snapshots, 100, 600_000, 5, NODE_ID)).snapshotNow().unwrap();

        assertThat(store.isUnacknowledged(id)).isFalse();
        assertThat(restoredStore(snapshots).getLifecycle(id).isPresent()).isTrue();
    }

    /// The mark must be set BEFORE the record is inserted, so a capture at any instant after the insert
    /// finds the id marked. The map hands the test the instant right after the insert (the one place a
    /// concurrent capture could otherwise see an unmarked record): there, the claim must already be hidden
    /// from [MetadataStore#listAllLifecycles] and reported by [MetadataStore#isUnacknowledged].
    @Test
    void claimUnacknowledged_marksBeforeTheRecordExists_soNoCaptureSeesItUnmarked() {
        var seenRightAfterInsert = new ArrayList<BlockLifecycle>();
        var markedRightAfterInsert = new ArrayList<Boolean>();
        var holder = new InMemoryMetadataStore[1];
        var map = new ConcurrentHashMap<BlockId, BlockLifecycle>() {
            @Override
            public BlockLifecycle putIfAbsent(BlockId key, BlockLifecycle value) {
                var previous = super.putIfAbsent(key, value);

                seenRightAfterInsert.addAll(holder[0].listAllLifecycles());
                markedRightAfterInsert.add(holder[0].isUnacknowledged(key));

                return previous;
            }
        };

        holder[0] = InMemoryMetadataStore.inMemoryMetadataStore("capture-race", AccessTracker.accessTracker(), map);

        var id = BlockId.blockId(CONTENT).unwrap();

        assertThat(holder[0].claimUnacknowledged(id, BlockLifecycle.blockLifecycle(id, TierLevel.LOCAL_DISK))).isTrue();
        assertThat(seenRightAfterInsert).as("a capture right after the insert lists nothing").isEmpty();
        assertThat(markedRightAfterInsert).as("and the id is already marked").containsExactly(true);
    }

    private static StorageInstance instance(MetadataStore store, LocalDiskTier disk) {
        return StorageInstance.storageInstance("sb02", List.of(MemoryTier.memoryTier(1 << 20), disk), store);
    }

    private static MetadataStore restoredStore(Path snapshots) {
        var restored = MetadataStore.inMemoryMetadataStore("restored");
        var snapshot = snapshotManager(restored, snapshotConfig(snapshots, 100, 600_000, 5, NODE_ID)).restoreFromLatest()
                                                                                                    .unwrap()
                                                                                                    .unwrap();

        restored.restoreLifecycles(snapshot.lifecycles());
        restored.restoreRefs(snapshot.refs());

        return restored;
    }

    private LocalDiskTier diskTier(String name, Fn2<Result<Unit>, Path, byte[]> writer) {
        return LocalDiskTier.localDiskTier(dir.resolve("blocks-" + name),
                                           1 << 20,
                                           timeSpan(30).seconds(),
                                           none(),
                                           some(writer),
                                           none())
                            .unwrap();
    }

    private static final class CountingWriter implements Fn2<Result<Unit>, Path, byte[]> {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public Result<Unit> apply(Path path, byte[] content) {
            calls.incrementAndGet();

            return FileOps.writeBytesForced(path, content);
        }
    }

    /// Parks its first call until released; that call fails when `failFirst`, later calls write normally.
    private static final class GatedWriter implements Fn2<Result<Unit>, Path, byte[]> {
        private final boolean failFirst;
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();

        GatedWriter(boolean failFirst) {
            this.failFirst = failFirst;
        }

        @Override
        public Result<Unit> apply(Path path, byte[] content) {
            if (calls.incrementAndGet() > 1) {
                return FileOps.writeBytesForced(path, content);
            }

            entered.countDown();
            awaitRelease();

            return failFirst
                   ? Causes.cause("injected disk failure").result()
                   : FileOps.writeBytesForced(path, content);
        }

        private void awaitRelease() {
            try {
                if (!release.await(30, TimeUnit.SECONDS)) {
                    fail("the parked write was never released");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
