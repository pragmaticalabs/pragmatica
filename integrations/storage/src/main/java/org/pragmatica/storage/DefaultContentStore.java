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

    @Override
    public Promise<String> put(String name, byte[] content) {
        return content.length <= config.chunkSizeBytes()
               ? putDirect(name, content)
               : putChunked(name, content);
    }

    private Promise<String> putDirect(String name, byte[] content) {
        return frameAndStore(content)
            .flatMap(blockId -> createRefAndReturnHex(name, blockId));
    }

    private Promise<String> putChunked(String name, byte[] content) {
        var chunks = splitIntoChunks(content);

        return storeAllChunks(chunks, 0, new ArrayList<>())
            .flatMap(chunkIds -> storeManifestAndCreateRef(name, content.length, chunkIds));
    }

    private Promise<String> createRefAndReturnHex(String name, BlockId blockId) {
        return storage.createRef(name, blockId)
                      .map(_ -> blockId.hexString());
    }

    private Promise<String> storeManifestAndCreateRef(String name, long totalSize, List<String> chunkIds) {
        var manifest = ContentManifest.contentManifest(name, totalSize, chunkIds);

        return storage.put(manifest.toBytes())
                      .flatMap(manifestId -> createRefAndReturnHex(name, manifestId));
    }

    private Promise<BlockId> frameAndStore(byte[] content) {
        return frameCompress(content)
            .async()
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

        return frameAndStore(chunks.get(index))
            .flatMap(blockId -> accumulateAndContinue(chunks, index, accumulated, blockId));
    }

    private Promise<List<String>> accumulateAndContinue(List<byte[]> chunks, int index, List<String> accumulated, BlockId blockId) {
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
        return ContentManifest.fromBytes(rawData)
                              .fold(() -> frameDecompressAndWrap(rawData), this::reassembleFromManifest);
    }

    private Promise<Option<byte[]>> frameDecompressAndWrap(byte[] data) {
        return frameDecompress(data)
            .map(Option::some)
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
        return fetchAllChunks(manifest.chunkBlockIds(), 0, new ArrayList<>())
            .map(this::concatenateChunks)
            .map(Option::some);
    }

    private Promise<List<byte[]>> fetchAllChunks(List<String> chunkIds, int index, List<byte[]> accumulated) {
        if (index >= chunkIds.size()) {
            return Promise.success(List.copyOf(accumulated));
        }

        return fetchSingleChunk(chunkIds.get(index))
            .flatMap(data -> accumulateChunkAndContinue(chunkIds, index, accumulated, data));
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

    private Promise<List<byte[]>> accumulateChunkAndContinue(List<String> chunkIds, int index, List<byte[]> accumulated, byte[] data) {
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
                      .fold(() -> Promise.success(false), storage::exists);
    }

    // --- Delete flow ---

    @Override
    public Promise<Unit> delete(String name) {
        return storage.resolveRef(name)
                      .fold(() -> Promise.success(unit()), blockId -> deleteByBlockId(name, blockId));
    }

    private Promise<Unit> deleteByBlockId(String name, BlockId blockId) {
        return storage.get(blockId)
                      .flatMap(opt -> opt.fold(() -> deleteRefOnly(name), data -> deleteContentAndRef(name, blockId, data)));
    }

    private Promise<Unit> deleteRefOnly(String name) {
        return storage.deleteRef(name);
    }

    private Promise<Unit> deleteContentAndRef(String name, BlockId blockId, byte[] data) {
        return ContentManifest.fromBytes(data)
                              .fold(() -> deleteBlockAndRef(name, blockId),
                                    manifest -> deleteManifestChunksAndRef(name, blockId, manifest));
    }

    private Promise<Unit> deleteBlockAndRef(String name, BlockId blockId) {
        return storage.delete(blockId)
                      .flatMap(_ -> storage.deleteRef(name));
    }

    private Promise<Unit> deleteManifestChunksAndRef(String name, BlockId manifestId, ContentManifest manifest) {
        return deleteAllChunks(manifest.chunkBlockIds(), 0)
            .flatMap(_ -> storage.delete(manifestId))
            .flatMap(_ -> storage.deleteRef(name));
    }

    private Promise<Unit> deleteAllChunks(List<String> chunkIds, int index) {
        if (index >= chunkIds.size()) {
            return Promise.success(unit());
        }

        return deleteSingleChunk(chunkIds.get(index))
            .flatMap(_ -> deleteAllChunks(chunkIds, index + 1));
    }

    private Promise<Unit> deleteSingleChunk(String hexId) {
        return BlockId.fromHex(hexId)
                      .async()
                      .flatMap(storage::delete);
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
