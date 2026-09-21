package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.StorageInstance.RefSwap;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.storage.ContentStoreError.General.CHUNK_MISSING;
import static org.pragmatica.storage.ContentStoreError.General.CONTENT_NOT_FOUND;


/// Default content store implementation with auto-chunking and compression.
final class DefaultContentStore implements ContentStore {
    private static final int SIZE_HEADER_BYTES = 4;

    private final StorageInstance storage;
    private final ContentStoreConfig config;
    private final CompressionCodec codec;
    private final boolean compressionEnabled;

    DefaultContentStore(StorageInstance storage, ContentStoreConfig config) {
        this.storage = storage;
        this.config = config;
        this.codec = config.compression().codec();
        this.compressionEnabled = config.compression() != Compression.NONE;
    }

    // --- Put flow ---
    /// The chunks of the manifest the swap DISPLACES are released after the write -- and it is the swap's
    /// own report of what it displaced ([StorageInstance#swapRef]) that decides whose chunks, never the
    /// pre-read of the name: two overwrites of one name that both pre-read the same manifest would both
    /// release its chunks, and a third name deduplicating to them would lose them (#981 round 2). The
    /// pre-read is kept for two things only: it fails the put BEFORE anything is written when the current
    /// block cannot be read (ruled: no silent "nothing to release"), and it is the chunk list to release
    /// when the swap displaced exactly the block it read -- the common case, and a content-addressed id
    /// names one manifest. A different displaced id means another writer got in between; its manifest is
    /// read then, after the swap.
    ///
    /// `putRef` decrements the manifest it displaces, never what that manifest points at, and a chunk
    /// holds only `put`'s credit (see [#storeManifestUnderName]) -- so nothing but this gives that credit
    /// back, and before #981 every superseded chunk stayed at refCount 1 forever. Releasing after the
    /// write is load-bearing: a failed overwrite leaves the previous document untouched, and re-storing
    /// the same content credits each chunk (dedup) before releasing it, netting to zero.
    @Override
    public Promise<String> put(String name, byte[] content) {
        return previousContent(name).flatMap(previous -> storeThenRelease(name, content, previous));
    }

    private Promise<String> storeThenRelease(String name, byte[] content, Option<PreviousContent> previous) {
        return store(name, content).flatMap(swap -> releaseThenReturn(swap, previous));
    }

    private Promise<String> releaseThenReturn(RefSwap swap, Option<PreviousContent> previous) {
        return releaseDisplaced(swap.displaced(), previous).map(_ -> swap.current()
                                                                         .hexString());
    }

    private Promise<RefSwap> store(String name, byte[] content) {
        return content.length <= config.chunkSizeBytes()
               ? putDirect(name, content)
               : putChunked(name, content);
    }

    private Promise<RefSwap> putDirect(String name, byte[] content) {
        return frameCompress(content).async()
                            .flatMap(framed -> storage.swapRef(name, framed));
    }

    private Promise<RefSwap> putChunked(String name, byte[] content) {
        var chunks = splitIntoChunks(content);

        return storeAllChunks(chunks, 0, new ArrayList<>()).flatMap(chunkIds -> storeManifestUnderName(name,
                                                                                                       content.length,
                                                                                                       chunkIds));
    }

    /// One write-and-ref call, never [StorageInstance#put] followed by [StorageInstance#createRef]:
    /// the pair credits the block twice for one name, so it could never reach refCount 0 and the
    /// garbage collector could never collect it (#812). Chunk blocks below keep using plain `put` --
    /// they carry no name, and `put`'s own credit is the only thing holding them against GC; it is
    /// given back with [StorageInstance#release] when the manifest is superseded or deleted (#981).
    private Promise<RefSwap> storeManifestUnderName(String name, long totalSize, List<String> chunkIds) {
        var manifest = ContentManifest.contentManifest(name, totalSize, chunkIds);

        return storage.swapRef(name, manifest.toBytes());
    }

    private Promise<BlockId> frameAndStore(byte[] content) {
        return frameCompress(content).async()
                            .flatMap(storage::put);
    }

    private Result<byte[]> frameCompress(byte[] content) {
        if (!compressionEnabled) {
            return Result.success(content);
        }

        return codec.compress(content)
                    .map(compressed -> prependOriginalSize(content.length, compressed));
    }

