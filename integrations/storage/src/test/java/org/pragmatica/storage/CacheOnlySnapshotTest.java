package org.pragmatica.storage;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
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

    @Test
    void restore_lineWithAnUnknownFlag_isRefusedNotIgnored() throws Exception {
        var dir = copyFixture();
        var file = dir.resolve("snapshot-000005.dat");
        var original = Files.readString(file, StandardCharsets.UTF_8);
        var alphaLine = original.lines().filter(l -> l.startsWith(idOf("fixture-alpha").hexString())).findFirst().orElseThrow();

        Files.writeString(file, original.replace(alphaLine, alphaLine + "|SOMETHING_NEW"), StandardCharsets.UTF_8);

        var store = MetadataStore.inMemoryMetadataStore("unknown-flag");
        var result = snapshotManager(store, snapshotConfig(dir, 100, 600_000, 5, NODE_ID)).restoreFromLatest();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause.message()).contains("SOMETHING_NEW"));
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
