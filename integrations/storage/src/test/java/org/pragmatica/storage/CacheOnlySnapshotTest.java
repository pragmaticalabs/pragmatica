package org.pragmatica.storage;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.storage.SnapshotConfig.snapshotConfig;
import static org.pragmatica.storage.SnapshotManager.snapshotManager;

/// The cache-only marker in the snapshot format is ADDITIVE: a snapshot written before the marker existed
/// restores unchanged, and the marker costs an ordinary record nothing.
///
/// `snapshot-pre-cache-only/` holds the REAL files the encoder wrote at the commit before the marker (rc5
/// `ce5d94c16`), produced by running that encoder over three lifecycles and two refs -- not hand-written.
class CacheOnlySnapshotTest {
    private static final String FIXTURE = "/snapshot-pre-cache-only";
    private static final String NODE_ID = "fixture-node";

    @TempDir
    Path tempDir;

    private static BlockId idOf(String content) {
        return BlockId.blockId(content.getBytes(StandardCharsets.UTF_8)).unwrap();
    }

    private Path copyFixture() throws IOException, URISyntaxException {
        var source = Path.of(CacheOnlySnapshotTest.class.getResource(FIXTURE + "/LATEST").toURI()).getParent();
        var target = Files.createDirectories(tempDir.resolve("pre-marker"));

        for (var name : List.of("LATEST", "snapshot-000005.dat")) {
            Files.copy(source.resolve(name), target.resolve(name));
        }

        return target;
    }

    private static MetadataSnapshot restore(Path dir) {
        var store = MetadataStore.inMemoryMetadataStore("restore");

        return snapshotManager(store, snapshotConfig(dir, 100, 600_000, 5, NODE_ID)).restoreFromLatest()
                                                                                      .unwrap()
                                                                                      .unwrap();
    }

