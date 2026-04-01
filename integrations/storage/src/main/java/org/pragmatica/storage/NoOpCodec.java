package org.pragmatica.storage;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Pass-through codec that performs no compression.
final class NoOpCodec implements CompressionCodec {

    static final NoOpCodec INSTANCE = new NoOpCodec();

    @Override
    public Result<byte[]> compress(byte[] data) {
        return success(data);
    }

    @Override
    public Result<byte[]> decompress(byte[] data, int originalSize) {
        return success(data);
    }
}