    private Promise<List<String>> storeAllChunks(List<byte[]> chunks, int index, List<String> accumulated) {
        if (index >= chunks.size()) {
            return Promise.success(List.copyOf(accumulated));
        }

        return frameAndStore(chunks.get(index)).flatMap(blockId -> accumulateAndContinue(chunks,
                                                                                         index,
                                                                                         accumulated,
                                                                                         blockId));
    }

    private Promise<List<String>> accumulateAndContinue(List<byte[]> chunks,
                                                        int index,
                                                        List<String> accumulated,
                                                        BlockId blockId) {
        accumulated.add(blockId.hexString());

        return storeAllChunks(chunks, index + 1, accumulated);
    }

    // --- Get flow ---
    @Override
    public Promise<Option<byte[]>> get(String name) {
        return storage.resolveRef(name)
                      .async(CONTENT_NOT_FOUND)
                      .flatMap(storage::get)
                      .flatMap(opt -> opt.async(CONTENT_NOT_FOUND))
                      .flatMap(this::resolveContent);
    }

    private Promise<Option<byte[]>> resolveContent(byte[] rawData) {
        return ContentManifest.fromBytes(rawData).fold(() -> frameDecompressAndWrap(rawData),
                                                       this::reassembleFromManifest);
    }

    private Promise<Option<byte[]>> frameDecompressAndWrap(byte[] data) {
        return frameDecompress(data).map(Option::some)
                              .async();
    }

    private Result<byte[]> frameDecompress(byte[] data) {
        if (!compressionEnabled) {
            return Result.success(data);
        }

        var originalSize = extractOriginalSize(data);
        var compressed = extractCompressedPayload(data);

        return codec.decompress(compressed, originalSize);
    }

    private Promise<Option<byte[]>> reassembleFromManifest(ContentManifest manifest) {
        return fetchAllChunks(manifest.chunkBlockIds(),
                              0,
                              new ArrayList<>()).map(this::concatenateChunks)
                             .map(Option::some);
    }

    private Promise<List<byte[]>> fetchAllChunks(List<String> chunkIds, int index, List<byte[]> accumulated) {
        if (index >= chunkIds.size()) {
            return Promise.success(List.copyOf(accumulated));
        }

        return fetchSingleChunk(chunkIds.get(index)).flatMap(data -> accumulateChunkAndContinue(chunkIds,
                                                                                                index,
                                                                                                accumulated,
                                                                                                data));
    }

    private Promise<byte[]> fetchSingleChunk(String hexId) {
        return BlockId.fromHex(hexId)
                      .async()
                      .flatMap(storage::get)
                      .flatMap(opt -> opt.async(CHUNK_MISSING))
                      .flatMap(this::frameDecompressAsync);
    }

    private Promise<byte[]> frameDecompressAsync(byte[] data) {
        return frameDecompress(data).async();
    }

    private Promise<List<byte[]>> accumulateChunkAndContinue(List<String> chunkIds,
                                                             int index,
                                                             List<byte[]> accumulated,
                                                             byte[] data) {
        accumulated.add(data);

        return fetchAllChunks(chunkIds, index + 1, accumulated);
    }

    private byte[] concatenateChunks(List<byte[]> chunks) {
        var totalLength = chunks.stream().mapToInt(c -> c.length).sum();
        var result = new byte[totalLength];
        var offset = 0;

        for (var chunk : chunks) {
            System.arraycopy(chunk, 0, result, offset, chunk.length);
            offset += chunk.length;
        }

        return result;
    }

    // --- Exists flow ---
    @Override
    public Promise<Boolean> exists(String name) {
        return storage.resolveRef(name)
                      .fold(() -> Promise.success(false),
                            storage::exists);
    }

    // --- Delete flow ---
    /// Releases, never deletes. Dropping the name decrements the block it pointed at, and each chunk of
    /// the manifest the drop actually removed ([StorageInstance#dropRef], exactly once per removal --
    /// so two deletes of one name release once) gives back `put`'s credit; whatever reaches zero is
    /// collected by [StorageGarbageCollector] through the lifecycle record it already reads -- there is
    /// no second delete path. A block another name still holds (two names deduplicating to one block)
    /// is decremented, not destroyed, and stays readable through that name. Before #981 this called
    /// [StorageInstance#delete], which removes the block from every tier regardless of who else holds it.
    @Override
    public Promise<Unit> delete(String name) {
        return previousContent(name).flatMap(previous -> dropThenRelease(name, previous));
    }

