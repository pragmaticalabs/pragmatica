package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

class KeyShardTest {
    private static final HexFormat HEX = HexFormat.of();

    /// For every count, the shards' prefixes partition the 256 first bytes: each byte in exactly one shard, as a contiguous
    /// ascending run, and `contains` agrees with the prefixes.
    @Test
    void shardsOfEveryCount_partitionTheFirstBytes_andContainsAgrees() {
        for (var count : List.of(1, 2, 3, 7, 16, 100, 255, 256)) {
            var all = new ArrayList<String>();

            for (var index = 0; index < count; index++) {
                var shard = KeyShard.keyShard(index, count).unwrap();
                var prefixes = shard.prefixes();

                assertThat(prefixes).as("count %d shard %d is not empty", count, index).isNotEmpty();
                assertThat(prefixes).isSorted();
                assertThat(prefixes).allSatisfy(prefix -> assertThat(shard.contains(idStartingWith(prefix))).isTrue());
                all.addAll(prefixes);
            }

            assertThat(all).as("count %d", count)
                           .containsExactlyElementsOf(IntStream.range(0, 256).mapToObj(b -> HEX.toHexDigits((byte) b)).toList());
        }
    }

    @Test
    void aBlockBelongsToExactlyOneShardOfACount() {
        var id = BlockId.blockId("which shard".getBytes(StandardCharsets.UTF_8)).unwrap();

        for (var count : List.of(1, 3, 4, 256)) {
            var owners = IntStream.range(0, count).filter(index -> KeyShard.keyShard(index, count).unwrap().contains(id)).count();

            assertThat(owners).as("count %d", count).isEqualTo(1);
        }
    }

    @Test
    void theEdgesOfTheByteRangeLandInTheFirstAndLastShard() {
        var last = KeyShard.keyShard(3, 4).unwrap();
        var first = KeyShard.keyShard(0, 4).unwrap();

        assertThat(first.prefixes()).startsWith("00").endsWith("3f");
        assertThat(last.prefixes()).startsWith("c0").endsWith("ff");
        assertThat(last.contains(idStartingWith("ff"))).isTrue();
        assertThat(first.contains(idStartingWith("00"))).isTrue();
        assertThat(first.contains(idStartingWith("40"))).isFalse();
    }

    @Test
    void invalidShards_areRefused() {
        assertThat(KeyShard.keyShard(0, 0).isFailure()).isTrue();
        assertThat(KeyShard.keyShard(0, 257).isFailure()).isTrue();
        assertThat(KeyShard.keyShard(-1, 4).isFailure()).isTrue();
        assertThat(KeyShard.keyShard(4, 4).isFailure()).isTrue();
        assertThat(KeyShard.keyShard(255, 256).isSuccess()).isTrue();
        assertThat(KeyShard.keyShard(0, 1).unwrap()).isEqualTo(KeyShard.all());
    }

    @Test
    void aCursorMustBeABlocksLowercaseHexId() {
        var hex = BlockId.blockId("cursor".getBytes(StandardCharsets.UTF_8)).unwrap().hexString();

        assertThat(KeyShard.resumeAfter(Option.some(Cursor.cursor(hex))).unwrap()).isEqualTo(Option.some(hex));
        assertThat(KeyShard.resumeAfter(Option.none()).unwrap()).isEqualTo(Option.none());
        assertThat(KeyShard.resumeAfter(Option.some(Cursor.cursor(hex.toUpperCase()))).isFailure()).isTrue();
        assertThat(KeyShard.resumeAfter(Option.some(Cursor.cursor(hex.substring(1)))).isFailure()).isTrue();
        assertThat(KeyShard.resumeAfter(Option.some(Cursor.cursor("../" + hex.substring(3)))).isFailure()).isTrue();
    }

    @Test
    void aPageLimitMustBePositive() {
        assertThat(KeyShard.pageLimit(1).unwrap()).isEqualTo(1);
        assertThat(KeyShard.pageLimit(0).isFailure()).isTrue();
        assertThat(KeyShard.pageLimit(-5).isFailure()).isTrue();
    }

    private static BlockId idStartingWith(String prefix) {
        return BlockId.fromHex(prefix + "00".repeat(31)).unwrap();
    }
}
