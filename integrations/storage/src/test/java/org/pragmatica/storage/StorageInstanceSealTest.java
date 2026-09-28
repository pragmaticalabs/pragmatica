package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1567: `StorageInstance.seal` makes the block durable, THEN records the ref, THEN lets the log be
/// truncated -- and a write succeeds only once every durable tier has it, whatever tier comes last.
///
/// The local-disk tier's partial-file write (which forces the file) and its post-rename directory force
/// are injected so that every force records what the rest of the system could see AT THAT MOMENT: whether
/// the ref already resolved and how far the log's seal bound had moved. A seal that recorded the ref, or
/// advanced the log, before forcing the block is caught by the force itself observing it.
class StorageInstanceSealTest {
    private static final String REF = "streams/orders/0/0-2";
    private static final byte[] BLOCK = "segment [0-2]".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    private final List<ForceObservation> forces = new CopyOnWriteArrayList<>();
    private final AtomicReference<StorageInstance> instance = new AtomicReference<>();
    private final AtomicReference<AppendLog> log = new AtomicReference<>();

    @Nested
    class Ordering {
        @Test
        void seal_forcesBlockFileAndDirectory_beforeRecordingRefOrAdvancingLog() {
            var storage = storageWith(recordingWriter(), recordingForcer());
            var wal = logWithRecords(storage, 5);

            storage.seal(wal, 0, 2, REF, BLOCK).await().onFailure(cause -> fail(cause.message()));

            assertThat(forces).extracting(ForceObservation::kind).containsExactly("file", "directory");
            assertThat(forces).as("every force happened before the ref resolved and before the log's bound moved")
                              .allSatisfy(force -> {
                                  assertThat(force.refPresent()).isFalse();
                                  assertThat(force.sealedThrough()).isEqualTo(-1L);
                              });
            assertThat(storage.resolveRef(REF).map(BlockId::hexString)).isEqualTo(BlockId.blockId(BLOCK).map(BlockId::hexString).option());
            assertThat(wal.sealedThrough()).isEqualTo(2L);
        }

        /// A block this instance already holds is written again: its record may be the claim of a write
        /// still in flight, and a seal must see its own block become durable before naming it.
        @Test
        void seal_forcesAnAlreadyStoredBlockAgain_beforeRecordingRef() {
            var storage = storageWith(recordingWriter(), recordingForcer());
            var wal = logWithRecords(storage, 3);

            storage.put(BLOCK).await().onFailure(cause -> fail(cause.message()));
            forces.clear();
            storage.seal(wal, 0, 2, REF, BLOCK).await().onFailure(cause -> fail(cause.message()));

            assertThat(forces).extracting(ForceObservation::kind).containsExactly("file", "directory");
            assertThat(forces).allSatisfy(force -> assertThat(force.refPresent()).isFalse());
            assertThat(wal.sealedThrough()).isEqualTo(2L);
        }
    }

    @Nested
    class FailedDurability {
        @Test
        void seal_recordsNoRefAndTruncatesNothing_whenDirectoryForceFails() {
            var storage = storageWith(recordingWriter(), failingForcer());
            var wal = logWithRecords(storage, 5);

            storage.seal(wal, 0, 2, REF, BLOCK).await().onSuccess(_ -> fail("the block was never made durable"));
            wal.truncate(2L).onFailure(cause -> fail(cause.message()));

            assertNothingSealed(storage, wal, 5);
        }

        @Test
        void seal_recordsNoRefAndTruncatesNothing_whenFileForceFails() {
            var storage = storageWith(failingWriter(), recordingForcer());
            var wal = logWithRecords(storage, 5);

            storage.seal(wal, 0, 2, REF, BLOCK).await().onSuccess(_ -> fail("the block was never made durable"));
            wal.truncate(2L).onFailure(cause -> fail(cause.message()));

            assertNothingSealed(storage, wal, 5);
        }

