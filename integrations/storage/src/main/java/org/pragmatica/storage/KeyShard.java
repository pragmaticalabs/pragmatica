package org.pragmatica.storage;

import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// One of `count` disjoint shards of the block-id space, decided by the first byte of the block's hash (A2b): first byte
/// `b` belongs to shard `b * count / 256`. A shard covers a contiguous run of first bytes, so a tier that lays blocks out
/// by the first two hex characters ([LocalDiskTier] directories, [RemoteTier] key prefixes) visits only that run. The
/// shards of one count cover every block exactly once. Which node sweeps which shard is the caller's decision (spec v2
/// §A1.5, A4), not this type's.
///
/// Also the paging rule every tier's [StorageTier#scan] follows, so a cursor means the same on every tier: blocks come in
/// ascending hex order of their id, a page holds those strictly after the cursor, and the cursor of the next page is the
/// hex id of the last block returned. A page that comes back full carries a next cursor; one that comes back short is the
/// last. The page after a full last page is therefore empty, with no cursor.
public record KeyShard(int index, int count) {
    public static final int MAX_COUNT = 256;
    private static final Pattern BLOCK_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final HexFormat HEX = HexFormat.of();

    /// Shard `index` of `count`: `count` in 1..256 and `index` in 0..count-1, otherwise [StorageError.InvalidScan].
    public static Result<KeyShard> keyShard(int index, int count) {
        return Verify.ensure(count,
                             Verify.Is::between,
                             1,
                             MAX_COUNT,
                             StorageError.InvalidScan.invalidScan("shard count " + count + " is outside 1.." + MAX_COUNT))
                     .flatMap(_ -> Verify.ensure(index,
                                                 Verify.Is::between,
                                                 0,
                                                 count - 1,
                                                 StorageError.InvalidScan.invalidScan("shard " + index
                                                                                     + " is outside 0.." + (count - 1))))
                     .map(_ -> new KeyShard(index, count));
    }

    /// The single shard holding every block.
    public static KeyShard all() {
        return new KeyShard(0, 1);
    }

    public boolean contains(BlockId id) {
        return shardOf(id.hash() [0] & 0xFF) == index;
    }

    /// The two-hex-character first-byte prefixes of this shard, in ascending order.
    public List<String> prefixes() {
        return IntStream.range(0, MAX_COUNT)
                        .filter(firstByte -> shardOf(firstByte) == index)
                        .mapToObj(firstByte -> HEX.toHexDigits((byte) firstByte))
                        .toList();
    }

    private int shardOf(int firstByte) {
        return firstByte * count / MAX_COUNT;
    }

    /// The hex id a scan resumes after, or none to start at the beginning. A cursor that is not a block's hex id was not
    /// handed out by a scan and fails with [StorageError.InvalidScan].
    static Result<Option<String>> resumeAfter(Option<Cursor> cursor) {
        return cursor.fold(() -> Result.success(none()), KeyShard::cursorHex);
    }

    private static Result<Option<String>> cursorHex(Cursor cursor) {
        return Verify.ensure(cursor.value(),
                             Verify.Is::matches,
                             BLOCK_HEX,
                             StorageError.InvalidScan.invalidScan("cursor '" + cursor.value() + "' is not a block id"))
                     .map(Option::some);
    }

    /// A page limit below 1 fails with [StorageError.InvalidScan].
    static Result<Integer> pageLimit(int limit) {
        return Verify.ensure(limit,
                             Verify.Is::positive,
                             StorageError.InvalidScan.invalidScan("page limit " + limit + " is below 1"));
    }

    /// The page of `limit` taken from `ascending`, which holds this shard's blocks after the cursor in ascending hex order
    /// (more than `limit` of them is fine: only the first `limit` are returned).
    static Page<BlockStat> pageOf(List<BlockStat> ascending, int limit) {
        var items = ascending.stream().limit(limit).toList();

        return Page.page(items, nextCursor(items, limit));
    }

    private static Option<Cursor> nextCursor(List<BlockStat> items, int limit) {
        return items.size() == limit
               ? some(Cursor.cursor(items.getLast().id().hexString()))
               : none();
    }
}
