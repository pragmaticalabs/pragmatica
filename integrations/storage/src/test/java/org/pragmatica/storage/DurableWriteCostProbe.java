package org.pragmatica.storage;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;

import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1567 cost measurement, not a gate: `putRef` throughput on a `[memory, local disk]` instance with the
/// pre-#1567 local-disk write (`FileOps.writeBytes`, no directory force) against the durable one (file force,
/// rename, directory force). Arms are INTERLEAVED -- A B A B ... -- so load drift on a shared box lands on both
/// arms alike. Two block sizes: a cursor commit (64 B) and a sealed segment (256 KiB).
///
/// Runs only with `-Ds8.durableWriteCost=true`; prints one line per round and a median per arm.
@EnabledIfSystemProperty(named = "s8.durableWriteCost", matches = "true")
class DurableWriteCostProbe {
    private static final int ROUNDS = Integer.getInteger("s8.rounds", 6);
    private static final int WRITES = Integer.getInteger("s8.writes", 400);

    @TempDir
    Path dir;

    @Test
    void cursorSizedCommit() {
        measure("cursor-64B", 64);
    }

    @Test
    void segmentSizedSeal() {
        measure("segment-256KiB", 256 * 1024);
    }

    private void measure(String label, int blockBytes) {
        var before = new ArrayList<Double>();
        var after = new ArrayList<Double>();

        for (var round = 0; round < ROUNDS; round++) {
            before.add(opsPerSecond(label, "before", round, blockBytes, FileOps::writeBytes, _ -> Result.unitResult()));
            after.add(opsPerSecond(label, "after", round, blockBytes, FileOps::writeBytesForced, FileOps::forceDirectory));
        }

        System.out.printf(Locale.ROOT,
                          "S8-COST %s median ops/s: before=%.1f after=%.1f ratio=%.3f%n",
                          label,
                          median(before),
                          median(after),
                          median(after) / median(before));
    }

    private double opsPerSecond(String label,
                                String arm,
                                int round,
                                int blockBytes,
                                Fn2<Result<Unit>, Path, byte[]> writer,
                                Fn1<Result<Unit>, Path> forcer) {
        var root = dir.resolve(label + "-" + arm + "-" + round);
        var disk = LocalDiskTier.localDiskTier(root, Long.MAX_VALUE, timeSpan(30).seconds(), none(), some(writer), some(forcer))
                                .unwrap();
        var storage = StorageInstance.storageInstance(label, List.of(MemoryTier.memoryTier(1 << 20), disk));
        var started = System.nanoTime();

        for (var i = 0; i < WRITES; i++) {
            storage.putRef("cursor/" + (i % 16), block(blockBytes, round, i))
                   .await()
                   .onFailure(cause -> fail(cause.message()));
        }

        var elapsed = System.nanoTime() - started;
        var rate = WRITES / (elapsed / 1e9);

        System.out.printf(Locale.ROOT, "S8-COST %s round=%d arm=%s writes=%d ms=%.1f ops/s=%.1f%n",
                          label, round, arm, WRITES, elapsed / 1e6, rate);

        return rate;
    }

    /// Distinct content per write so no put deduplicates: every write reaches the disk tier.
    private static byte[] block(int size, int round, int index) {
        var bytes = new byte[size];

        ByteBuffer.wrap(bytes).putInt(round).putInt(index).putLong(System.nanoTime());

        return bytes;
    }

    private static double median(List<Double> values) {
        var sorted = values.stream().sorted().toList();

        return sorted.get(sorted.size() / 2);
    }
}