        @Test
        void seal_refuses_withoutDurableTier() {
            var storage = StorageInstance.storageInstance("mem",
                                                          List.of(MemoryTier.memoryTier(1 << 20)),
                                                          MetadataStore.inMemoryMetadataStore("mem"),
                                                          WritePolicy.WRITE_THROUGH,
                                                          some(dir.resolve("logs")));
            var wal = logWithRecords(storage, 3);

            storage.seal(wal, 0, 2, REF, BLOCK)
                   .await()
                   .onSuccess(_ -> fail("an instance without a durable tier must not seal"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.NoDurableTier.class));

            assertNothingSealed(storage, wal, 3);
        }

        @Test
        void seal_refuses_invertedRange() {
            var storage = storageWith(recordingWriter(), recordingForcer());
            var wal = logWithRecords(storage, 3);

            storage.seal(wal, 2, 1, REF, BLOCK)
                   .await()
                   .onSuccess(_ -> fail("an inverted range must be refused"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.InvalidSealRange.class));
            assertThat(forces).isEmpty();
        }
    }

    /// The `streams` instance's tiers are memory, local disk, then an in-memory DHT tier LAST. Before #1567
    /// only the last tier was required, so a local-disk failure was absorbed as a cache miss (#910) and the
    /// block lived in memory alone. A stand-in memory tier plays the DHT here.
    @Nested
    class DurableTierRequired {
        @Test
        void putRef_fails_whenLocalDiskFails_evenThoughLastTierSucceeds() {
            var storage = dhtLastStorageWith(failingWriter());

            storage.putRef(REF, BLOCK).await().onSuccess(_ -> fail("the only durable tier failed"));

            assertThat(storage.resolveRef(REF).isPresent()).isFalse();
        }

        @Test
        void putRef_writesTheDurableTier_evenThoughItIsNotLast() {
            var storage = dhtLastStorageWith(recordingWriter());

            storage.putRef(REF, BLOCK).await().onFailure(cause -> fail(cause.message()));

            assertThat(forces).extracting(ForceObservation::kind).containsExactly("file", "directory");
            assertThat(storage.resolveRef(REF).isPresent()).isTrue();
        }

        @Test
        void seal_fails_whenLocalDiskFails_evenThoughLastTierSucceeds() {
            var storage = dhtLastStorageWith(failingWriter());
            var wal = logWithRecords(storage, 3);

            storage.seal(wal, 0, 2, REF, BLOCK).await().onSuccess(_ -> fail("the only durable tier failed"));

            assertNothingSealed(storage, wal, 3);
        }

        /// #910 still holds for a genuinely non-durable cache tier: its failure after the durable write is
        /// absorbed, and the caller is told its durably stored block is stored.
        @Test
        void putRef_succeeds_whenOnlyAMemoryCacheTierFails() {
            var disk = diskTier(recordingWriter(), recordingForcer());
            var storage = StorageInstance.storageInstance("cache-fails",
                                                          List.of(MemoryTier.memoryTier(1), disk),
                                                          MetadataStore.inMemoryMetadataStore("cache-fails"));

            storage.putRef(REF, BLOCK).await().onFailure(cause -> fail(cause.message()));

            assertThat(storage.resolveRef(REF).isPresent()).isTrue();
        }
    }

    @Nested
    class OpenLog {
        @Test
        void openLog_placesTheLogUnderTheLogRoot() {
            var storage = storageWith(recordingWriter(), recordingForcer());

            storage.openLog("orders/0")
                   .onFailure(cause -> fail(cause.message()))
                   .onSuccess(wal -> assertThat(wal.path()).isEqualTo(dir.resolve("logs").resolve("orders").resolve("0.wal")))
                   .onSuccess(AppendLog::close);
        }

