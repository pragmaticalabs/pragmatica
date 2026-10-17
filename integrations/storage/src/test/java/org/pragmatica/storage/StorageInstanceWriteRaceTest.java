package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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

/// #1567, one layer above the tiers: a put never hands out an id whose bytes have not landed on every
/// required tier, and a required write that fails part-way leaves no unreachable copy behind.
class StorageInstanceWriteRaceTest {
    private static final byte[] CONTENT = "checkpoint block".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    /// Two puts of the same content. The first claims the id and its durable write is held open; the second
    /// must not resolve while it is open -- a ref, cursor or checkpoint it named would name bytes that may
    /// never land. The first write then FAILS: the second goes round again, claims the released id and
    /// writes the block itself (the second disk write), so it succeeds with bytes that are really there.
    @Test
    void duplicatePut_waitsForTheInFlightWrite_andWritesItselfWhenThatWriteFails() throws InterruptedException {
        var writer = new GatedWriter(true);
        var disk = diskTier(writer);
        var storage = StorageInstance.storageInstance("race", List.of(MemoryTier.memoryTier(1 << 20), disk));

        var first = storage.put(CONTENT);

        assertThat(writer.entered.await(5, TimeUnit.SECONDS)).isTrue();

        var second = storage.put(CONTENT);

        assertThat(second.await(timeSpan(200).millis()).isFailure()).as("the duplicate waits for the open write").isTrue();

        writer.release.countDown();

        first.await().onSuccess(_ -> fail("the first write was injected to fail"));

        var id = second.await().onFailure(cause -> fail(cause.message())).unwrap();

        assertThat(writer.calls.get()).as("the duplicate wrote the block itself").isEqualTo(2);
        assertThat(disk.exists(id).await().unwrap()).isTrue();
    }

    /// The same race with a first write that succeeds: the duplicate deduplicates onto the finished block,
    /// writing nothing, and both references are counted.
    @Test
    void duplicatePut_deduplicatesOntoTheFinishedBlock_whenTheInFlightWriteSucceeds() throws InterruptedException {
        var writer = new GatedWriter(false);
        var store = MetadataStore.inMemoryMetadataStore("race-ok");
        var storage = StorageInstance.storageInstance("race-ok",
                                                      List.of(MemoryTier.memoryTier(1 << 20), diskTier(writer)),
                                                      store);

        var first = storage.put(CONTENT);

        assertThat(writer.entered.await(5, TimeUnit.SECONDS)).isTrue();

        var second = storage.put(CONTENT);

        assertThat(second.await(timeSpan(200).millis()).isFailure()).isTrue();
        writer.release.countDown();

        var id = first.await().unwrap();

        assertThat(second.await().unwrap()).isEqualTo(id);
        assertThat(writer.calls.get()).isEqualTo(1);
        assertThat(store.getLifecycle(id).unwrap().refCount()).isEqualTo(2);
    }

    /// The disk takes the block, then the last (shared, in-memory) tier refuses it. The put fails, and the
    /// disk copy is removed before the claim is released: without that it would sit on disk with no record,
    /// and garbage collection -- driven by records -- would never find it (#910's orphan).
    @Test
    void putRef_removesTheDiskCopy_whenTheLastRequiredTierFailsAfterIt() {
        var disk = diskTier(FileOps::writeBytesForced);
        var storage = StorageInstance.storageInstance("compensate",
                                                      List.of(MemoryTier.memoryTier(1 << 20),
                                                              disk,
                                                              MemoryTier.memoryTier(1, TierLevel.REMOTE)));
        var id = BlockId.blockId(CONTENT).unwrap();

        storage.putRef("cursor", CONTENT).await().onSuccess(_ -> fail("the last required tier is full"));

        assertThat(disk.exists(id).await().unwrap()).as("no unreachable copy left on disk").isFalse();
        assertThat(disk.usedBytes()).isZero();
        assertThat(storage.resolveRef("cursor").isPresent()).isFalse();
    }

    private LocalDiskTier diskTier(Fn2<Result<Unit>, Path, byte[]> writer) {
        return LocalDiskTier.localDiskTier(dir.resolve("blocks"),
                                           1 << 20,
                                           timeSpan(30).seconds(),
                                           none(),
                                           some(writer),
                                           none())
                            .unwrap();
    }

    /// Blocks its first call until released; that call fails when `failFirst`, later calls write normally.
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
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
