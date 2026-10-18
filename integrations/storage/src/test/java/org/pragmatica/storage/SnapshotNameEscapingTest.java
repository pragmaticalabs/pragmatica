package org.pragmatica.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.InMemoryMetadataStore.inMemoryMetadataStore;
import static org.pragmatica.storage.MetadataSnapshot.metadataSnapshot;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.storage.SnapshotManager.snapshotManager;

/// #2257: a ref name is user data. Every name must come back from a snapshot exactly as it went in, a
/// snapshot that cannot be read back must fail when it is WRITTEN, and a file written before the fix
/// must still restore.
class SnapshotNameEscapingTest {
    private static final String NODE_ID = "node-escape";
    private static final HexFormat HEX = HexFormat.of();

    private static void failed(Cause cause) {
        Assertions.fail(cause.message());
    }

    private static void unexpected(Object value) {
        Assertions.fail("Expected a failure but got: " + value);
    }

    private static BlockId blockIdOf(String content) {
        return BlockId.blockId(content.getBytes(StandardCharsets.UTF_8)).unwrap();
    }

    @Nested
    class NameRoundTrip {
        @TempDir
        Path tempDir;

        private InMemoryMetadataStore store;
        private SnapshotConfig config;
        private SnapshotManager manager;

        @BeforeEach
        void setUp() {
            store = inMemoryMetadataStore("escape");
            config = snapshotConfig(tempDir, 100, 600_000, 5, NODE_ID);
            manager = snapshotManager(store, config);
        }

        @Test
        void restore_keepsName_withEquals() {
            assertNameRoundTrips("invoice?id=42");
            assertNameRoundTrips("dGVzdA==");
            assertNameRoundTrips("=");
        }

        @Test
        void restore_keepsName_withPipe() {
            assertNameRoundTrips("a|b");
            assertNameRoundTrips("|");
            assertNameRoundTrips("report|0123abcd");
        }

        @Test
        void restore_keepsName_withLeadingHash() {
            assertNameRoundTrips("#");
            assertNameRoundTrips("# Refs");
            assertNameRoundTrips("# Lifecycles");
        }

        @Test
        void restore_keepsName_withNewline() {
            assertNameRoundTrips("line\nbreak");
            assertNameRoundTrips("crlf\r\nbreak");
            assertNameRoundTrips("\n");
            assertNameRoundTrips("nul\0inside");
        }

        @Test
        void restore_keepsName_whenEmpty() {
            assertNameRoundTrips("");
        }

        @Test
        void restore_keepsName_withNonAscii() {
            assertNameRoundTrips("naïve-名前-😀");
            assertNameRoundTrips("café");
        }

        @Test
        void restore_keepsName_withLoneSurrogate() {
            assertNameRoundTrips("lone\uD800surrogate");
            assertNameRoundTrips("\uDFFFtail");
        }

        @Test
        void restore_keepsName_withPercentAndEscapeLookalikes() {
            assertNameRoundTrips("100%");
            assertNameRoundTrips("%41");
            assertNameRoundTrips("%u0041");
            assertNameRoundTrips("a b\tc");
        }

        @Test
        void restore_keepsName_withCursorShapeAndSeparator() {
            assertNameRoundTrips("cursors/org.example:app#onEvent/ns:orders:1/0");
            assertNameRoundTrips("cursors/group=a|b/orders/0");
        }

        @Test
        void restore_keepsEveryName_whenSnapshotHoldsAllOfThem() {
            var names = List.of("plain", "a=b", "a|b", "#x", "n\nl", "", "名前", "\uD800");
            var id = blockIdOf("shared");

            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
            names.forEach(name -> store.putRef(name, id));
            manager.snapshotNow().onFailure(SnapshotNameEscapingTest::failed);

            var restored = restoreFresh();

            assertThat(restored.refs().keySet()).containsExactlyInAnyOrderElementsOf(names);
            assertThat(restored.isValid()).isTrue();
        }

        /// A name is user data of any length. The encoder and the validation of the read-back must not cost
        /// stack or time per character: the first version of the decoder ran a regex with a repeated
        /// alternation and threw StackOverflowError on every snapshot write for names of a few thousand
        /// characters, while the unescaped format took a 1 MiB name.
        @Test
        void restore_keepsName_at8KiB() {
            assertNameRoundTrips("a".repeat(8 * 1024));
            assertNameRoundTrips("=".repeat(8 * 1024));
            assertNameRoundTrips("\u540d".repeat(8 * 1024));
            assertNameRoundTrips("\uD800".repeat(8 * 1024));
        }