        @Test
        void openLog_refusesNamesOutsideTheLogRoot() {
            var storage = storageWith(recordingWriter(), recordingForcer());

            List.of("../escape", "/absolute", "", " ", "a/../../escape")
                .forEach(name -> storage.openLog(name)
                                        .onSuccess(_ -> fail("accepted log name '" + name + "'"))
                                        .onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.InvalidLogName.class)));
        }

        @Test
        void openLog_fails_withoutLogRoot() {
            var storage = StorageInstance.storageInstance("no-root", List.of(MemoryTier.memoryTier(1 << 20)));

            storage.openLog("orders/0")
                   .onSuccess(_ -> fail("an instance without a log root has nowhere to put a log"))
                   .onFailure(cause -> assertThat(cause).isInstanceOf(StorageError.LogsUnsupported.class));
        }
    }

    private void assertNothingSealed(StorageInstance storage, AppendLog wal, int records) {
        assertThat(storage.resolveRef(REF).isPresent()).as("no ref names a block that is not durable").isFalse();
        assertThat(wal.sealedThrough()).isEqualTo(-1L);
        assertThat(replayedOffsets(wal)).as("the log kept every record")
                                        .containsExactlyElementsOf(IntStream.range(0, records).mapToObj(i -> (long) i).toList());
    }

    private StorageInstance storageWith(Fn2<Result<Unit>, Path, byte[]> writer, Fn1<Result<Unit>, Path> forcer) {
        var storage = StorageInstance.storageInstance("streams",
                                                      List.of(MemoryTier.memoryTier(1 << 20), diskTier(writer, forcer)),
                                                      MetadataStore.inMemoryMetadataStore("streams"),
                                                      WritePolicy.WRITE_THROUGH,
                                                      some(dir.resolve("logs")));
        instance.set(storage);

        return storage;
    }

    private StorageInstance dhtLastStorageWith(Fn2<Result<Unit>, Path, byte[]> writer) {
        var storage = StorageInstance.storageInstance("streams",
                                                      List.of(MemoryTier.memoryTier(1 << 20),
                                                              diskTier(writer, recordingForcer()),
                                                              MemoryTier.memoryTier(1 << 20, TierLevel.REMOTE)),
                                                      MetadataStore.inMemoryMetadataStore("streams"),
                                                      WritePolicy.WRITE_THROUGH,
                                                      some(dir.resolve("logs")));
        instance.set(storage);

        return storage;
    }

    private LocalDiskTier diskTier(Fn2<Result<Unit>, Path, byte[]> writer, Fn1<Result<Unit>, Path> forcer) {
        return LocalDiskTier.localDiskTier(dir.resolve("blocks"),
                                           1 << 20,
                                           timeSpan(30).seconds(),
                                           none(),
                                           some(writer),
                                           some(forcer))
                            .unwrap();
    }

    private AppendLog logWithRecords(StorageInstance storage, int records) {
        var wal = storage.openLog("orders/0").unwrap();

        IntStream.range(0, records)
                 .forEach(i -> wal.append(i, new byte[]{(byte) i}, 1L).await().onFailure(cause -> fail(cause.message())));
        log.set(wal);

        return wal;
    }

    private Fn2<Result<Unit>, Path, byte[]> recordingWriter() {
        return (path, content) -> FileOps.writeBytesForced(path, content).onSuccess(_ -> observe("file"));
    }

    private Fn1<Result<Unit>, Path> recordingForcer() {
        return path -> FileOps.forceDirectory(path).onSuccess(_ -> observe("directory"));
    }

    private static Fn2<Result<Unit>, Path, byte[]> failingWriter() {
        return (_, _) -> Causes.cause("injected file force failure").result();
    }

    private static Fn1<Result<Unit>, Path> failingForcer() {
        return _ -> Causes.cause("injected directory force failure").result();
    }

    private void observe(String kind) {
        var refPresent = Option.option(instance.get()).map(storage -> storage.resolveRef(REF).isPresent()).or(false);
        var sealedThrough = Option.option(log.get()).map(AppendLog::sealedThrough).or(-1L);

        forces.add(new ForceObservation(kind, refPresent, sealedThrough));
    }

    private static List<Long> replayedOffsets(AppendLog wal) {
        var offsets = new ArrayList<Long>();

        wal.replay(-1L, record -> offsets.add(record.offset())).onFailure(cause -> fail(cause.message()));

        return offsets;
    }

    private record ForceObservation(String kind, boolean refPresent, long sealedThrough) {}
}
