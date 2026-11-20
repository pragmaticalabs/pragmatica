package org.pragmatica.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.storage.InMemoryMetadataStore.inMemoryMetadataStore;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;

/// #2836 (AD0b-1a), the storage module's half: a snapshot carries the id of the storage that wrote it and a restore
/// refuses a foreign one (T28b); `openLog` refuses the names the storage keeps for itself (T28c); the durable `put`
/// the identity file will rely on fails loudly and leaves no record (T28d, pins A14 and is green before this change).
///
/// Marked guesses, one assertion each so a later ruling flips one line: a snapshot with one side's id absent is
/// restored ([Restore#anUnstampedSnapshotUnderAConfiguredId_isRestored]); a reserved name is the FIRST `/`-segment,
/// compared case-sensitively ([ReservedLogNames#reservedForm_isTheFirstSegment_caseSensitive]).
class StorageIdentityPrimitivesTest {
    private static final String NODE_ID = "node-identity";
    private static final String ID_U = "storage-U";
    private static final String ID_V = "storage-V";
    private static final byte[] CONTENT = "identity block".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path dir;

    @Nested
    class Header {
        /// T28b(a): the file names the storage that wrote it.
        @Test
        void aConfiguredId_isWrittenAsAHeaderLine() {
            var store = inMemoryMetadataStore("t28ba");
            var manager = managerWith(store, some(ID_U));

            mutate(store, "one");
            manager.forceSnapshot();

            assertThat(headerLines(latestFile())).contains("storageId=" + ID_U);
        }

        /// T28b(a): no id configured, no line: the file is what it was before #2836.
        @Test
        void noConfiguredId_writesNoStorageIdLine() {
            var store = inMemoryMetadataStore("t28ba0");
            var manager = managerWith(store, none());

            mutate(store, "one");
            manager.forceSnapshot();

            assertThat(headerLines(latestFile())).noneMatch(line -> line.startsWith("storageId"));
        }
    }

    @Nested
    class Restore {
        /// T28b(b): the newest file is another storage's. The failure names the mismatch, not "nothing
        /// restorable", and the OLDER retained file -- written under the id being restored -- is not used: the
        /// volume in front of this node is the foreign one.
        @Test
        void aSnapshotStampedByAnotherStorage_isRefused_andNoOlderFileIsTried() {
            var store = inMemoryMetadataStore("t28bb");

            mutate(store, "one");
            managerWith(store, some(ID_V)).forceSnapshot();
            mutate(store, "two");
            managerWith(store, some(ID_U)).forceSnapshot();

            var restored = managerWith(store, some(ID_V)).restoreFromLatest();

            assertThat(restored.isFailure()).as("a foreign snapshot is a failure, not an empty restore").isTrue();
            restored.onFailure(cause -> assertMismatch(cause, ID_V, ID_U));
        }

        /// T28b(b), the plain shape: one foreign file and nothing else.
        @Test
        void aForeignSnapshot_isAMismatch_notNothingRestorable() {
            var store = inMemoryMetadataStore("t28bb1");

            mutate(store, "one");
            managerWith(store, some(ID_U)).forceSnapshot();

            managerWith(store, some(ID_V)).restoreFromLatest()
                                          .onSuccess(_ -> fail("foreign snapshot restored"))
                                          .onFailure(cause -> assertMismatch(cause, ID_V, ID_U));
        }

        /// T28b(b): the same id restores, so the refusal is the comparison and not a blanket one.
        @Test
        void aSnapshotStampedByThisStorage_isRestored() {
            var store = inMemoryMetadataStore("t28bsame");

            mutate(store, "one");
            managerWith(store, some(ID_U)).forceSnapshot();

            var restored = managerWith(store, some(ID_U)).restoreFromLatest().unwrap();

            assertThat(restored.isPresent()).isTrue();
            assertThat(restored.unwrap().storageId()).isEqualTo(some(ID_U));
        }

        /// T28b(c) `[guess]`: a snapshot from before identities existed carries none; with an id configured it is
        /// restored. AD1 may want it refused; flip this assertion then.
        @Test
        void anUnstampedSnapshotUnderAConfiguredId_isRestored() {
            var store = inMemoryMetadataStore("t28bc1");

            mutate(store, "one");
            managerWith(store, none()).forceSnapshot();

            var restored = managerWith(store, some(ID_U)).restoreFromLatest().unwrap();

            assertThat(restored.isPresent()).as("one side absent is accepted").isTrue();
            assertThat(restored.unwrap().storageId()).isEqualTo(none());
        }

        /// T28b(c) `[guess]`: a stamped snapshot with no id configured (the dark state until AD1) is restored.
        @Test
        void aStampedSnapshotWithNoConfiguredId_isRestored() {
            var store = inMemoryMetadataStore("t28bc2");

            mutate(store, "one");
            managerWith(store, some(ID_U)).forceSnapshot();

            var restored = managerWith(store, none()).restoreFromLatest().unwrap();

            assertThat(restored.isPresent()).as("one side absent is accepted").isTrue();
            assertThat(restored.unwrap().storageId()).isEqualTo(some(ID_U));
        }
    }

