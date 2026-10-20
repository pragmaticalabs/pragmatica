package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.StorageInstance.RefSwap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.storage.ContentStoreError.General.CHUNK_MISSING;
import static org.pragmatica.storage.ContentStoreError.General.CONTENT_NOT_FOUND;
import static org.pragmatica.storage.ContentStoreError.General.MANIFEST_PARSE_FAILED;


/// Default content store implementation with auto-chunking and compression.
final class DefaultContentStore implements ContentStore {
    private static final Logger log = LoggerFactory.getLogger(DefaultContentStore.class);
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
    ///
    /// F7: values are written under the TRUSTED ref of `name` ([ContentEnvelope#trustedRef]); a LEGACY ref of the
    /// same name (written before the trusted namespace existed) is retired after the write, its chunks released by
    /// the legacy rules.
    @Override
    public Promise<String> put(String name, byte[] content) {
        return previousContent(Namespace.TRUSTED, name).flatMap(previous -> storeThenRelease(name, content, previous))
                              .flatMap(id -> retireLegacy(name).map(_ -> id));
    }

    private Promise<Unit> retireLegacy(String name) {
        return previousContent(Namespace.LEGACY, name).flatMap(previous -> dropThenRelease(Namespace.LEGACY,
                                                                                           name,
                                                                                           previous));
    }

    private Promise<String> storeThenRelease(String name, byte[] content, Option<PreviousContent> previous) {
        return store(name, content).flatMap(swap -> releaseThenReturn(swap, previous));
    }

    private Promise<String> releaseThenReturn(RefSwap swap, Option<PreviousContent> previous) {
        return releaseDisplaced(Namespace.TRUSTED, swap.displaced(), previous).map(_ -> swap.current()
                                                                                            .hexString());
    }

    private Promise<RefSwap> store(String name, byte[] content) {
        return content.length <= config.chunkSizeBytes()
               ? putDirect(name, content)
               : putChunked(name, content);
    }

    /// Direct content is stored under envelope kind `D` ([ContentEnvelope]), so no upload is ever read back as a
    /// manifest, whatever its bytes begin with.
    private Promise<RefSwap> putDirect(String name, byte[] content) {
        return frameCompress(content).async()
                            .flatMap(framed -> storage.swapRef(ContentEnvelope.trustedRef(name),
                                                               ContentEnvelope.wrap(ContentEnvelope.DIRECT, framed)));
    }

    /// Each chunk holds only `put`'s credit and no name, so a put that fails part-way would leave
    /// the chunks it already stored at refCount 1 behind no manifest -- uncollectable for ever, and with
    /// deduplication an over-count that keeps a chunk shared with the previous document alive after that
    /// document is deleted (#1437). On ANY failure, chunk `k` or the manifest swap, the put therefore
    /// gives back exactly the credits THIS put took, never the previous document's (it is released only
    /// after a successful swap, see [#put]).
    private Promise<RefSwap> putChunked(String name, byte[] content) {
        var chunks = splitIntoChunks(content);
        var stored = new ArrayList<String>();
        var attempt = storeAllChunks(chunks, 0, stored).flatMap(chunkIds -> storeManifestUnderName(name,
                                                                                                   content.length,
                                                                                                   chunkIds));

        return releaseStoredOnFailure(attempt, stored);
    }

    /// The release is awaited before the failure is reported, so a caller that sees the failure also sees
    /// the credits returned; a release that itself fails is logged and does not mask the original cause.
    private Promise<RefSwap> releaseStoredOnFailure(Promise<RefSwap> attempt, List<String> stored) {
        var settled = Promise.<RefSwap> promise();

        attempt.onResult(result -> result.onSuccess(settled::succeed)
                                         .onFailure(cause -> rollBack(stored, cause, settled)));

        return settled;
    }

