package org.pragmatica.storage;

import java.util.List;
import java.util.Random;

import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// A0 (spec A2.1): the maximum block size is per instance, and the content store refuses, when it is provisioned, a
/// chunk size whose blocks the instance would refuse. The envelope adds 7 bytes to direct content; a compressed block
/// adds the size header and what the codec adds to data it cannot shrink.
class ContentStoreConfigBlockLimitTest {
    private static final int ENVELOPE = 7;
    private static final int CAP = 64 * 1024;

    private static StorageInstance instanceWithCap(int cap) {
        return StorageInstance.storageInstance("cap",
                                               List.of(MemoryTier.memoryTier(64L * 1024 * 1024)),
                                               MetadataStore.inMemoryMetadataStore("cap"),
                                               WritePolicy.WRITE_THROUGH,
                                               Option.none(),
                                               AppendLog.TornTailSink.logOnly(),
                                               _ -> {},
                                               cap);
    }

    private static byte[] incompressible(int size, long seed) {
        var data = new byte[size];

        new Random(seed).nextBytes(data);

        return data;
    }

    @Test
    void sixteenMiBChunk_againstTheDefaultCap_isRefusedNamingBothValues() {
        var config = ContentStoreConfig.contentStoreConfig(16 * 1024 * 1024, Compression.NONE);

        config.validatedAgainst(StorageInstance.DEFAULT_MAX_BLOCK_BYTES)
              .onSuccess(_ -> fail("a 16 MiB chunk makes 16 MiB + 7 byte blocks, over a 16 MiB cap"))
              .onFailure(cause -> {
                  assertThat(cause).isInstanceOfSatisfying(ContentStoreError.ChunkSizeExceedsBlockLimit.class, refusal -> {
                      assertThat(refusal.chunkSizeBytes()).isEqualTo(16_777_216);
                      assertThat(refusal.requiredBlockBytes()).isEqualTo(16_777_216L + ENVELOPE);
                      assertThat(refusal.maxBlockBytes()).isEqualTo(16_777_216);
                      assertThat(refusal.largestChunkSize()).isEqualTo(16_777_216L - ENVELOPE);
                  });
                  assertThat(cause.message()).contains("16777216").contains("16777223").contains("16777209");
              });
    }

    @Test
    void refusal_namesTheChunkSizeAndTheLimitSeparately() {
        ContentStoreConfig.contentStoreConfig(5_000_000, Compression.NONE)
                          .validatedAgainst(1_000_000)
                          .onSuccess(_ -> fail("5 MB chunks do not fit a 1 MB cap"))
                          .onFailure(cause -> assertThat(cause.message()).contains("chunkSizeBytes 5000000")
                                                                         .contains("5000007")
                                                                         .contains("maximum block size of 1000000")
                                                                         .contains("at most 999993"));
    }

    /// A zero chunk would make the writer spin on a buffer it can never fill.
    @Test
    void aChunkSizeOfZero_isRefused_andOneIsAccepted() {
        assertThat(ContentStoreConfig.contentStoreConfig(0, Compression.NONE).validatedAgainst(CAP).isFailure()).isTrue();
        assertThat(ContentStoreConfig.contentStoreConfig(1, Compression.NONE).validatedAgainst(CAP).isSuccess()).isTrue();
    }

    @Test
    void largestBlock_isTheFramedChunk() {
        assertThat(ContentStoreConfig.contentStoreConfig(100_000, Compression.NONE).largestBlockBytes()).as("envelope").isEqualTo(100_007L);
        for (var compression : List.of(Compression.LZ4, Compression.ZSTD)) {
            assertThat(ContentStoreConfig.contentStoreConfig(100_000, compression).largestBlockBytes())
                    .as(compression + ": envelope 7 + size header 4 + chunk + chunk / 255 + 64").isEqualTo(100_467L);
        }
    }

    @Test
    void chunkOfTheCapMinusTheEnvelope_isAccepted_asTheControl() {
        var config = ContentStoreConfig.contentStoreConfig(16 * 1024 * 1024 - ENVELOPE, Compression.NONE);

        assertThat(config.validatedAgainst(StorageInstance.DEFAULT_MAX_BLOCK_BYTES).isSuccess()).isTrue();
    }

    @Test
    void oneByteOverTheBoundary_isRefused() {
        var config = ContentStoreConfig.contentStoreConfig(CAP - ENVELOPE + 1, Compression.NONE);

        assertThat(config.validatedAgainst(CAP).isFailure()).isTrue();
    }