    private static BlockLifecycle byId(MetadataSnapshot snapshot, BlockId id) {
        return snapshot.lifecycles().stream().filter(lc -> lc.blockId().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void restore_snapshotWrittenBeforeTheMarker_restoresEveryRecordUnchanged() throws Exception {
        var snapshot = restore(copyFixture());

        assertThat(snapshot.isValid()).as("the stored hash still matches what the new encoder renders").isTrue();
        assertThat(snapshot.epoch()).isEqualTo(5);
        assertThat(snapshot.lifecycles()).hasSize(3);
        assertThat(snapshot.lifecycles()).noneMatch(BlockLifecycle::cacheOnly);

        var alpha = byId(snapshot, idOf("fixture-alpha"));

        assertThat(alpha.presentIn()).containsExactlyInAnyOrder(TierLevel.MEMORY, TierLevel.LOCAL_DISK);
        assertThat(alpha.refCount()).isEqualTo(2);
        assertThat(alpha.lastAccessedAt()).isEqualTo(1_700_000_000_000L);
        assertThat(alpha.createdAt()).isEqualTo(1_699_999_000_000L);
        assertThat(alpha.accessCount()).isEqualTo(7);
        assertThat(alpha.orphanedAt()).isZero();

        var bravo = byId(snapshot, idOf("fixture-bravo"));

        assertThat(bravo.isOrphaned()).as("an orphan stays an orphan across the upgrade").isTrue();
        assertThat(bravo.orphanedAt()).isEqualTo(1_700_000_100_000L);
        assertThat(byId(snapshot, idOf("fixture-charlie")).presentIn()).containsExactly(TierLevel.REMOTE);
        assertThat(snapshot.refs()).containsOnlyKeys("ref-alpha", "odd name|with=chars");
    }

    @Test
    void serialize_ofARestoredPreMarkerSnapshot_isByteIdenticalToTheOldFile() throws Exception {
        var dir = copyFixture();
        var original = Files.readString(dir.resolve("snapshot-000005.dat"), StandardCharsets.UTF_8);

        assertThat(DefaultSnapshotManager.serializeSnapshot(restore(dir)))
            .as("the new encoder writes an ordinary record exactly as the old one did")
            .isEqualTo(original);
    }

    @Test
    void restore_preMarkerSnapshot_installsRecordsIntoTheStore() throws Exception {
        var store = MetadataStore.inMemoryMetadataStore("install");
        var snapshot = snapshotManager(store, snapshotConfig(copyFixture(), 100, 600_000, 5, NODE_ID)).restoreFromLatest()
                                                                                                      .unwrap()
                                                                                                      .unwrap();

        store.restoreLifecycles(snapshot.lifecycles());

        assertThat(store.listAllLifecycles()).hasSize(3);
        assertThat(store.getLifecycle(idOf("fixture-alpha")).unwrap().cacheOnly()).isFalse();
    }

    @Test
    void snapshot_ofAMarker_roundTripsItAndLeavesOrdinaryLinesAtSevenFields() throws IOException {
        var store = MetadataStore.inMemoryMetadataStore("roundtrip");
        var owned = new BlockLifecycle(idOf("owned"), EnumSet.of(TierLevel.LOCAL_DISK), 1, 10L, 5L, 2, 0L);
        var marker = new BlockLifecycle(idOf("marker"), EnumSet.of(TierLevel.REMOTE, TierLevel.MEMORY), 0, 30L, 20L, 4, 0L, true);
        var dir = Files.createDirectories(tempDir.resolve("roundtrip"));
        var manager = snapshotManager(store, snapshotConfig(dir, 100, 600_000, 5, NODE_ID));

        store.createLifecycle(owned);
        store.createLifecycle(marker);

        var written = manager.snapshotNow().unwrap();
        var restored = restore(dir);
        var lines = Files.readString(dir.resolve("snapshot-000002.dat")).lines().toList();

        assertThat(written.isValid()).isTrue();
        assertThat(byId(restored, marker.blockId())).isEqualTo(marker);
        assertThat(byId(restored, owned.blockId())).isEqualTo(owned);
        assertThat(lines.stream().filter(l -> l.startsWith(owned.blockId().hexString())).findFirst().orElseThrow().split("\\|"))
            .hasSize(7);
        assertThat(lines.stream().filter(l -> l.startsWith(marker.blockId().hexString())).findFirst().orElseThrow())
            .endsWith("|CACHE_ONLY");
    }

    /// The fixture with `flag` appended to the alpha line and the header hash recomputed over the new body, so
    /// the file differs from a valid snapshot in the flag alone -- a restore that refuses it refuses the flag,
    /// not a hash that no longer matches.
    private Path fixtureWithFlagOnAlpha(String flag) throws Exception {
        var dir = copyFixture();
        var file = dir.resolve("snapshot-000005.dat");
        var original = Files.readString(file, StandardCharsets.UTF_8);
        var alphaLine = original.lines().filter(l -> l.startsWith(idOf("fixture-alpha").hexString())).findFirst().orElseThrow();
        var body = original.substring(original.indexOf("# Lifecycles")).replace(alphaLine, alphaLine + "|" + flag);
        var header = original.substring(0, original.indexOf("contentHash=")) + "contentHash=" + HexFormat.of().formatHex(hashOf(body));

        Files.writeString(file, header + "\n\n" + body, StandardCharsets.UTF_8);

        return dir;
    }

    /// The hash scheme of the body as written: `epoch=<n>\n`, each lifecycle line and `\n`, `#\n`, each ref line and `\n`.
    private static byte[] hashOf(String body) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var lifecycles = new ArrayList<String>();
        var refs = new ArrayList<String>();
        var target = lifecycles;

        for (var line : body.lines().toList()) {
            if (line.equals("# Refs")) {
                target = refs;
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                target.add(line);
            }
        }

        digest.update("epoch=5\n".getBytes(StandardCharsets.US_ASCII));
        lifecycles.forEach(line -> digest.update((line + "\n").getBytes(StandardCharsets.US_ASCII)));
        digest.update("#\n".getBytes(StandardCharsets.US_ASCII));
        refs.forEach(line -> digest.update((line + "\n").getBytes(StandardCharsets.US_ASCII)));

        return digest.digest();
    }

    @Test
    void restore_lineWithTheKnownFlagAndAMatchingHash_restoresTheMarker() throws Exception {
        var snapshot = restore(fixtureWithFlagOnAlpha("CACHE_ONLY"));

        assertThat(snapshot.isValid()).as("control: the recomputed hash is the one the encoder computes").isTrue();
        assertThat(byId(snapshot, idOf("fixture-alpha")).cacheOnly()).isTrue();
        assertThat(byId(snapshot, idOf("fixture-bravo")).cacheOnly()).isFalse();
    }

    @Test
    void restore_lineWithAnUnknownFlagAndAMatchingHash_isRefusedNotIgnored() throws Exception {
        var dir = fixtureWithFlagOnAlpha("SOMETHING_NEW");
        var store = MetadataStore.inMemoryMetadataStore("unknown-flag");

        assertThat(snapshotManager(store, snapshotConfig(dir, 100, 600_000, 5, NODE_ID)).restoreFromLatest().isFailure())
            .as("a flag this code cannot read could be one whose absence changes what the record means")
            .isTrue();
    }

    /// A file in the unversioned layout, whose hash covers block ids and ref names only, so a flag changes nothing it
    /// checks: the parser is the only thing standing between an unknown flag and a restored record.
    private Path legacyFileWith(String extraField) throws IOException {
        var dir = Files.createDirectories(tempDir.resolve("legacy-" + extraField.length()));
        var id = idOf("legacy-block");
        var record = new BlockLifecycle(id, EnumSet.of(TierLevel.MEMORY), 1, 10L, 5L, 2, 0L);
        var hash = HexFormat.of().formatHex(MetadataSnapshot.legacyContentHash(List.of(record), Map.of("legacy-ref", id)));
        var file = "epoch=1\ntimestamp=1\nnodeId=" + NODE_ID + "\ncontentHash=" + hash + "\n\n# Lifecycles\n"
                   + id.hexString() + "|MEMORY|1|10|5|2|0" + extraField + "\n\n# Refs\nlegacy-ref|" + id.hexString() + "\n";

        Files.writeString(dir.resolve("snapshot-000001.dat"), file, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("LATEST"), "snapshot-000001.dat", StandardCharsets.UTF_8);

        return dir;
    }

    @Test
    void restore_unversionedFile_stillRestores_butRefusesAFlagItsHashCannotVouchFor() throws Exception {
        var restored = restore(legacyFileWith(""));

        assertThat(restored.lifecycles()).hasSize(1);
        assertThat(restored.lifecycles().getFirst().cacheOnly()).isFalse();

        var store = MetadataStore.inMemoryMetadataStore("legacy-unknown-flag");

        assertThat(snapshotManager(store, snapshotConfig(legacyFileWith("|SOMETHING_NEW"), 100, 600_000, 5, NODE_ID))
                       .restoreFromLatest()
                       .isFailure())
            .as("the hash of this layout ignores flags, so only the parser can refuse one it does not know")
            .isTrue();
    }

    @Test
    void readBack_ofAMarkerLine_requiresTheFlagAndOfAnOrdinaryLine_forbidsIt() {
        var owned = new BlockLifecycle(idOf("owned"), EnumSet.of(TierLevel.LOCAL_DISK), 1, 10L, 5L, 2, 0L);
        var marker = new BlockLifecycle(idOf("marker"), EnumSet.of(TierLevel.REMOTE), 0, 30L, 20L, 4, 0L, true);
        var captured = MetadataSnapshot.metadataSnapshot(1, NODE_ID, List.of(owned, marker), Map.of());
        var written = DefaultSnapshotManager.serializeSnapshot(captured);

        assertThat(SnapshotReadBack.matches(captured, written)).as("control: the file the encoder wrote reads back").isTrue();
        assertThat(SnapshotReadBack.matches(captured, written.replace("|CACHE_ONLY", "")))
            .as("a marker whose flag was lost").isFalse();
        assertThat(SnapshotReadBack.matches(captured, withFlagOnOwnedLine(written, owned)))
            .as("an ordinary record that gained a flag").isFalse();
        assertThat(SnapshotReadBack.matches(captured, written.replace("|CACHE_ONLY", "|CACHE_ONLY|CACHE_ONLY")))
            .as("anything after the flag").isFalse();
    }

    private static String withFlagOnOwnedLine(String written, BlockLifecycle owned) {
        var line = written.lines().filter(l -> l.startsWith(owned.blockId().hexString())).findFirst().orElseThrow();

        return written.replace(line, line + "|CACHE_ONLY");
    }

    @Test
    void metadataSnapshot_hashCoversTheMarker() {
        var id = idOf("hashed");
        var owned = new BlockLifecycle(id, EnumSet.of(TierLevel.REMOTE), 0, 30L, 20L, 4, 0L);
        var marker = new BlockLifecycle(id, EnumSet.of(TierLevel.REMOTE), 0, 30L, 20L, 4, 0L, true);

        var ownedHash = MetadataSnapshot.metadataSnapshot(1, NODE_ID, List.of(owned), Map.of()).contentHash();
        var markerHash = MetadataSnapshot.metadataSnapshot(1, NODE_ID, List.of(marker), Map.of()).contentHash();

        assertThat(markerHash).isNotEqualTo(ownedHash);
    }
}
