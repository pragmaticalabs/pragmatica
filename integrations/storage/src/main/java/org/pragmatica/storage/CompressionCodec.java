package org.pragmatica.storage;

import org.pragmatica.lang.Result;

/// Codec for compressing and decompressing block data.
public interface CompressionCodec {

    Result<byte[]> compress(byte[] data);

    Result<byte[]> decompress(byte[] data, int originalSize);

    /// No-op codec for uncompressed blocks.
    CompressionCodec NONE = new NoOpCodec();
}