    @Test
    void chunkSizeOfZeroOrLess_isRefusedNamingBothValues() {
        for (var chunkSize : new int[]{0, -1}) {
            ContentStoreConfig.contentStoreConfig(chunkSize, Compression.NONE)
                              .validatedAgainst(CAP)
                              .onSuccess(_ -> fail("chunkSizeBytes " + chunkSize + " can split nothing"))
                              .onFailure(cause -> {
                                  assertThat(cause).isInstanceOfSatisfying(ContentStoreError.ChunkSizeNotPositive.class, refusal -> {
                                      assertThat(refusal.chunkSizeBytes()).isEqualTo(chunkSize);
                                      assertThat(refusal.maxBlockBytes()).isEqualTo(CAP);
                                  });
                                  assertThat(cause.message()).contains("chunkSizeBytes " + chunkSize).contains(Integer.toString(CAP));
                              });
        }
    }

    @Test
    void chunkSizeOfOne_isAccepted_asTheControl() {
        assertThat(ContentStoreConfig.contentStoreConfig(1, Compression.NONE).validatedAgainst(CAP).isSuccess()).isTrue();
    }

    @Test
    void defaultConfig_fitsTheDefaultCap() {
        assertThat(ContentStoreConfig.contentStoreConfig().validatedAgainst(StorageInstance.DEFAULT_MAX_BLOCK_BYTES).isSuccess()).isTrue();
    }

    private static int largestFittingChunk(Compression compression) {
        var refusal = (ContentStoreError.ChunkSizeExceedsBlockLimit) ContentStoreConfig.contentStoreConfig(CAP, compression)
                                                                                      .validatedAgainst(CAP)
                                                                                      .fold(cause -> cause, _ -> null);

        return (int) refusal.largestChunkSize();
    }

    @Test
    void largestChunkSizeNamedByTheRefusal_fits_andOneMoreDoesNot() {
        for (var compression : Compression.values()) {
            var largest = largestFittingChunk(compression);

            assertThat(ContentStoreConfig.contentStoreConfig(largest, compression).validatedAgainst(CAP).isSuccess())
                    .as(compression + ": the largest named chunk fits").isTrue();
            assertThat(ContentStoreConfig.contentStoreConfig(largest + 1, compression).validatedAgainst(CAP).isFailure())
                    .as(compression + ": one more does not").isTrue();
        }
    }

    @Test
    void uncompressedChunkOfTheCapMinusTheEnvelope_storesDirectAndChunkedContent_withoutExceedingTheCap() {
        var instance = instanceWithCap(CAP);
        var chunk = CAP - ENVELOPE;
        var store = ContentStore.contentStore(instance,
                                              ContentStoreConfig.contentStoreConfig(chunk, Compression.NONE)
                                                                .validatedAgainst(CAP)
                                                                .unwrap());
        var direct = incompressible(chunk, 1);
        var chunked = incompressible(3 * chunk + 5, 2);

        store.put("direct", direct).await().onFailure(cause -> fail("direct: " + cause.message()));
        store.put("chunked", chunked).await().onFailure(cause -> fail("chunked: " + cause.message()));

        assertThat(store.get("direct").await().unwrap().unwrap()).isEqualTo(direct);
        assertThat(store.get("chunked").await().unwrap().unwrap()).isEqualTo(chunked);
    }

    @Test
    void compressedChunkAtTheLargestFittingSize_storesIncompressibleContent_withoutExceedingTheCap() {
        for (var compression : List.of(Compression.LZ4, Compression.ZSTD)) {
            var chunk = largestFittingChunk(compression);
            var store = ContentStore.contentStore(instanceWithCap(CAP),
                                                  ContentStoreConfig.contentStoreConfig(chunk, compression)
                                                                    .validatedAgainst(CAP)
                                                                    .unwrap());
            var direct = incompressible(chunk, 3);
            var chunked = incompressible(2 * chunk + 11, 4);

            store.put("direct", direct).await().onFailure(cause -> fail(compression + " direct: " + cause.message()));
            store.put("chunked", chunked).await().onFailure(cause -> fail(compression + " chunked: " + cause.message()));

            assertThat(store.get("direct").await().unwrap().unwrap()).as(compression + " direct").isEqualTo(direct);
            assertThat(store.get("chunked").await().unwrap().unwrap()).as(compression + " chunked").isEqualTo(chunked);
        }
    }
}
