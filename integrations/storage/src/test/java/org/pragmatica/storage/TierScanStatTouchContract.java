package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// The A2b contract of [StorageTier#stat], [StorageTier#scan] and [StorageTier#touch], run against every tier that
/// provides them (memory, local disk, S3 over an in-memory client and over MinIO). Each subclass builds a fresh, empty
/// tier and says how to make a block's write time older than a touch made now would set.
abstract class TierScanStatTouchContract {
    private static final int BLOCKS = 40;

    protected StorageTier tier;

    /// A fresh tier holding no blocks.
    protected abstract StorageTier newTier();

    /// Makes the write time [StorageTier#stat] reports for `id` strictly older than one a touch made after this returns
    /// would set: move the tier's clock on, backdate the file, or wait out the provider's one-second precision.
    protected abstract void age(BlockId id);

    @BeforeEach
    void freshTier() {
        tier = newTier();
    }

    protected static byte[] bytes(String content) {
        return content.getBytes(StandardCharsets.UTF_8);
    }

    protected BlockId store(byte[] content) {
        var id = BlockId.blockId(content).unwrap();

        tier.put(id, content).await().unwrap();

        return id;
    }

    @Test
    void stat_ofAHeldBlock_reportsItsStoredSizeAndAWriteTime() {
        var content = bytes("stat me");
        var id = store(content);

        var stat = tier.stat(id).await().unwrap();

        assertThat(stat.map(BlockStat::id)).isEqualTo(Option.some(id));
        assertThat(stat.map(BlockStat::size)).isEqualTo(Option.some((long) content.length));
        assertThat(stat.map(BlockStat::writtenAtMillis).or(0L)).isPositive();
    }

    @Test
    void stat_ofAnAbsentBlock_isNone() {
        var id = BlockId.blockId(bytes("never stored")).unwrap();

        assertThat(tier.stat(id).await().unwrap().isEmpty()).isTrue();
    }

    @Test
    void touch_ofAHeldBlock_movesItsWriteTimeLater_andKeepsItsBytes() {
        var content = bytes("touch me");
        var id = store(content);

        age(id);

        var aged = tier.stat(id).await().unwrap().unwrap();

        assertThat(tier.touch(id).await().unwrap()).as("the tier held the block").isTrue();

        var touched = tier.stat(id).await().unwrap().unwrap();

        assertThat(touched.writtenAtMillis()).as("the touch moved the write time").isGreaterThan(aged.writtenAtMillis());
        assertThat(touched.size()).isEqualTo(aged.size());
        assertThat(tier.get(id).await().unwrap().unwrap()).isEqualTo(content);
    }

    @Test
    void touch_ofAnAbsentBlock_isFalse_andCreatesNothing() {
        var id = BlockId.blockId(bytes("never stored either")).unwrap();

        assertThat(tier.touch(id).await().unwrap()).isFalse();
        assertThat(tier.exists(id).await().unwrap()).isFalse();
        assertThat(tier.stat(id).await().unwrap().isEmpty()).isTrue();
    }

    /// Every block exactly once across the shards of each count, each in the shard it belongs to, in ascending order
    /// within a shard. Small pages force the cursor through every shard boundary.
    @Test
    void scan_overEveryShard_returnsEachBlockExactlyOnce_inItsOwnShard() {
        var ids = IntStream.range(0, BLOCKS)
                           .mapToObj(i -> store(bytes("scanned block " + i)))
                           .toList();

        for (var count : List.of(1, 3, 4)) {
            var seen = new ArrayList<BlockId>();

            for (var index = 0; index < count; index++) {
                var shard = KeyShard.keyShard(index, count).unwrap();
                var found = scanAll(shard, 3);

                assertThat(found).as("shard %d of %d holds only its own blocks", index, count).allMatch(shard::contains);
                assertThat(found.stream().map(BlockId::hexString).toList()).as("ascending").isSorted();
                seen.addAll(found);
            }

            assertThat(seen).as("count %d covers every block exactly once", count).containsExactlyInAnyOrderElementsOf(ids);
        }
    }