    private void rollBack(List<String> stored, Cause cause, Promise<RefSwap> settled) {
        releaseAllChunks(List.copyOf(stored),
                         0).onFailure(releaseCause -> log.warn("Rollback of {} chunk(s) of a failed put could not complete: {}",
                                                               stored.size(),
                                                               releaseCause.message()))
                        .onResult(_ -> settled.fail(cause));
    }

    /// One write-and-ref call, never [StorageInstance#put] followed by [StorageInstance#createRef]:
    /// the pair credits the block twice for one name, so it could never reach refCount 0 and the
    /// garbage collector could never collect it (#812). Chunk blocks below keep using plain `put` --
    /// they carry no name, and `put`'s own credit is the only thing holding them against GC; it is
    /// given back with [StorageInstance#release] when the manifest is superseded or deleted (#981).
    private Promise<RefSwap> storeManifestUnderName(String name, long totalSize, List<String> chunkIds) {
        var manifest = ContentManifest.contentManifest(name, totalSize, chunkIds);

        return storage.swapRef(ContentEnvelope.trustedRef(name), manifest.toBytes());
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
        return storage.resolveRef(ContentEnvelope.trustedRef(name))
                      .fold(() -> getLegacy(name),
                            this::readTrusted);
    }

    /// The trusted namespace is written only by this store, so its envelope kind byte decides direct content from
    /// a manifest.
    private Promise<Option<byte[]>> readTrusted(BlockId blockId) {
        return readBlock(blockId).flatMap(this::resolveEnveloped);
    }

    /// A name never written since the trusted namespace existed is read the legacy way (see [ContentManifest]): the
    /// legacy text manifest is recognised, everything else is returned VERBATIM. A legacy value is never read as an
    /// envelope, whatever its bytes begin with.
    private Promise<Option<byte[]>> getLegacy(String name) {
        return Namespace.LEGACY.refName(name)
                      .flatMap(storage::resolveRef)
                      .async(CONTENT_NOT_FOUND)
                      .flatMap(this::readBlock)
                      .flatMap(this::resolveLegacy);
    }

    private Promise<byte[]> readBlock(BlockId blockId) {
        return storage.get(blockId)
                      .flatMap(opt -> opt.async(CONTENT_NOT_FOUND));
    }

    private Promise<Option<byte[]>> resolveEnveloped(byte[] rawData) {
        return ContentEnvelope.payloadOf(ContentEnvelope.DIRECT, rawData)
                              .map(this::frameDecompressAndWrap)
                              .or(() -> reassembleEnvelopedManifest(rawData));
    }

    private Promise<Option<byte[]>> reassembleEnvelopedManifest(byte[] rawData) {
        return ContentManifest.fromBytes(rawData)
                              .async(MANIFEST_PARSE_FAILED)
                              .flatMap(this::reassembleFromManifest);
    }

