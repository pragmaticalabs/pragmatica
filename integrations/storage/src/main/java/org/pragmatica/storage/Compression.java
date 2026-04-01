package org.pragmatica.storage;

/// Compression algorithm for storage blocks.
public enum Compression {
    NONE,
    LZ4,
    ZSTD;

    /// Returns the codec implementation for this compression algorithm.
    public CompressionCodec codec() {
        return switch (this) {
            case NONE -> CompressionCodec.NONE;
            case LZ4 -> Lz4Codec.INSTANCE;
            case ZSTD -> ZstdCodec.INSTANCE;
        };
    }
}
