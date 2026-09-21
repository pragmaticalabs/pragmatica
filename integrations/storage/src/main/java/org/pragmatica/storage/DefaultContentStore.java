package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

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
    /// The chunks of the manifest `name` previously pointed at are read BEFORE the write and released
    /// AFTER it. `putRef` decrements the manifest it displaces, never what that manifest points at, and
    /// a chunk holds only `put`'s credit (see [#storeManifestUnderName]) -- so nothing but this gives
    /// that credit back, and before #981 every superseded chunk stayed at refCount 1 forever. Releasing
    /// after the write is load-bearing: a failed overwrite leaves the previous document untouched, and
    /// re-storing the same content credits each chunk (dedup) before releasing it, netting to zero.
    @Override
    public Promise<String> put(String name, byte[] content) {
        return previousChunkIds(name).flatMap(previous -> storeThenRelease(name, content, previous));
    }

    private Promise<String> storeThenRelease(String name, byte[] content, List<String> previous) {
        return store(name, content).flatMap(id -> releaseThenReturn(previous, id));
    }

    private Promise<String> releaseThenReturn(List<String> previous, String id) {
        return releaseAllChunks(previous, 0).map(_ -> id);
    }

    private Promise<String> store(String name, byte[] content) {
        return content.length <= config.chunkSizeBytes()
               ? putDirect(name, content)
               : putChunked(name, content);
    }

    private Promise<String> putDirect(String name, byte[] content) {
        return frameCompress(content).async()
                            .flatMap(framed -> storage.putRef(name, framed))
                            .map(BlockId::hexString);
    }

    private Promise<String> putChunked(String name, byte[] content) {
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
    private Promise<String> storeManifestUnderName(String name, long totalSize, List<String> chunkIds) {
        var manifest = ContentManifest.contentManifest(name, totalSize, chunkIds);

        return storage.putRef(name,
                              manifest.toBytes())
                      .map(BlockId::hexString);
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
    /// a manifest gives back `put`'s credit; whatever reaches zero is collected by
    /// [StorageGarbageCollector] through the lifecycle record it already reads -- there is no second
    /// delete path. A block another name still holds (two names deduplicating to one block) is
    /// decremented, not destroyed, and stays readable through that name. Before #981 this called
    /// [StorageInstance#delete], which removes the block from every tier regardless of who else holds it.
    @Override
    public Promise<Unit> delete(String name) {
        return previousChunkIds(name).flatMap(chunkIds -> dropNameThenRelease(name, chunkIds));
    }

    private Promise<Unit> dropNameThenRelease(String name, List<String> chunkIds) {
        return storage.deleteRef(name)
                      .flatMap(_ -> releaseAllChunks(chunkIds, 0));
    }

    /// The chunk ids of the manifest `name` currently points at -- empty for an absent name, a block
    /// no tier holds any more, or direct (unchunked) content.
    private Promise<List<String>> previousChunkIds(String name) {
        return storage.resolveRef(name)
                      .fold(() -> Promise.success(List.of()),
                            this::chunkIdsOf);
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
