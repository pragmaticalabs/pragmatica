package org.pragmatica.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import org.apache.logging.log4j.Level;
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
import org.pragmatica.testlogging.LogCapture;

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
/// restored ([Restore#anUnstampedSnapshotUnderAConfiguredId_isRestored]). A log name is accepted only if it matches
/// one grammar, and only then is its first segment compared, case-insensitively, to the reserved names
/// ([ReservedLogNames#everyFormOfAReservedOrMalformedName_isRefused_withATypedError]).
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
    class FormatBoundary {
        /// #2836: `format=3` marks a stamped snapshot, `format=2` an unstamped one (byte-for-byte as before).
        @Test
        void aStampedSnapshotIsFormat3_andAnUnstampedOneStaysFormat2() {
            var stamped = inMemoryMetadataStore("fmt3");
            var unstamped = inMemoryMetadataStore("fmt2");

            mutate(stamped, "one");
            mutate(unstamped, "one");
            managerWith(stamped, some(ID_U)).forceSnapshot();

            assertThat(headerLines(latestFile())).contains("format=3");

            managerWith(unstamped, none(), "snap2").forceSnapshot();

            assertThat(headerLines(dir.resolve("snap2").resolve(read(dir.resolve("snap2").resolve("LATEST")).trim()))).contains("format=2");
        }

        /// Lane 1b: the write-time read-back compares the storage id as well as the format, so a file whose header
        /// line was dropped, or whose id or format differs from what was captured, is not accepted as written.
        @Test
        void theReadBack_comparesTheStorageIdAndTheFormat() {
            var captured = capturedSnapshot(some(ID_U));
            var content = DefaultSnapshotManager.serializeSnapshot(captured);

            assertThat(SnapshotReadBack.matches(captured, content)).as("control: the intact file reads back").isTrue();
            assertThat(SnapshotReadBack.matches(captured, content.replace("storageId=" + ID_U + "\n", "")))
                    .as("a dropped storageId line is not a read-back of the capture").isFalse();
            assertThat(SnapshotReadBack.matches(captured, content.replace("storageId=" + ID_U, "storageId=" + ID_V)))
                    .as("another id is not a read-back of the capture").isFalse();
            assertThat(SnapshotReadBack.matches(captured, content.replace("format=3", "format=2")))
                    .as("another format is not a read-back of the capture").isFalse();
            assertThat(SnapshotReadBack.matches(capturedSnapshot(none()), content))
                    .as("an id the capture does not carry is not a read-back of it").isFalse();
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

            assertThat(restored.isPresent())
                    .as("AD1: a node with no storage id must refuse a stamped snapshot; replace this tripwire")
                    .isTrue();
            assertThat(restored.unwrap().storageId()).isEqualTo(some(ID_U));
        }
    }

    @Nested
    class StampLineIntegrity {
        /// A7(a): a format-3 file whose storageId line is removed, or whose key is corrupted, is refused -- under the
        /// configured id AND under none -- instead of being read as unstamped.
        @Test
        void aStampedSnapshotWhoseIdLineIsGone_isRefused() {
            for (var damage : List.of("removed", "storageJd=")) {
                var store = inMemoryMetadataStore("t28a7-" + damage);

                mutate(store, "one");
                managerWith(store, some(ID_U)).forceSnapshot();
                rewriteLatest(line -> line.equals("storageId=" + ID_U) ? damaged(damage) : line);

                for (var configured : List.of(some(ID_V), some(ID_U), Option.<String>none())) {
                    managerWith(store, configured).restoreFromLatest()
                                                  .onSuccess(_ -> fail("a stamped snapshot without its id line restored (" + damage + ")"))
                                                  .onFailure(cause -> assertThat(cause).isInstanceOf(SnapshotError.NothingRestorable.class));
                }
            }
        }

        /// The boundary, pinned from the other side: a `format=2` file has never required the line, so it restores
        /// as unstamped (as the legacy files do).
        @Test
        void aFormat2SnapshotWithNoIdLine_restoresUnstamped() {
            var store = inMemoryMetadataStore("t28a7-f2");

            mutate(store, "one");
            managerWith(store, some(ID_U)).forceSnapshot();
            rewriteLatest(line -> line.equals("storageId=" + ID_U) ? null : line.equals("format=3") ? "format=2" : line);

            var restored = managerWith(store, some(ID_V)).restoreFromLatest().unwrap();

            assertThat(restored.unwrap().storageId()).isEqualTo(none());
        }

        /// A7(b), parse side: an id with a control character or an empty value in the header is refused, not
        /// compared.
        @Test
        void aMalformedIdInTheHeader_isRefused() {
            for (var bad : List.of("storage-U\r", "storage\u0001U", "")) {
                var store = inMemoryMetadataStore("t28a7-parse");

                mutate(store, "one");
                managerWith(store, some(ID_U)).forceSnapshot();
                rewriteLatest(line -> line.equals("storageId=" + ID_U) ? "storageId=" + bad : line);

                managerWith(store, some(ID_U)).restoreFromLatest()
                                              .onSuccess(_ -> fail("a snapshot with a malformed id restored"))
                                              .onFailure(cause -> assertThat(cause).isInstanceOf(SnapshotError.NothingRestorable.class));
            }
        }

        /// A7(b), mint side: every id the config accepts is one the storage restores from its own snapshot.
        @Test
        void everyIdTheConfigMints_isRestoredFromItsOwnSnapshot() {
            var ids = List.of(ID_U, "01JABCDEFGHJKMNPQRSTVWXYZ0", "with space", " lead", "trail ", "a=b", "a|b", "#hash",
                              "\u00fcn\u00ef-\u4e2d", "emoji \uD83D\uDE00");

            for (var index = 0; index < ids.size(); index++) {
                var id = ids.get(index);
                var snapshotDirectory = "snap-mint-" + index;
                var store = inMemoryMetadataStore("t28a7-mint" + index);

                mutate(store, "one");
                managerWith(store, some(id), snapshotDirectory).forceSnapshot();

                var restored = managerWith(store, some(id), snapshotDirectory).restoreFromLatest().unwrap();

                assertThat(restored.unwrap().storageId()).as("id '%s'", id).isEqualTo(some(id));
            }
        }

        /// A7(b), mint side: an id the header could not carry is refused where it is minted, with a typed error.
        @Test
        void anIdTheHeaderCouldNotCarry_isRefusedAtMint() {
            for (var id : List.of("", "a\nb", "a\rb", "a\u0000b", "a\tb", "\u0085", "\u007f", "\uD800")) {
                snapshotConfig(dir.resolve("snap"), NODE_ID).withStorageId(some(id))
                                                            .onSuccess(_ -> fail("minted '" + id + "'"))
                                                            .onFailure(cause -> assertThat(cause).isInstanceOf(SnapshotError.InvalidStorageId.class));
            }
        }

        /// The `Snapshot restore failed` WARN is the only place the cause of a refused stamped file reaches an operator:
        /// the restore itself returns `NothingRestorable`, which does not name it. The WARN carries the missing-line
        /// text and the unusable-id text.
        @Test
        void theCauseOfARefusedStampedFile_reachesTheOperatorLog() {
            var expectedTexts = Map.of("removed", "requires a storageId header line", "empty", "is not usable");

            for (var damage : List.of("removed", "empty")) {
                var store = inMemoryMetadataStore("t28a7-log-" + damage);

                mutate(store, "one");
                managerWith(store, some(ID_U)).forceSnapshot();
                rewriteLatest(line -> line.equals("storageId=" + ID_U) ? damagedForLog(damage) : line);

                try (var capture = LogCapture.capture(DefaultSnapshotManager.class, Level.ALL, true)) {
                    var restored = managerWith(store, some(ID_U)).restoreFromLatest();

                    assertThat(restored.isFailure()).as(damage).isTrue();
                    assertThat(capture.messages(Level.WARN)).as(damage)
                                                            .anySatisfy(message -> assertThat(message).contains("Snapshot restore failed")
                                                                                                      .contains(expectedTexts.get(damage)));
                }
            }
        }

        private static String damagedForLog(String damage) {
            return damage.equals("removed") ? null : "storageId=";
        }

        private void rewriteLatest(UnaryOperator<String> editLine) {
            var file = latestFile();
            var edited = read(file).lines().map(editLine).filter(Objects::nonNull).collect(Collectors.joining("\n", "", "\n"));

            try {
                Files.writeString(file, edited);
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        private String damaged(String damage) {
            return damage.equals("removed") ? null : damage + ID_U;
        }
    }

    @Nested
    class ConfigAndSnapshotCarry {
        /// m01: the six-argument constructor is the pre-#2836 shape and carries no id.
        @Test
        void theLegacyMetadataSnapshotConstructor_carriesNoId() {
            var snapshot = new MetadataSnapshot(1L, 0L, NODE_ID, List.of(), Map.of(), new byte[0]);

            assertThat(snapshot.storageId()).isEqualTo(none());
        }

        /// m02: resealing a snapshot (a legacy-hash file restored) keeps the id it carries.
        @Test
        void aResealedSnapshot_keepsItsStorageId() {
            var resealed = capturedSnapshot(some(ID_U)).resealed();

            assertThat(resealed.storageId()).isEqualTo(some(ID_U));
            assertThat(resealed.isValid()).isTrue();
        }

        /// c01, c02: neither default overload carries an id or a sentinel.
        @Test
        void theDefaultOverloads_carryNeitherSentinelNorId() {
            for (var config : List.of(snapshotConfig(dir, NODE_ID), snapshotConfig(dir, 10, 20, 3, NODE_ID))) {
                assertThat(config.volumeSentinel()).isEqualTo(none());
                assertThat(config.storageId()).isEqualTo(none());
            }
        }

        /// c03: setting the sentinel keeps the id (and every other field).
        @Test
        void withVolumeSentinel_keepsTheId() {
            var sentinel = dir.resolve("sentinel");
            var config = snapshotConfig(dir, 10, 20, 3, NODE_ID).withStorageId(some(ID_U)).unwrap().withVolumeSentinel(some(sentinel));

            assertThat(config.storageId()).isEqualTo(some(ID_U));
            assertThat(config.volumeSentinel()).isEqualTo(some(sentinel));
            assertThat(config.mutationThreshold()).isEqualTo(10);
            assertThat(config.maxIntervalMillis()).isEqualTo(20L);
            assertThat(config.retentionCount()).isEqualTo(3);
        }

        /// c04, c05: setting the id keeps the sentinel (and every other field).
        @Test
        void withStorageId_keepsTheSentinel() {
            var sentinel = dir.resolve("sentinel");
            var config = snapshotConfig(dir, 10, 20, 3, NODE_ID).withVolumeSentinel(some(sentinel)).withStorageId(some(ID_U)).unwrap();

            assertThat(config.storageId()).isEqualTo(some(ID_U));
            assertThat(config.volumeSentinel()).isEqualTo(some(sentinel));
            assertThat(config.mutationThreshold()).isEqualTo(10);
            assertThat(config.nodeId()).isEqualTo(NODE_ID);
        }
    }

    @Nested
    class OperatorMessages {
        /// e01, e02: the mismatch names the expected id, the found id, and the remedy, in that order of meaning.
        @Test
        void storageIdMismatch_namesExpectedFoundAndTheRemedy() {
            assertThat(SnapshotError.StorageIdMismatch.storageIdMismatch("exp-V", "fnd-U").message())
                    .isEqualTo("Snapshot was written by storage fnd-U, but this storage is exp-V; refusing to start with another storage's metadata. Mount the original volume");
        }

        /// r01, r02: the reserved-name error names the offending name.
        @Test
        void reservedLogName_namesTheOffendingName() {
            assertThat(StorageError.ReservedLogName.reservedLogName("LOCK/x").message())
                    .isEqualTo("Append-log name 'LOCK/x' is reserved for the storage's own files");
        }

        @Test
        void invalidLogName_namesTheOffendingName() {
            assertThat(StorageError.InvalidLogName.invalidLogName("a//b").message())
                    .isEqualTo("Invalid append-log name 'a//b': must be a relative path under the log root");
        }

        @Test
        void storageIdMissing_namesTheFormatAndTheRemedy() {
            assertThat(SnapshotError.StorageIdMissing.storageIdMissing("3").message())
                    .isEqualTo("Snapshot format 3 requires a storageId header line and this file has none; the header was removed or corrupted. Restore the snapshot directory from a copy, or mount the original volume");
        }

        @Test
        void invalidStorageId_showsControlCharactersEscaped() {
            assertThat(SnapshotError.InvalidStorageId.invalidStorageId("a\nb").message())
                    .isEqualTo("Storage id 'a\\u000ab' is not usable: it must be non-empty, free of control characters and valid UTF-8");
        }
    }

    @Nested
    class ReservedLogNames {
        /// T28c / A8: ONE rule. A name is accepted only if it matches the grammar (segments of letters, digits, `_`,
        /// `-` and, after the first character, `.`, `@`, `:`; single `/` between them; ASCII only; no `.` or `..`
        /// segment), and only then is its first segment compared, case-insensitively, to the reserved names. Every
        /// form below is refused with a typed error, and none of them creates a file. Grammar comes first: a reserved
        /// first segment followed by a malformed rest (`LOCK/..`) is a grammar failure, not a reserved-name one.
        @Test
        void everyFormOfAReservedOrMalformedName_isRefused_withATypedError() {
            var storage = instanceWithLogs();
            var reserved = StorageError.ReservedLogName.class;
            var invalid = StorageError.InvalidLogName.class;
            var refused = List.<Map.Entry<String, Class<?>>>of(Map.entry("STORAGE_ID", reserved),
                                                               Map.entry("CLAIM", reserved),
                                                               Map.entry("LOCK", reserved),
                                                               Map.entry("aside", reserved),
                                                               Map.entry("aside/x", reserved),
                                                               Map.entry("storage_id", reserved),
                                                               Map.entry("Aside/y", reserved),
                                                               Map.entry("ASIDE/y", reserved),
                                                               Map.entry("lock/0", reserved),
                                                               Map.entry("claim", reserved),
                                                               Map.entry("LOCK/x/y", reserved),
                                                               Map.entry("LOCK/..", invalid),
                                                               Map.entry("aside/a b", invalid),
                                                               Map.entry("Claim/x\\y", invalid),
                                                               Map.entry("STORAGE_ID//x", invalid),
                                                               Map.entry("lock/./x", invalid),
                                                               Map.entry("./STORAGE_ID", invalid),
                                                               Map.entry("./aside/y", invalid),
                                                               Map.entry("x/../CLAIM", invalid),
                                                               Map.entry("x/../aside/z", invalid),
                                                               Map.entry("\uFF33TORAGE_ID", invalid),
                                                               Map.entry("\u0430side/y", invalid),
                                                               Map.entry("aside\\y", invalid),
                                                               Map.entry(" aside/y", invalid),
                                                               Map.entry("aside /y", invalid),
                                                               Map.entry("a b", invalid),
                                                               Map.entry("a//b", invalid),
                                                               Map.entry("a/", invalid),
                                                               Map.entry("/a", invalid),
                                                               Map.entry("/", invalid),
                                                               Map.entry(".", invalid),
                                                               Map.entry("..", invalid),
                                                               Map.entry("a/..", invalid),
                                                               Map.entry("a\nb", invalid),
                                                               Map.entry("", invalid));

            for (var entry : refused) {
                var name = entry.getKey();
                var opened = storage.openLog(name);

                assertThat(opened.isFailure()).as("openLog(%s) is refused", name).isTrue();
                opened.onFailure(cause -> assertThat(cause).as(name).isInstanceOf(entry.getValue()));
                opened.onFailure(cause -> assertThat(cause.message()).as(name).contains("'" + name + "'"));
            }

            assertThat(storage.listLogs().unwrap()).as("no refused name created a log").isEmpty();
        }

        /// T28c controls: stream logs are `<stream>@<incarnation>/<partition>` and a topic stream is `topic:<address>`,
        /// so a name that merely starts with a reserved word is an ordinary log: the FIRST SEGMENT of `LOCK:x` or `aside@1/x` is the whole `LOCK:x` or `aside@1`, which is not reserved.
        @Test
        void streamLogNames_startingWithAReservedWord_stayLegal() {
            var storage = instanceWithLogs();

            for (var name : List.of("s@1/0", "aside@1/0", "LOCK:x", "claim@2/x", "LOCKS/0", "asides", "a/aside", "topic:orders.dlq/0", "a.b/c_d-e", "Ab-9_")) {
                var opened = storage.openLog(name);

                assertThat(opened.isSuccess()).as("openLog(%s) opens", name).isTrue();
                opened.onSuccess(AppendLog::close);
            }
        }

        /// r14: a legal name opens the file it names, `<logRoot>/<name>.wal`, and no other.
        @Test
        void aLegalName_opensTheFileItNames() {
            var storage = instanceWithLogs();

            storage.openLog("a/b").onSuccess(AppendLog::close);

            assertThat(Files.exists(dir.resolve("logs").resolve("a").resolve("b.wal"))).isTrue();
            assertThat(storage.listLogs().unwrap()).containsExactly("a/b");
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
        return managerWith(store, storageId, "snap");
    }

    private SnapshotManager managerWith(MetadataStore store, Option<String> storageId, String snapshotDirectory) {
        return SnapshotManager.snapshotManager(store,
                                               snapshotConfig(dir.resolve(snapshotDirectory), 100, 600_000, 5, NODE_ID).withStorageId(storageId)
                                                                                                                       .unwrap());
    }

    private static MetadataSnapshot capturedSnapshot(Option<String> storageId) {
        var hash = MetadataSnapshot.render(1L, List.of(), Map.of()).hash();

        return new MetadataSnapshot(1L, 0L, NODE_ID, List.of(), Map.of(), hash, storageId);
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
