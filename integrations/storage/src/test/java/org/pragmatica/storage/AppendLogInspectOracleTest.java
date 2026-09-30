package org.pragmatica.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// the streaming [AppendLog#inspect] against an oracle built from the frame layout alone (24-byte
/// header + payload, record i at offset i), not from either scan. Every truncation point and every single-bit flip
/// must yield exactly the valid prefix: never a head above the last intact record (too high = a probe promising
/// data the log does not hold), never below it (too low = an owner promoting past durable records).
class AppendLogInspectOracleTest {
    private static final int HEADER = 24;
    private static final int[] PAYLOADS = {0, 1, 23, 24, 25, 100, 4095, 4096, 4097, 70_000};
    private static final int[] BUFFERS = {1, 24, 25, 4096, 65_536};

    @TempDir
    Path root;

    @Test
    void inspect_everyTruncationAndEverySingleBitFlip_matchTheFrameOracle() throws Exception {
        var source = root.resolve("src/0.wal");
        var log = AppendLog.open(source).unwrap();
        var ends = new long[PAYLOADS.length];
        var position = 0L;

        for (var i = 0; i < PAYLOADS.length; i++) {
            var payload = new byte[PAYLOADS[i]];

            for (var j = 0; j < payload.length; j++) {
                payload[j] = (byte) (i * 31 + j);
            }
            log.append(i, payload, 1_000L + i).await().unwrap();
            position += HEADER + PAYLOADS[i];
            ends[i] = position;
        }
        log.close();

        var bytes = Files.readAllBytes(source);

        assertThat((long) bytes.length).as("frame layout assumption").isEqualTo(position);

        var target = root.resolve("t/0.wal");
        Files.createDirectories(target.getParent());
        var mismatches = new ArrayList<String>();
        var checks = 0;

        for (var cut : truncationPoints(bytes.length, ends)) {
            Files.write(target, java.util.Arrays.copyOf(bytes, cut));
            var intact = intactRecords(ends, cut);

            for (var buffer : BUFFERS) {
                checks++;
                expect(mismatches, "cut " + cut + " buf " + buffer, AppendLog.inspect(target, buffer).unwrap(), intact, ends, cut);
            }
        }

        for (var flipAt : flipPoints(bytes.length, ends)) {
            var flipped = bytes.clone();

            flipped[flipAt] ^= 0x01;
            Files.write(target, flipped);
            var intact = recordContaining(ends, flipAt);

            for (var buffer : BUFFERS) {
                checks++;
                expect(mismatches, "flip " + flipAt + " buf " + buffer, AppendLog.inspect(target, buffer).unwrap(), intact, ends, bytes.length);
            }
        }

        assertThat(checks).as("the oracle actually ran").isGreaterThan(10_000);
        assertThat(mismatches).as("inspect disagreeing with the frame oracle (first 20)")
                              .isEmpty();
    }

    private static void expect(List<String> mismatches, String label, AppendLog.LogExtent extent, int intact, long[] ends, long fileBytes) {
        var head = intact - 1L;
        var valid = intact == 0 ? 0L : ends[intact - 1];
        var low = intact == 0 ? -1L : 0L;

        if (extent.headOffset() != head || extent.validBytes() != valid || extent.lowOffset() != low || extent.fileBytes() != fileBytes) {
            if (mismatches.size() < 20) {
                mismatches.add(label + ": got " + extent + " want head " + head + " valid " + valid + " low " + low);
            }
        }
    }

    private static int intactRecords(long[] ends, long cut) {
        var n = 0;

        while (n < ends.length && ends[n] <= cut) {
            n++;
        }

        return n;
    }

    private static int recordContaining(long[] ends, int at) {
        var r = 0;

        while (ends[r] <= at) {
            r++;
        }

        return r;
    }

    private static List<Integer> truncationPoints(int size, long[] ends) {
        var points = new java.util.TreeSet<Integer>();

        for (var k = 0; k <= Math.min(size, 9_000); k++) {
            points.add(k);
        }
        for (var k = 9_000; k <= size; k += 997) {
            points.add(k);
        }
        for (var end : ends) {
            for (var d = -HEADER - 1; d <= HEADER + 1; d++) {
                var k = (int) end + d;

                if (k >= 0 && k <= size) {
                    points.add(k);
                }
            }
        }
        points.add(size);

        return List.copyOf(points);
    }

    private static List<Integer> flipPoints(int size, long[] ends) {
        var points = new java.util.TreeSet<Integer>();

        for (var k = 0; k < Math.min(size, 9_000); k++) {
            points.add(k);
        }
        for (var k = 9_000; k < size; k += 499) {
            points.add(k);
        }
        var start = 0L;

        for (var end : ends) {
            for (var d = 0; d < HEADER; d++) {
                points.add((int) start + d);
            }
            points.add((int) end - 1);
            start = end;
        }

        return List.copyOf(points);
    }
}