        @Test
        void restore_keepsName_at1MiB() {
            assertNameRoundTrips("a".repeat(1024 * 1024));
            assertNameRoundTrips("=".repeat(1024 * 1024));
            assertNameRoundTrips("\u540d".repeat(256 * 1024));
        }

        @Test
        void restore_keepsName_at16MiB() {
            assertNameRoundTrips("a".repeat(16 * 1024 * 1024));
        }

        /// Written on the test thread, restored on a thread with a small stack: restore cost in stack must
        /// not depend on the name either (the fall-back to older files never runs if a Throwable escapes).
        @Test
        void restore_succeedsOnSmallStack_forLongName() throws Exception {
            var name = "=".repeat(20_000);
            var id = blockIdOf("small-stack");

            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
            store.putRef(name, id);
            manager.snapshotNow().onFailure(SnapshotNameEscapingTest::failed);

            var restored = new AtomicReference<MetadataSnapshot>();
            var thrown = new AtomicReference<Throwable>();
            var thread = new Thread(null, () -> restoreOnSmallStack(restored, thrown), "small-stack-restore", 256 * 1024);

            thread.start();
            thread.join();

            assertThat(thrown.get()).isNull();
            assertThat(restored.get().refs()).containsKey(name);
        }

        private void restoreOnSmallStack(AtomicReference<MetadataSnapshot> restored,
                                         AtomicReference<Throwable> thrown) {
            try {
                restored.set(restoreFresh());
            } catch (Throwable t) {
                thrown.set(t);
            }
        }

        @Test
        void restore_keepsEveryLifecycleField_acrossMultipleTiers() {
            var id = blockIdOf("all-fields");
            var lifecycle = BlockLifecycle.blockLifecycle(id, EnumSet.of(TierLevel.MEMORY, TierLevel.LOCAL_DISK), 3, 111L, 222L, 5, 333L);

            store.createLifecycle(lifecycle);
            manager.snapshotNow().onFailure(SnapshotNameEscapingTest::failed);

            assertThat(restoreFresh().lifecycles()).containsExactly(lifecycle);
        }

        /// Map.copyOf scrambles iteration order between the writer's map and the one built from the file, so
        /// a hash that depended on that order would fail restore of a perfectly good snapshot.
        @Test
        void restore_validatesSnapshot_whenManyRefsReorderOnRead() {
            var id = blockIdOf("many");

            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
            IntStream.range(0, 300).forEach(i -> store.putRef("ref-" + i, id));
            manager.snapshotNow().onFailure(SnapshotNameEscapingTest::failed);

            var restored = restoreFresh();

            assertThat(restored.refs()).hasSize(300);
            assertThat(restored.isValid()).isTrue();
        }

        private void assertNameRoundTrips(String name) {
            var id = blockIdOf("block-for-" + name.length());

            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
            store.putRef(name, id);
            manager.snapshotNow().onFailure(SnapshotNameEscapingTest::failed);

            var restored = restoreFresh();
            var freshStore = inMemoryMetadataStore("restored");

            freshStore.restoreLifecycles(restored.lifecycles());
            freshStore.restoreRefs(restored.refs());

            assertThat(restored.refs()).containsKey(name);
            assertThat(freshStore.resolveRef(name).isPresent()).isTrue();
            assertThat(restored.isValid()).isTrue();

            store.removeRef(name);
        }

        private MetadataSnapshot restoreFresh() {
            return snapshotManager(inMemoryMetadataStore("fresh"), config).restoreFromLatest()
                                                                          .onFailure(SnapshotNameEscapingTest::failed)
                                                                          .unwrap()
                                                                          .unwrap();
        }
    }

    @Nested
    class RetentionLifecycle {
        @TempDir
        Path tempDir;

        /// SA-01 lifecycle: three good snapshots, then a bad name, then one snapshot per step. Before the fix
        /// the first four steps silently rolled back to an older epoch and the fifth refused to boot.
        @Test
        void restore_returnsNewestEpochWithEveryRef_whileBadNameIsRetained() {
            var store = inMemoryMetadataStore("lifecycle");
            var config = snapshotConfig(tempDir, 1, 600_000, 5, NODE_ID);
            var manager = snapshotManager(store, config);

            for (var i = 0; i < 3; i++) {
                addBlockAndRef(store, "good-" + i);
                manager.forceSnapshot();
            }

            addBlockAndRef(store, "report=final|v2");

            for (var step = 1; step <= 7; step++) {
                addBlockAndRef(store, "after-" + step);
                manager.forceSnapshot();

                var restored = snapshotManager(inMemoryMetadataStore("boot-" + step), config).restoreFromLatest()
                                                                                         .onFailure(SnapshotNameEscapingTest::failed)
                                                                                         .unwrap()
                                                                                         .unwrap();

                assertThat(restored.epoch()).as("step %d", step).isEqualTo(store.currentEpoch());
                assertThat(restored.refs()).as("step %d", step).containsKey("report=final|v2");
                assertThat(restored.refs()).as("step %d", step).hasSize(store.listAllRefs().size());
            }
        }