    @Test
    void scan_resumesStrictlyAfterTheCursor_andOnlyAShortPageEndsIt() {
        var ids = IntStream.range(0, 5)
                           .mapToObj(i -> store(bytes("paged block " + i)))
                           .sorted(Comparator.comparing(BlockId::hexString))
                           .toList();
        var all = KeyShard.all();

        var first = tier.scan(all, Option.none(), 2).await().unwrap();
        var second = tier.scan(all, first.next(), 2).await().unwrap();
        var third = tier.scan(all, second.next(), 2).await().unwrap();

        assertThat(first.items().stream().map(BlockStat::id).toList()).isEqualTo(ids.subList(0, 2));
        assertThat(first.next()).isEqualTo(Option.some(Cursor.cursor(ids.get(1).hexString())));
        assertThat(second.items().stream().map(BlockStat::id).toList()).isEqualTo(ids.subList(2, 4));
        assertThat(third.items().stream().map(BlockStat::id).toList()).isEqualTo(ids.subList(4, 5));
        assertThat(third.next().isEmpty()).as("a short page is the last").isTrue();
    }

    @Test
    void scan_ofAFullLastPage_isFollowedByAnEmptyPageWithoutACursor() {
        IntStream.range(0, 2).forEach(i -> store(bytes("exact page " + i)));

        var full = tier.scan(KeyShard.all(), Option.none(), 2).await().unwrap();
        var after = tier.scan(KeyShard.all(), full.next(), 2).await().unwrap();

        assertThat(full.items()).hasSize(2);
        assertThat(full.next().isPresent()).isTrue();
        assertThat(after.items()).isEmpty();
        assertThat(after.next().isEmpty()).isTrue();
    }

    @Test
    void scan_refusesALimitBelowOne_andACursorNoScanHandedOut() {
        var badLimit = tier.scan(KeyShard.all(), Option.none(), 0).await();
        var badCursor = tier.scan(KeyShard.all(), Option.some(Cursor.cursor("../not-a-block")), 5).await();

        assertThat(badLimit.<Cause>fold(cause -> cause, _ -> null)).isInstanceOf(StorageError.InvalidScan.class);
        assertThat(badCursor.<Cause>fold(cause -> cause, _ -> null)).isInstanceOf(StorageError.InvalidScan.class);
    }

    /// Anchors live beside the blocks on the tiers that keep them, and are never listed as blocks.
    @Test
    void scan_listsBlocksOnly_notAnchors() {
        var id = store(bytes("the only block"));

        tier.putAnchor(AnchorName.anchorName("scan-anchor").unwrap(), 3, bytes("anchor value")).await().unwrap();

        assertThat(scanAll(KeyShard.all(), 10)).containsExactly(id);
    }

    /// A scan reports the time a touch set, as [StorageTier#stat] does (to the second), and lists a touched block once -- on S3, where
    /// the touch is a marker object beside the block, at every page size, so a marker can open the page after its block.
    @Test
    void scan_listsATouchedBlockOnce_withTheTimeStatReports() {
        var ids = IntStream.range(0, 3)
                           .mapToObj(i -> store(bytes("touched block " + i)))
                           .toList();

        ids.forEach(this::age);

        var aged = ids.stream().map(id -> tier.stat(id).await().unwrap().unwrap().writtenAtMillis()).toList();

        ids.forEach(id -> assertThat(tier.touch(id).await().unwrap()).isTrue());

        for (var index = 0; index < ids.size(); index++) {
            assertThat(tier.stat(ids.get(index)).await().unwrap().unwrap().writtenAtMillis()).as("stat after the touch")
                                                                                                .isGreaterThan(aged.get(index));
        }

        for (var limit : List.of(1, 2, 10)) {
            var scanned = scanAllStats(KeyShard.all(), limit);

            assertThat(scanned.stream().map(BlockStat::id).toList()).as("page size %d", limit)
                                                                   .containsExactlyInAnyOrderElementsOf(ids);
            scanned.forEach(stat -> assertThat(inWholeSeconds(stat)).as("page size %d", limit)
                                                                    .isEqualTo(inWholeSeconds(tier.stat(stat.id())
                                                                                                  .await()
                                                                                                  .unwrap()
                                                                                                  .unwrap())));
        }
    }

    /// S3 reports `Last-Modified` in whole seconds on a HEAD and in milliseconds on a LIST, so a scan and a stat agree to
    /// the second.
    private static BlockStat inWholeSeconds(BlockStat stat) {
        return BlockStat.blockStat(stat.id(), stat.size(), stat.writtenAtMillis() / 1000 * 1000);
    }

    protected List<BlockId> scanAll(KeyShard shard, int limit) {
        return scanAllStats(shard, limit).stream().map(BlockStat::id).toList();
    }

    protected List<BlockStat> scanAllStats(KeyShard shard, int limit) {
        var found = new ArrayList<BlockStat>();
        var cursor = Option.<Cursor> none();

        do {
            var page = tier.scan(shard, cursor, limit).await().unwrap();

            found.addAll(page.items());
            cursor = page.next();
        } while (cursor.isPresent());

        return found;
    }
}
