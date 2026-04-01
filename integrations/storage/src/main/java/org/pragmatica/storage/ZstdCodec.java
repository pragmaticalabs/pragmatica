package org.pragmatica.storage;

import com.github.luben.zstd.Zstd;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.lift;

/// Zstandard compression codec for high compression ratios.
final class ZstdCodec implements CompressionCodec {

    static final ZstdCodec INSTANCE = new ZstdCodec();

    private ZstdCodec() {}

    @Override
    public Result<byte[]> compress(byte[] data) {
        return lift(CompressionError.CompressionFailed::new, () -> Zstd.compress(data));
    }

    @Override
    public Result<byte[]> decompress(byte[] data, int originalSize) {
        return lift(CompressionError.DecompressionFailed::new, () -> Zstd.decompress(data, originalSize));
    }
}
