package org.pragmatica.aether.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.function.Consumer;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class BlockIdTest {

    private static final byte[] SAMPLE_CONTENT = "hello world".getBytes(StandardCharsets.UTF_8);

    @Nested
    class FactoryTests {

        @Test
        void blockId_computesSha256_fromContent() throws Exception {
            var expected = MessageDigest.getInstance("SHA-256").digest(SAMPLE_CONTENT);
            var result = BlockId.blockId(SAMPLE_CONTENT);

            assertSuccess(result, id -> assertThat(id.hash()).isEqualTo(expected));
        }

        @Test
        void blockId_sameContent_sameId() {
            var first = BlockId.blockId(SAMPLE_CONTENT);
            var second = BlockId.blockId(SAMPLE_CONTENT);

            assertSuccess(first, id1 ->
                assertSuccess(second, id2 -> assertThat(id1).isEqualTo(id2))
            );
        }

        @Test
        void blockId_differentContent_differentId() {
            var first = BlockId.blockId("aaa".getBytes(StandardCharsets.UTF_8));
            var second = BlockId.blockId("bbb".getBytes(StandardCharsets.UTF_8));

            assertSuccess(first, id1 ->
                assertSuccess(second, id2 -> assertThat(id1).isNotEqualTo(id2))
            );
        }
    }

    @Nested
    class HexRoundTripTests {

        @Test
        void blockId_hexString_roundTrip() {
            var original = BlockId.blockId(SAMPLE_CONTENT);

            assertSuccess(original, id -> {
                var hex = id.hexString();
                var restored = BlockId.fromHex(hex);

                assertSuccess(restored, restoredId -> assertThat(restoredId).isEqualTo(id));
            });
        }

        @Test
        void blockId_hexString_isLowercaseHex() {
            assertSuccess(
                BlockId.blockId(SAMPLE_CONTENT),
                id -> assertThat(id.hexString()).matches("[0-9a-f]{64}")
            );
        }
    }

    @Nested
    class ShardPrefixTests {

        @Test
        void blockId_shardPrefix_twoLevelFormat() {
            assertSuccess(
                BlockId.blockId(SAMPLE_CONTENT),
                id -> {
                    var prefix = id.shardPrefix();

                    assertThat(prefix).matches("[0-9a-f]{2}/[0-9a-f]{2}");
                    assertThat(prefix.substring(0, 2)).isEqualTo(id.hexString().substring(0, 2));
                    assertThat(prefix.substring(3, 5)).isEqualTo(id.hexString().substring(2, 4));
                }
            );
        }
    }

    @Nested
    class EqualityTests {

        @Test
        void blockId_equals_hashCode_consistent() {
            var hex = HexFormat.of().formatHex(new byte[32]);
            var id1 = BlockId.fromHex(hex);
            var id2 = BlockId.fromHex(hex);

            assertSuccess(id1, first ->
                assertSuccess(id2, second -> {
                    assertThat(first).isEqualTo(second);
                    assertThat(first.hashCode()).isEqualTo(second.hashCode());
                })
            );
        }

        @Test
        void blockId_notEqual_differentHash() {
            var hex1 = HexFormat.of().formatHex(new byte[32]);
            var bytes2 = new byte[32];
            bytes2[0] = 1;
            var hex2 = HexFormat.of().formatHex(bytes2);

            assertSuccess(BlockId.fromHex(hex1), first ->
                assertSuccess(BlockId.fromHex(hex2), second ->
                    assertThat(first).isNotEqualTo(second)
                )
            );
        }
    }

    private static void assertSuccess(Result<BlockId> result, Consumer<BlockId> assertions) {
        result.onFailure(cause -> fail("Expected success but got: " + cause.message()))
              .onSuccess(assertions::accept);
    }
}