        private static void addBlockAndRef(InMemoryMetadataStore store, String name) {
            var id = blockIdOf("content-" + name);

            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
            store.putRef(name, id);
        }
    }

    @Nested
    class IntegrityHash {
        @TempDir
        Path tempDir;

        private InMemoryMetadataStore store;
        private SnapshotConfig config;

        @BeforeEach
        void setUp() {
            store = inMemoryMetadataStore("hash");
            config = snapshotConfig(tempDir, 100, 600_000, 5, NODE_ID);
        }

        @Test
        void contentHash_differs_forNamesThatConcatenateAlike() {
            var id = blockIdOf("x");
            var first = metadataSnapshot(1L, NODE_ID, List.of(), Map.of("ab", id, "c", id));
            var second = metadataSnapshot(1L, NODE_ID, List.of(), Map.of("a", id, "bc", id));

            assertThat(first.contentHash()).isNotEqualTo(second.contentHash());
        }

        @Test
        void contentHash_isIndependentOfLifecycleOrder() {
            var first = BlockLifecycle.blockLifecycle(blockIdOf("one"), TierLevel.MEMORY);
            var second = BlockLifecycle.blockLifecycle(blockIdOf("two"), TierLevel.MEMORY);

            assertThat(metadataSnapshot(1L, NODE_ID, List.of(first, second), Map.of()).contentHash())
                .isEqualTo(metadataSnapshot(1L, NODE_ID, List.of(second, first), Map.of()).contentHash());
        }

        /// The delimiter between lifecycle lines is part of the hash. Moving the first two digits of one block
        /// id onto the end of the previous line (orphanedAt 7 -> 712, id one byte short) leaves the sorted,
        /// concatenated text identical, so only a delimiter keeps the edit from validating. The pair is chosen
        /// so the edit does not change the sort order of the two lines.
        @Test
        void restore_rejectsSnapshot_whenDigitsMoveAcrossLifecycleLines() throws IOException {
            var candidates = Stream.iterate(0, i -> i + 1)
                                   .map(i -> orphanedLifecycle(blockIdOf("digits-" + i)))
                                   .filter(lc -> lc.blockId().hexString().substring(0, 2).chars().allMatch(Character::isDigit))
                                   .limit(40)
                                   .toList();
            var pair = candidates.stream()
                                 .flatMap(a -> candidates.stream().map(b -> List.of(a, b)))
                                 .filter(IntegrityHash::survivesDigitMove)
                                 .findFirst()
                                 .orElseThrow();
            var earlier = MetadataSnapshot.lifecycleLine(pair.get(0));
            var later = MetadataSnapshot.lifecycleLine(pair.get(1));

            pair.forEach(store::createLifecycle);
            snapshotManager(store, config).snapshotNow().onFailure(SnapshotNameEscapingTest::failed);
            var file = onlySnapshotFile();
            var content = Files.readString(file);

            assertThat(content).contains("\n" + earlier + "\n").contains("\n" + later + "\n");
            Files.writeString(file,
                              content.replace("\n" + earlier + "\n", "\n" + earlier + later.substring(0, 2) + "\n")
                                     .replace("\n" + later + "\n", "\n" + later.substring(2) + "\n"));

            assertRestoreRefused();
        }

        private static BlockLifecycle orphanedLifecycle(BlockId id) {
            return new BlockLifecycle(id, EnumSet.of(TierLevel.MEMORY), 1, 1L, 1L, 0, 7L);
        }

        private static boolean survivesDigitMove(List<BlockLifecycle> pair) {
            var earlier = MetadataSnapshot.lifecycleLine(pair.get(0));
            var later = MetadataSnapshot.lifecycleLine(pair.get(1));

            return earlier.compareTo(later) < 0 && (earlier + later.substring(0, 2)).compareTo(later.substring(2)) < 0;
        }