    @Nested
    class ReservedLogNames {
        /// T28c: the storage's own file names are not log names.
        @Test
        void theStorageOwnNames_areRefused() {
            var storage = instanceWithLogs();

            for (var name : List.of("STORAGE_ID", "CLAIM", "LOCK", "aside/x", "aside")) {
                var opened = storage.openLog(name);

                assertThat(opened.isFailure()).as("openLog(%s) is refused", name).isTrue();
                opened.onFailure(cause -> assertThat(cause).as(name).isInstanceOf(StorageError.ReservedLogName.class));
            }
        }

        /// T28c controls: stream logs are `<stream>@<incarnation>/<partition>`, so a name that merely starts
        /// with a reserved word is an ordinary log.
        @Test
        void streamLogNames_startingWithAReservedWord_stayLegal() {
            var storage = instanceWithLogs();

            for (var name : List.of("s@1/0", "aside@1/0", "LOCKS/0", "asides")) {
                var opened = storage.openLog(name);

                assertThat(opened.isSuccess()).as("openLog(%s) opens", name).isTrue();
                opened.onSuccess(AppendLog::close);
            }
        }

        /// `[guess]`: the form of the rule is the first `/`-segment, case-sensitive. The spec names `openLog` and
        /// the four names, not the form; flip this test when it is ruled.
        @Test
        void reservedForm_isTheFirstSegment_caseSensitive() {
            var storage = instanceWithLogs();

            assertThat(storage.openLog("lock/0").isSuccess()).as("lower-case is not reserved").isTrue();
            assertThat(storage.openLog("claim").isSuccess()).as("lower-case is not reserved").isTrue();
            assertThat(storage.openLog("a/aside").isSuccess()).as("only the FIRST segment is reserved").isTrue();
            assertThat(storage.openLog("LOCK/0").isFailure()).as("the first segment is").isTrue();
        }
    }

    @Nested
    class DurablePut {
        /// T28d (A14, green at base): the disk write fails, so the put fails and no record claims the block.
        @Test
        void aFailingBlockWrite_failsThePut_andLeavesNoRecord() {
            var store = inMemoryMetadataStore("t28d-write");
            Fn2<Result<Unit>, Path, byte[]> failing = (_, _) -> Causes.cause("disk write failed").result();
            var disk = LocalDiskTier.localDiskTier(dir.resolve("blocks"), 1 << 20, timeSpan(30).seconds(), none(), some(failing), none())
                                    .unwrap();

            assertPutFailsLeavingNoRecord(store, disk);
        }

        /// T28d (A14, green at base): the directory force after the rename fails, so the put fails and no
        /// record claims the block.
        @Test
        void aFailingDirectoryForce_failsThePut_andLeavesNoRecord() {
            var store = inMemoryMetadataStore("t28d-force");
            Fn1<Result<Unit>, Path> failing = _ -> Causes.cause("directory force failed").result();
            var disk = LocalDiskTier.localDiskTier(dir.resolve("blocks"), 1 << 20, timeSpan(30).seconds(), none(), none(), some(failing))
                                    .unwrap();

            assertPutFailsLeavingNoRecord(store, disk);
        }

        private void assertPutFailsLeavingNoRecord(MetadataStore store, LocalDiskTier disk) {
            var storage = StorageInstance.storageInstance("t28d", List.of(MemoryTier.memoryTier(1 << 20), disk), store);
            var id = BlockId.blockId(CONTENT).unwrap();

            assertThat(storage.put(CONTENT).await().isFailure()).as("the put fails").isTrue();
            assertThat(store.getLifecycle(id).isPresent()).as("no record for a block stored nowhere").isFalse();
        }
    }

    private SnapshotManager managerWith(MetadataStore store, Option<String> storageId) {
        return SnapshotManager.snapshotManager(store, snapshotConfig(dir.resolve("snap"), 100, 600_000, 5, NODE_ID).withStorageId(storageId));
    }

    private StorageInstance instanceWithLogs() {
        return StorageInstance.storageInstance("t28c",
                                               List.of(MemoryTier.memoryTier(1 << 20)),
                                               inMemoryMetadataStore("t28c"),
                                               WritePolicy.WRITE_THROUGH,
                                               some(dir.resolve("logs")));
    }

    private static void mutate(MetadataStore store, String content) {
        var id = BlockId.blockId(content.getBytes(StandardCharsets.UTF_8)).unwrap();

        store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
    }

    private Path latestFile() {
        return dir.resolve("snap").resolve(readLatestName());
    }

    private String readLatestName() {
        return read(dir.resolve("snap").resolve("LATEST")).trim();
    }

    private static List<String> headerLines(Path file) {
        return read(file).lines().takeWhile(line -> !line.isEmpty()).toList();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    private static void assertMismatch(Cause cause, String expected, String found) {
        assertThat(cause).isInstanceOf(SnapshotError.StorageIdMismatch.class);
        assertThat(((SnapshotError.StorageIdMismatch) cause).expected()).isEqualTo(expected);
        assertThat(((SnapshotError.StorageIdMismatch) cause).found()).isEqualTo(found);
    }
}
