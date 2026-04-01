package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class CompressionCodecTest {

    private static final byte[] SAMPLE_DATA = "The quick brown fox jumps over the lazy dog".getBytes(StandardCharsets.UTF_8);

    @Nested
    class Lz4Tests {

        private final CompressionCodec codec = Compression.LZ4.codec();

        @Test
        void compress_decompress_roundTrip_lz4() {
            assertRoundTrip(codec, SAMPLE_DATA);
        }

        @Test
        void compress_emptyData_succeeds() {
            assertRoundTrip(codec, new byte[0]);
        }

        @Test
        void compress_largeData_reducesSize() {
            var repetitive = buildRepetitiveData(10_000);

            assertSuccess(codec.compress(repetitive), compressed ->
                assertThat(compressed.length).isLessThan(repetitive.length)
            );
        }

        @Test
        void compress_incompressibleData_stillRoundTrips() {
            var random = buildPseudoRandomData(256);

            assertRoundTrip(codec, random);
        }
    }

    @Nested
    class ZstdTests {

        private final CompressionCodec codec = Compression.ZSTD.codec();

        @Test
        void compress_decompress_roundTrip_zstd() {
            assertRoundTrip(codec, SAMPLE_DATA);
        }

        @Test
        void compress_emptyData_succeeds() {
            assertRoundTrip(codec, new byte[0]);
        }

        @Test
        void compress_largeData_reducesSize() {
            var repetitive = buildRepetitiveData(10_000);

            assertSuccess(codec.compress(repetitive), compressed ->
                assertThat(compressed.length).isLessThan(repetitive.length)
            );
        }

        @Test
        void compress_incompressibleData_stillRoundTrips() {
            var random = buildPseudoRandomData(256);

            assertRoundTrip(codec, random);
        }
    }

    @Nested
    class NoneTests {

        private final CompressionCodec codec = Compression.NONE.codec();

        @Test
        void compress_decompress_roundTrip_none() {
            assertRoundTrip(codec, SAMPLE_DATA);
        }

        @Test
        void compress_emptyData_succeeds() {
            assertRoundTrip(codec, new byte[0]);
        }

        @Test
        void compress_returnsIdenticalData() {
            assertSuccess(codec.compress(SAMPLE_DATA), compressed ->
                assertThat(compressed).isEqualTo(SAMPLE_DATA)
            );
        }
    }

    @Nested
    class DecompressionErrorTests {

        @Test
        void decompress_wrongSize_lz4_returnsFailure() {
            var codec = Compression.LZ4.codec();

            assertSuccess(codec.compress(SAMPLE_DATA), compressed ->
                codec.decompress(compressed, SAMPLE_DATA.length + 100)
                     .onSuccess(_ -> fail("Expected failure for wrong original size"))
                     .onFailure(cause -> assertThat(cause.message()).contains("Decompression failed"))
            );
        }

        @Test
        void decompress_corruptData_zstd_returnsFailure() {
            var codec = Compression.ZSTD.codec();
            var garbage = new byte[]{1, 2, 3, 4, 5};

            codec.decompress(garbage, 100)
                 .onSuccess(_ -> fail("Expected failure for corrupt data"))
                 .onFailure(cause -> assertThat(cause.message()).contains("Decompression failed"));
        }
    }

    @Nested
    class CodecAccessTests {

        @Test
        void codec_returnsSameInstance_forSameAlgorithm() {
            assertThat(Compression.LZ4.codec()).isSameAs(Compression.LZ4.codec());
            assertThat(Compression.ZSTD.codec()).isSameAs(Compression.ZSTD.codec());
            assertThat(Compression.NONE.codec()).isSameAs(Compression.NONE.codec());
        }
    }

    private static void assertRoundTrip(CompressionCodec codec, byte[] original) {
        assertSuccess(codec.compress(original), compressed ->
            assertSuccess(codec.decompress(compressed, original.length), decompressed ->
                assertThat(decompressed).isEqualTo(original)
            )
        );
    }

    private static <T> void assertSuccess(Result<T> result, Consumer<T> assertions) {
        result.onFailure(cause -> fail("Expected success but got: " + cause.message()))
              .onSuccess(assertions::accept);
    }

    private static byte[] buildRepetitiveData(int size) {
        var pattern = "ABCDEFGHIJ".getBytes(StandardCharsets.UTF_8);
        var data = new byte[size];

        for (int i = 0; i < size; i++) {
            data[i] = pattern[i % pattern.length];
        }

        return data;
    }

    private static byte[] buildPseudoRandomData(int size) {
        var data = new byte[size];
        var seed = 42L;

        for (int i = 0; i < size; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            data[i] = (byte) (seed >>> 56);
        }

        return data;
    }
}
