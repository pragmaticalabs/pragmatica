package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.pragmatica.lang.Result;


/// How one chunk becomes one block and back: the chunk itself when the store does not compress, otherwise the original
/// size as a 4-byte header followed by the codec's output. The manifest does not record the compression, so a store reads
/// blocks with the compression it writes them with.
record ChunkFraming(CompressionCodec codec, boolean compressed) {
    private static final int SIZE_HEADER_BYTES = 4;

    static ChunkFraming chunkFraming(Compression compression) {
        return new ChunkFraming(compression.codec(), compression != Compression.NONE);
    }

    /// The block for the first `length` bytes of `chunk`; `chunk` itself, not a copy, when it is whole and not compressed.
    Result<byte[]> frame(byte[] chunk, int length) {
        var content = length == chunk.length
                      ? chunk
                      : Arrays.copyOf(chunk, length);

        return compressed
               ? codec.compress(content).map(packed -> prependOriginalSize(length, packed))
               : Result.success(content);
    }

    Result<byte[]> unframe(byte[] block) {
        return compressed
               ? codec.decompress(Arrays.copyOfRange(block, SIZE_HEADER_BYTES, block.length),
                                  ByteBuffer.wrap(block, 0, SIZE_HEADER_BYTES).getInt())
               : Result.success(block);
    }

    private static byte[] prependOriginalSize(int originalSize, byte[] packed) {
        var framed = new byte[SIZE_HEADER_BYTES + packed.length];

        ByteBuffer.wrap(framed).putInt(originalSize);
        System.arraycopy(packed, 0, framed, SIZE_HEADER_BYTES, packed.length);

        return framed;
    }
}