        @Test
        void restore_rejectsSnapshot_whenRefTargetIsEdited() throws IOException {
            var original = blockIdOf("original-target");
            var forged = blockIdOf("forged-target");

            writeSnapshotWith(original);
            replaceInOnlySnapshot("ref-name|" + original.hexString(), "ref-name|" + forged.hexString());

            assertRestoreRefused();
        }

        @Test
        void restore_rejectsSnapshot_whenLifecycleFieldIsEdited() throws IOException {
            var id = blockIdOf("original-target");

            writeSnapshotWith(id);
            replaceInOnlySnapshot(id.hexString() + "|MEMORY|1|", id.hexString() + "|MEMORY|9|");

            assertRestoreRefused();
        }

        @Test
        void restore_rejectsSnapshot_whenEpochIsEdited() throws IOException {
            writeSnapshotWith(blockIdOf("original-target"));
            var epoch = store.currentEpoch();

            replaceInOnlySnapshot("epoch=" + epoch + "\n", "epoch=" + (epoch + 100) + "\n");

            assertRestoreRefused();
        }

        @Test
        void restore_rejectsSnapshot_whenRefLineIsDuplicated() throws IOException {
            writeSnapshotWith(blockIdOf("original-target"));
            var file = onlySnapshotFile();
            var lines = Files.readString(file).split("\n");
            var refLine = lines[lines.length - 1];

            Files.writeString(file, Files.readString(file) + refLine + "\n");

            assertRestoreRefused();
        }

        @Test
        void restore_rejectsSnapshot_whenRefLineHasNoSeparator() throws IOException {
            writeSnapshotWith(blockIdOf("original-target"));
            var file = onlySnapshotFile();

            Files.writeString(file, Files.readString(file) + "orphan-token-without-target\n");

            assertRestoreRefused();
        }

        @Test
        void restore_rejectsSnapshot_whenRefNameTokenIsMalformed() throws IOException {
            writeSnapshotWith(blockIdOf("original-target"));
            var file = onlySnapshotFile();

            Files.writeString(file, Files.readString(file) + "bad%zzname|" + blockIdOf("y").hexString() + "\n");

            assertRestoreRefused();
        }

        @Test
        void restore_rejectsSnapshot_whenFormatVersionIsUnknown() throws IOException {
            writeSnapshotWith(blockIdOf("original-target"));

            replaceInOnlySnapshot("format=2\n", "format=3\n");

            assertRestoreRefused();
        }

        private void writeSnapshotWith(BlockId id) {
            store.createLifecycle(BlockLifecycle.blockLifecycle(id, TierLevel.MEMORY));
            store.putRef("ref-name", id);
            snapshotManager(store, config).snapshotNow().onFailure(SnapshotNameEscapingTest::failed);
        }

        private Path onlySnapshotFile() throws IOException {
            try (var files = Files.list(tempDir)) {
                return files.filter(p -> p.getFileName().toString().startsWith("snapshot-")).findFirst().orElseThrow();
            }
        }

        private void replaceInOnlySnapshot(String from, String to) throws IOException {
            var file = onlySnapshotFile();
            var content = Files.readString(file);

            assertThat(content).contains(from);
            Files.writeString(file, content.replace(from, to));
        }

        private void assertRestoreRefused() {
            snapshotManager(inMemoryMetadataStore("boot"), config).restoreFromLatest().onSuccess(SnapshotNameEscapingTest::unexpected);
        }
    }

    @Nested
    class LegacyFormat {
        @TempDir
        Path tempDir;