    private Promise<Option<byte[]>> resolveLegacy(byte[] rawData) {
        return ContentManifest.fromLegacyBytes(rawData).fold(() -> frameDecompressAndWrap(rawData),
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
        return storage.resolveRef(ContentEnvelope.trustedRef(name))
                      .orElse(() -> Namespace.LEGACY.refName(name).flatMap(storage::resolveRef))
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
    ///
    /// Both the trusted and any legacy ref of the name are dropped, each releasing its chunks by its own rules.
    @Override
    public Promise<Unit> delete(String name) {
        return previousContent(Namespace.TRUSTED, name).flatMap(previous -> dropThenRelease(Namespace.TRUSTED,
                                                                                            name,
                                                                                            previous))
                              .flatMap(_ -> retireLegacy(name));
    }

    private Promise<Unit> dropThenRelease(Namespace namespace, String name, Option<PreviousContent> previous) {
        return namespace.refName(name)
                        .fold(() -> Promise.success(unit()),
                              ref -> dropRefThenRelease(namespace, ref, previous));
    }

    private Promise<Unit> dropRefThenRelease(Namespace namespace, String ref, Option<PreviousContent> previous) {
        return storage.dropRef(ref)
                      .flatMap(displaced -> releaseDisplaced(namespace, displaced, previous));
    }

    /// Where a name's value lives and how its manifest is recognised. TRUSTED: the `|`-prefixed ref only this store
    /// writes, manifest by envelope kind. LEGACY: the bare name, written before the trusted namespace existed,
    /// manifest by the legacy text format only (an envelope-shaped legacy value is content, never a manifest).
    ///
    /// A name that itself begins with `|` has NO legacy ref: its bare name is another name's trusted ref (`|victim` is
    /// the trusted ref of `victim`), so reading, overwriting or dropping it as legacy would reach that name's internal
    /// value. Every legacy access goes through [#refName], so no user-supplied name reaches the trusted namespace on any
    /// operation.
    private enum Namespace {
        TRUSTED,
        LEGACY;
        Option<String> refName(String name) {
            return this == TRUSTED
                   ? some(ContentEnvelope.trustedRef(name))
                   : legacyRef(name);
        }
        private static Option<String> legacyRef(String name) {
            return name.startsWith(ContentEnvelope.TRUSTED_REF_PREFIX)
                   ? none()
                   : some(name);
        }
        Option<ContentManifest> manifestOf(byte[] value) {
            return this == TRUSTED
                   ? ContentManifest.fromBytes(value)
                   : ContentManifest.fromLegacyBytes(value);
        }
    }

    /// The block `name` currently points at and, if it is a manifest, its chunk ids -- empty for an
    /// absent name; empty chunks for a block no tier holds any more or for direct (unchunked) content.
    /// A block that cannot be READ fails the operation here, before anything is written or dropped.
    private Promise<Option<PreviousContent>> previousContent(Namespace namespace, String name) {
        return namespace.refName(name)
                        .flatMap(storage::resolveRef)
                        .fold(() -> Promise.success(none()),
                            blockId -> previousContentOf(namespace, blockId));
    }

    private Promise<Option<PreviousContent>> previousContentOf(Namespace namespace, BlockId blockId) {
        return chunkIdsOf(namespace, blockId).map(chunkIds -> some(PreviousContent.previousContent(blockId, chunkIds)));
    }

    private Promise<List<String>> chunkIdsOf(Namespace namespace, BlockId blockId) {
        return storage.get(blockId)
                      .map(block -> chunkIdsIn(namespace, block));
    }

    private static List<String> chunkIdsIn(Namespace namespace, Option<byte[]> block) {
        return block.flatMap(namespace::manifestOf)
                    .map(ContentManifest::chunkBlockIds)
                    .or(List.of());
    }

    /// Nothing displaced: nothing to release. The pre-read block: its chunk list, already in hand. Any
    /// other block (a concurrent writer's): read its manifest now -- it is at refCount 0 from this
    /// instant, so this read races the collector's grace period, and a grace shorter than one put's
    /// tail leaks that one manifest's chunks (bounded; the same bound as a crash between the swap and
    /// these releases).
    private Promise<Unit> releaseDisplaced(Namespace namespace,
                                           Option<BlockId> displaced,
                                           Option<PreviousContent> previous) {
        return displaced.fold(() -> Promise.success(unit()), id -> releaseChunksOf(namespace, id, previous));
    }

    private Promise<Unit> releaseChunksOf(Namespace namespace, BlockId displaced, Option<PreviousContent> previous) {
        return previous.filter(content -> content.id()
                                                 .equals(displaced))
                       .fold(() -> releaseChunksOfUnread(namespace, displaced),
                             content -> releaseAllChunks(content.chunkIds(),
                                                         0));
    }

    private Promise<Unit> releaseChunksOfUnread(Namespace namespace, BlockId displaced) {
        return chunkIdsOf(namespace, displaced).flatMap(ids -> releaseAllChunks(ids, 0));
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
