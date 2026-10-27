package org.pragmatica.storage;

import org.pragmatica.lang.Result;


/// Configuration for content store chunking and compression.
///
/// A chunk is stored as one block, and an instance refuses a block over its maximum block size
/// ([StorageInstance#maxBlockBytes]). A block is its chunk plus framing: direct content carries the store's envelope
/// ([ContentEnvelope]); a compressed block adds the original-size header and whatever the codec adds to data it cannot
/// shrink. [#validatedAgainst] refuses, when the store is provisioned, a chunk size whose blocks would not fit.
///
/// The streaming store ([ContentStore]) adds three settings, each with a default so an existing `[storage]` section binds
/// unchanged: `namespace` is the scope the store is bound to (validated by [#validatedAgainst]), `writeWindow` the number of
/// blocks a writer may have in flight beyond the one it is filling, `readAhead` the number of blocks a read stream fetches
/// ahead of the one it is serving.
public record ContentStoreConfig(int chunkSizeBytes,
                                 Compression compression,
                                 String namespace,
                                 int writeWindow,
                                 int readAhead) {
    public static final String DEFAULT_NAMESPACE = "default";
    public static final int DEFAULT_WRITE_WINDOW = 4;
    public static final int DEFAULT_READ_AHEAD = 2;
    private static final int DEFAULT_CHUNK_SIZE = 4 * 1024 * 1024;  // 4 MB
    /// The envelope's marker and kind byte, on direct content.
    private static final long ENVELOPE_BYTES = ContentEnvelope.marker().length + 1;
    /// The original-size header a compressed block carries.
    private static final long COMPRESSION_HEADER_BYTES = 4;
    /// Bound on what LZ4 or Zstandard add to a chunk they cannot shrink: `n / 255 + 64`, above both codecs' worst cases
    /// (LZ4: `n + n / 255 + 16`, Zstandard: `n + n / 256 + 64`).
    private static final long CODEC_EXPANSION_FIXED_BYTES = 64;
    private static final long CODEC_EXPANSION_DIVISOR = 255;

    public static ContentStoreConfig contentStoreConfig() {
        return contentStoreConfig(DEFAULT_CHUNK_SIZE, Compression.NONE);
    }

    public static ContentStoreConfig contentStoreConfig(int chunkSizeBytes, Compression compression) {
        return new ContentStoreConfig(chunkSizeBytes,
                                      compression,
                                      DEFAULT_NAMESPACE,
                                      DEFAULT_WRITE_WINDOW,
                                      DEFAULT_READ_AHEAD);
    }

    public static ContentStoreConfig contentStoreConfig(int chunkSizeBytes,
                                                        Compression compression,
                                                        String namespace,
                                                        int writeWindow,
                                                        int readAhead) {
        return new ContentStoreConfig(chunkSizeBytes, compression, namespace, writeWindow, readAhead);
    }

    /// The largest block this configuration can hand to the instance: a full chunk, framed.
    public long largestBlockBytes() {
        return largestBlockBytes(chunkSizeBytes, compression);
    }

    /// This configuration if its chunk size is positive and its blocks fit an instance whose maximum block size is
    /// `maxBlockBytes`; otherwise a [ContentStoreError.ChunkSizeNotPositive] naming the chunk size and the limit, or a
    /// [ContentStoreError.ChunkSizeExceedsBlockLimit] naming the chunk size, the block it needs and the limit.
    public Result<ContentStoreConfig> validatedAgainst(int maxBlockBytes) {
        return chunkSizeBytes < 1
               ? ContentStoreError.ChunkSizeNotPositive.chunkSizeNotPositive(chunkSizeBytes, maxBlockBytes).result()
               : validatedAgainstBlockLimit(maxBlockBytes).flatMap(ContentStoreConfig::validatedStreamingSettings);
    }

    /// The namespace must be a valid [Namespace]; the window must allow one block in flight and the read-ahead may be zero.
    private Result<ContentStoreConfig> validatedStreamingSettings() {
        return Namespace.namespace(namespace).flatMap(_ -> writeWindow < 1
                                                           ? ContentStoreError.InvalidConfig.invalidConfig("writeWindow",
                                                                                                           "must be at least 1").result()
                                                           : readAhead < 0
                                                             ? ContentStoreError.InvalidConfig.invalidConfig("readAhead",
                                                                                                             "must not be negative").result()
                                                             : Result.success(this));
    }

    private Result<ContentStoreConfig> validatedAgainstBlockLimit(int maxBlockBytes) {
        return largestBlockBytes() <= maxBlockBytes
               ? Result.success(this)
               : ContentStoreError.ChunkSizeExceedsBlockLimit.chunkSizeExceedsBlockLimit(chunkSizeBytes,
                                                                                         largestBlockBytes(),
                                                                                         maxBlockBytes,
                                                                                         largestChunkSize(maxBlockBytes,
                                                                                                          compression)).result();
    }

    private static long largestBlockBytes(long chunkSize, Compression compression) {
        return compression == Compression.NONE
               ? chunkSize + ENVELOPE_BYTES
               : ENVELOPE_BYTES + COMPRESSION_HEADER_BYTES + chunkSize + chunkSize / CODEC_EXPANSION_DIVISOR + CODEC_EXPANSION_FIXED_BYTES;
    }

    /// The largest chunk size whose framed block still fits `maxBlockBytes` (at most 0 when none does).
    private static long largestChunkSize(long maxBlockBytes, Compression compression) {
        var chunk = Math.max(0, maxBlockBytes - ENVELOPE_BYTES - COMPRESSION_HEADER_BYTES - CODEC_EXPANSION_FIXED_BYTES);

        while (largestBlockBytes(chunk + 1, compression) <= maxBlockBytes) {
            chunk++;
        }

        while (chunk > 0 && largestBlockBytes(chunk, compression) > maxBlockBytes) {
            chunk--;
        }

        return chunk;
    }
}