        /// A file exactly as the pre-#2257 writer produced it, hashed by an independent computation of the
        /// old rule (sorted block ids, then sorted ref names, concatenated).
        @Test
        void restore_readsUnversionedFile_andNextWriteIsVersioned() throws Exception {
            var config = snapshotConfig(tempDir, 100, 600_000, 5, NODE_ID);
            var idA = blockIdOf("legacy-a");
            var idB = blockIdOf("legacy-b");

            writeLegacyFile(7L, idA, idB);

            var manager = snapshotManager(inMemoryMetadataStore("legacy"), config);
            var restored = manager.restoreFromLatest().onFailure(SnapshotNameEscapingTest::failed).unwrap().unwrap();

            assertThat(restored.epoch()).isEqualTo(7L);
            assertThat(restored.nodeId()).isEqualTo("legacy-node");
            assertThat(restored.timestamp()).isEqualTo(1700000000000L);
            assertThat(restored.lifecycles()).extracting(BlockLifecycle::blockId).containsExactlyInAnyOrder(idA, idB);
            assertThat(restored.lifecycles()).allSatisfy(lc -> assertThat(lc.presentIn()).containsExactlyInAnyOrder(TierLevel.MEMORY, TierLevel.LOCAL_DISK));
            assertThat(restored.refs()).containsOnlyKeys("alpha", "beta", "gamma", "delta", "cursors/org.example:app/ns:orders:1/0");
            assertThat(restored.refs().get("alpha")).isEqualTo(idA);
            assertThat(restored.isValid()).isTrue();

            var store = inMemoryMetadataStore("rewrite");
            store.restoreLifecycles(restored.lifecycles());
            store.restoreRefs(restored.refs());
            store.restoreEpoch(restored.epoch());
            store.putRef("added=after|upgrade", idB);
            snapshotManager(store, config).snapshotNow().onFailure(SnapshotNameEscapingTest::failed);

            var latest = tempDir.resolve(Files.readString(tempDir.resolve("LATEST")).trim());
            assertThat(Files.readString(latest)).startsWith("format=2\n");

            var again = snapshotManager(inMemoryMetadataStore("again"), config).restoreFromLatest()
                                                                              .onFailure(SnapshotNameEscapingTest::failed)
                                                                              .unwrap()
                                                                              .unwrap();
            assertThat(again.refs()).containsKeys("alpha", "added=after|upgrade");
        }

        /// The old format keeps its known limits: a name it could not carry makes its file unrestorable, and
        /// the restore falls to the next retained file instead of installing a snapshot minus that name.
        @Test
        void restore_fallsBackToOlderFile_whenLegacyFileLostAName() throws Exception {
            var config = snapshotConfig(tempDir, 100, 600_000, 5, NODE_ID);
            var idA = blockIdOf("legacy-a");
            var idB = blockIdOf("legacy-b");

            writeLegacyFile(7L, idA, idB);
            writeLegacyFileWithBadName(9L, idA);

            var restored = snapshotManager(inMemoryMetadataStore("legacy"), config).restoreFromLatest()
                                                                                  .onFailure(SnapshotNameEscapingTest::failed)
                                                                                  .unwrap()
                                                                                  .unwrap();

            assertThat(restored.epoch()).isEqualTo(7L);
        }

        private void writeLegacyFile(long epoch, BlockId idA, BlockId idB) throws Exception {
            var refs = Map.of("alpha", idA,
                              "beta", idB,
                              "gamma", idA,
                              "delta", idB,
                              "cursors/org.example:app/ns:orders:1/0", idB);
            var lifecycleIds = Stream.of(idA, idB).sorted(Comparator.comparing(BlockId::hexString).reversed()).toList();
            var body = "epoch=" + epoch + "\ntimestamp=1700000000000\nnodeId=legacy-node\ncontentHash="
                       + HEX.formatHex(legacyHash(lifecycleIds, refs.keySet().stream().sorted().toList()))
                       + "\n\n# Lifecycles\n"
                       + lifecycleIds.stream().map(id -> id.hexString() + "|MEMORY,LOCAL_DISK|2|1700000000003|1700000000004|0|0\n").collect(Collectors.joining())
                       + "\n# Refs\n"
                       + refs.entrySet().stream().map(e -> e.getKey() + "|" + e.getValue().hexString() + "\n").collect(Collectors.joining());

            publish(epoch, body);
        }

        private void writeLegacyFileWithBadName(long epoch, BlockId idA) throws Exception {
            var hashedNames = List.of("a=b");
            var body = "epoch=" + epoch + "\ntimestamp=1700000000000\nnodeId=legacy-node\ncontentHash="
                       + HEX.formatHex(legacyHash(List.of(idA), hashedNames))
                       + "\n\n# Lifecycles\n"
                       + idA.hexString() + "|MEMORY|1|1700000000001|1700000000002|3|0\n"
                       + "\n# Refs\n"
                       + "a=b|" + idA.hexString() + "\n";

            publish(epoch, body);
        }

        private void publish(long epoch, String body) throws IOException {
            var name = "snapshot-" + String.format("%06d", epoch) + ".dat";

            Files.writeString(tempDir.resolve(name), body);
            Files.writeString(tempDir.resolve("LATEST"), name);
        }

        private byte[] legacyHash(List<BlockId> ids, List<String> names) throws Exception {
            var digest = MessageDigest.getInstance("SHA-256");

            ids.stream().map(BlockId::hexString).sorted().forEach(hex -> digest.update(hex.getBytes(StandardCharsets.UTF_8)));
            names.stream().sorted().forEach(name -> digest.update(name.getBytes(StandardCharsets.UTF_8)));

            return digest.digest();
        }
    }
}
