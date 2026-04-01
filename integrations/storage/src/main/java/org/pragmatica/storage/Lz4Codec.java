package org.pragmatica.storage;

import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.lift;

/// LZ4 compression codec using the fastest available implementation.
final class Lz4Codec implements CompressionCodec {

    static final Lz4Codec INSTANCE = new Lz4Codec();

    private final LZ4Compressor compressor;
    private final LZ4FastDecompressor decompressor;

    private Lz4Codec() {
        var factory = LZ4Factory.fastestInstance();
        this.compressor = factory.fastCompressor();
        this.decompressor = factory.fastDecompressor();
    }

    @Override
    public Result<byte[]> compress(byte[] data) {
        return lift(CompressionError.CompressionFailed::new, () -> compressor.compress(data));
    }

    @Override
    public Result<byte[]> decompress(byte[] data, int originalSize) {
        return lift(CompressionError.DecompressionFailed::new, () -> decompressor.decompress(data, originalSize));
    }
}
