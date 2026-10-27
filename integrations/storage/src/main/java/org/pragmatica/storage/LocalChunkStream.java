package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.storage.ContentStoreError.General.CHUNK_MISSING;
import static org.pragmatica.storage.ContentStoreError.General.MANIFEST_PARSE_FAILED;


/// A pull-based read of a byte range of one object. The blocks of the range are fetched in order, the next
/// `readAhead` of them already in flight while the caller consumes the current one, so at most `readAhead + 1` blocks are
/// on the heap. The first and last blocks of the range are sliced; every other block is handed over whole.
final class LocalChunkStream implements ChunkStream {
    private final StorageInstance storage;
    private final ChunkFraming framing;
    private final List<BlockId> blockIds;
    private final int chunkSize;
    private final long totalSize;
    private final long first;
    private final long last;
    private final int readAhead;
    private final int firstIndex;
    private final int lastIndex;
    private final ArrayDeque<Fetch> ahead = new ArrayDeque<>();
    private int nextIndex;
    private boolean closed;

    private LocalChunkStream(StorageInstance storage,
                             ChunkFraming framing,
                             ContentManifest manifest,
                             List<BlockId> blockIds,
                             long first,
                             long last,
                             int readAhead) {
        this.storage = storage;
        this.framing = framing;
        this.blockIds = blockIds;
        this.chunkSize = manifest.chunkSize();
        this.totalSize = manifest.totalSize();
        this.first = first;
        this.last = last;
        this.readAhead = readAhead;
        this.firstIndex = (int)(first / chunkSize);
        this.lastIndex = (int)(last / chunkSize);
        this.nextIndex = firstIndex;
    }

    private record Fetch(int index, Promise<byte[]> block) {}

    /// A stream over `range` of the object `manifest` describes, or the reason the range does not fit it.
    static Result<ChunkStream> chunkStream(StorageInstance storage,
                                           ChunkFraming framing,
                                           ContentManifest manifest,
                                           ByteRange range,
                                           int readAhead) {
        return consistent(manifest).flatMap(_ -> blockIdsOf(manifest))
                         .flatMap(ids -> openRange(storage, framing, manifest, ids, range, readAhead));
    }

    /// The manifest's own numbers must agree: a chunk size, and exactly the blocks the total size needs (one empty block
    /// for an empty object).
    private static Result<ContentManifest> consistent(ContentManifest manifest) {
        var expectedBlocks = Math.max(1,
                                      (manifest.totalSize() + manifest.chunkSize() - 1) / Math.max(1,
                                                                                                   manifest.chunkSize()));

        return manifest.chunkSize() >= 1 && manifest.totalSize() >= 0 && expectedBlocks == manifest.chunkBlockIds()
                                                                                                   .size()
               ? Result.success(manifest)
               : MANIFEST_PARSE_FAILED.result();
    }

    private static Result<List<BlockId>> blockIdsOf(ContentManifest manifest) {
        var ids = new ArrayList<BlockId>(manifest.chunkBlockIds().size());

        for (var hex : manifest.chunkBlockIds()) {
            var parsed = BlockId.fromHex(hex);

            if (parsed.isFailure()) {
                return MANIFEST_PARSE_FAILED.result();
            }

            ids.add(parsed.unwrap());
        }

        return Result.success(ids);
    }

    private static Result<ChunkStream> openRange(StorageInstance storage,
                                                 ChunkFraming framing,
                                                 ContentManifest manifest,
                                                 List<BlockId> ids,
                                                 ByteRange range,
                                                 int readAhead) {
        var total = manifest.totalSize();
        var requestedLast = range.last().or(total - 1);

        if (range.first() < 0 || requestedLast < range.first() && total > 0) {
            return ContentStoreError.InvalidRange.invalidRange("first " + range.first() + ", last " + range.last()).result();
        }

        if (total == 0) {
            return range.first() == 0
                   ? Result.success(new EmptyStream())
                   : ContentStoreError.InvalidRange.invalidRange("first " + range.first() + " of an empty object").result();
        }

        return range.first() >= total
               ? ContentStoreError.InvalidRange.invalidRange("first " + range.first()
                                                            + " is past the end of " + total
                                                            + " bytes").result()
               : Result.success(new LocalChunkStream(storage,
                                                     framing,
                                                     manifest,
                                                     ids,
                                                     range.first(),
                                                     Math.min(requestedLast, total - 1),
                                                     readAhead));
    }

    @Override
    public Promise<Option<ByteBuffer>> next() {
        Fetch head;

        synchronized (this) {
            if (closed) {
                return Promise.success(none());
            }

            fill(1);
            head = ahead.poll();
            if (head != null) {
                fill(readAhead);
            }
        }

        return head == null
               ? Promise.success(none())
               : head.block()
                     .map(bytes -> some(slice(head.index(),
                                              bytes)));
    }

    private void fill(int target) {
        while (ahead.size() < target && nextIndex <= lastIndex) {
            ahead.add(new Fetch(nextIndex, fetch(nextIndex)));
            nextIndex++;
        }
    }

    private Promise<byte[]> fetch(int index) {
        return storage.get(blockIds.get(index))
                      .flatMap(block -> block.async(CHUNK_MISSING))
                      .flatMap(framed -> framing.unframe(framed)
                                                .async())
                      .flatMap(chunk -> chunk.length == expectedLength(index)
                                        ? Promise.success(chunk)
                                        : MANIFEST_PARSE_FAILED.promise());
    }

    private int expectedLength(int index) {
        return index < blockIds.size() - 1
               ? chunkSize
               : (int)(totalSize - (long)(blockIds.size() - 1) * chunkSize);
    }

    private ByteBuffer slice(int index, byte[] chunk) {
        var start = index == firstIndex
                    ? (int)(first % chunkSize)
                    : 0;
        var end = index == lastIndex
                  ? (int)(last % chunkSize) + 1
                  : chunk.length;

        return ByteBuffer.wrap(chunk, start, end - start)
                         .slice()
                         .asReadOnlyBuffer();
    }

    @Override
    public Promise<Unit> close() {
        synchronized (this) {
            closed = true;
            ahead.clear();
        }

        return Promise.success(unit());
    }

    private record EmptyStream() implements ChunkStream {
        @Override
        public Promise<Option<ByteBuffer>> next() {
            return Promise.success(none());
        }

        @Override
        public Promise<Unit> close() {
            return Promise.success(unit());
        }
    }
}