    private Promise<Unit> dropThenRelease(String name, Option<PreviousContent> previous) {
        return storage.dropRef(name)
                      .flatMap(displaced -> releaseDisplaced(displaced, previous));
    }

    /// The block `name` currently points at and, if it is a manifest, its chunk ids -- empty for an
    /// absent name; empty chunks for a block no tier holds any more or for direct (unchunked) content.
    /// A block that cannot be READ fails the operation here, before anything is written or dropped.
    private Promise<Option<PreviousContent>> previousContent(String name) {
        return storage.resolveRef(name)
                      .fold(() -> Promise.success(none()),
                            this::previousContentOf);
    }

    private Promise<Option<PreviousContent>> previousContentOf(BlockId blockId) {
        return chunkIdsOf(blockId).map(chunkIds -> some(PreviousContent.previousContent(blockId, chunkIds)));
    }

    private Promise<List<String>> chunkIdsOf(BlockId blockId) {
        return storage.get(blockId)
                      .map(DefaultContentStore::chunkIdsIn);
    }

    private static List<String> chunkIdsIn(Option<byte[]> block) {
        return block.flatMap(ContentManifest::fromBytes)
                    .map(ContentManifest::chunkBlockIds)
                    .or(List.of());
    }

    /// Nothing displaced: nothing to release. The pre-read block: its chunk list, already in hand. Any
    /// other block (a concurrent writer's): read its manifest now -- it is at refCount 0 from this
    /// instant, so this read races the collector's grace period, and a grace shorter than one put's
    /// tail leaks that one manifest's chunks (bounded; the same bound as a crash between the swap and
    /// these releases).
    private Promise<Unit> releaseDisplaced(Option<BlockId> displaced, Option<PreviousContent> previous) {
        return displaced.fold(() -> Promise.success(unit()), id -> releaseChunksOf(id, previous));
    }

    private Promise<Unit> releaseChunksOf(BlockId displaced, Option<PreviousContent> previous) {
        return previous.filter(content -> content.id()
                                                 .equals(displaced))
                       .fold(() -> releaseChunksOfUnread(displaced),
                             content -> releaseAllChunks(content.chunkIds(),
                                                         0));
    }

    private Promise<Unit> releaseChunksOfUnread(BlockId displaced) {
        return chunkIdsOf(displaced).flatMap(ids -> releaseAllChunks(ids, 0));
    }

    private Promise<Unit> releaseAllChunks(List<String> chunkIds, int index) {
        if (index >= chunkIds.size()) {
            return Promise.success(unit());
        }

        return releaseSingleChunk(chunkIds.get(index)).flatMap(_ -> releaseAllChunks(chunkIds, index + 1));
    }

    private Promise<Unit> releaseSingleChunk(String hexId) {
        return BlockId.fromHex(hexId)
                      .async()
                      .flatMap(storage::release);
    }

    private record PreviousContent(BlockId id, List<String> chunkIds) {
        static PreviousContent previousContent(BlockId id, List<String> chunkIds) {
            return new PreviousContent(id, chunkIds);
        }
    }

    // --- Chunking helpers ---
    private List<byte[]> splitIntoChunks(byte[] content) {
        var chunkSize = config.chunkSizeBytes();
        var chunkCount = (content.length + chunkSize - 1) / chunkSize;
        var chunks = new ArrayList<byte[]>(chunkCount);

        for (var i = 0; i < chunkCount; i++) {
            var start = i * chunkSize;
            var end = Math.min(start + chunkSize, content.length);

            chunks.add(Arrays.copyOfRange(content, start, end));
        }

        return chunks;
    }

    // --- Compression framing helpers ---
    private static byte[] prependOriginalSize(int originalSize, byte[] compressed) {
        var framed = new byte[SIZE_HEADER_BYTES + compressed.length];

        ByteBuffer.wrap(framed).putInt(originalSize);
        System.arraycopy(compressed, 0, framed, SIZE_HEADER_BYTES, compressed.length);

        return framed;
    }

    private static int extractOriginalSize(byte[] framed) {
        return ByteBuffer.wrap(framed, 0, SIZE_HEADER_BYTES).getInt();
    }

    private static byte[] extractCompressedPayload(byte[] framed) {
        return Arrays.copyOfRange(framed, SIZE_HEADER_BYTES, framed.length);
    }
}
