// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.wal;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import java.util.zip.CRC32;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Promise.promise;
import static org.pragmatica.lang.Result.unitResult;
import static org.pragmatica.lang.Unit.unit;

/// Crash-durable, append-only write-ahead log for ONE `(stream, partition)`
/// (streaming-persistence Phase A-WAL, step W2).
///
/// Every event is appended and **fsync'd before the publish is acked**; on recovery the log is
/// replayed to rebuild the ring tail, and entries are truncated once they seal into segments.
/// This class is self-contained — it owns its file, framing, group-commit and recovery, and is
/// wired into the node/stream path by later steps (W3–W6).
///
/// ## On-disk record format (fixed framing + payload), BIG_ENDIAN
/// ```
/// [ u32 payloadLen ][ u64 offset ][ u64 timestampMillis ][ u32 crc32 ][ payload (payloadLen) ]
/// ```
/// The 24-byte header is fixed; `payloadLen` is framing only. `crc32` is the CRC-32 (low 32 bits)
/// over `offset:8 || timestampMillis:8 || payload` — it deliberately EXCLUDES the leading
/// `payloadLen` so a torn header and a corrupted payload are both caught (a record is replayed
/// only when its length frames a region whose CRC matches). All integers are BIG_ENDIAN.
///
/// ## Group-commit fsync
/// `append` resolves its `Promise` ONLY after the record's bytes are `force(false)`-durable, while
/// still batching concurrent appends into a single fsync:
///   - a serialized write section (`writeLock`) assigns a monotonic write-seq, writes the framed
///     bytes at the current end position, and publishes `writtenSeq = seq` AFTER the write
///     completes — so any reader of `writtenSeq` sees a seq whose bytes are in the channel;
///   - `groupCommit(mySeq)` returns immediately if `syncedSeq >= mySeq`; otherwise, under
///     `syncLock`, it snapshots `target = writtenSeq`, issues ONE `force(false)` (covering every
///     completed write up to `target`, possibly many appenders' bytes), and publishes
///     `syncedSeq = target`.
/// An append resolves only after `syncedSeq >= mySeq`, i.e. after a fsync that happened-after a
/// write covering its own bytes — so no append acks before it is durable, yet a burst of N
/// concurrent appends typically costs far fewer than N fsyncs. `append` runs on the async executor
/// (`Promise.promise`), so a pipelined publisher that fires without awaiting maximizes batching.
///
/// ## Recovery (torn-write + CRC)
/// `open` scans from byte 0, validating each record by length-frame + CRC, and positions further
/// appends AFTER the last VALID record; a torn trailing record (fewer bytes than its frame needs)
/// or a CRC mismatch ends the scan and the stray tail is physically truncated. `replay` applies
/// the same scan, stopping cleanly at the first torn/CRC boundary and returning the valid prefix
/// (a partial tail after a crash is expected, NOT a failure). Earlier records are never corrupted
/// by a bad tail.
///
/// ## Truncate (threshold-lazy compaction)
/// `truncate(uptoOffset)` advances an in-memory discard watermark (`truncatedUpto`) in O(1); it
/// only rewrites the file (surviving records → temp + `force(true)` → atomic rename) once the file
/// has grown past `COMPACTION_THRESHOLD_BYTES`, reclaiming disk in one pass. `replay` always
/// filters by `max(afterOffset, truncatedUpto)`, so records discarded by a still-lazy truncate are
/// never observed regardless of the caller's `afterOffset`. The watermark is in-memory: after a
/// crash it resets and previously-truncated records reappear, but recovery filters them out via the
/// durable last-sealed offset (W4), so no double-apply — the watermark is purely a reclamation hint.
public final class PartitionWal implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(PartitionWal.class);

    /// Fixed framing header: u32 payloadLen + u64 offset + u64 timestampMillis + u32 crc32.
    private static final int HEADER_BYTES = 4 + 8 + 8 + 4;
    /// CRC pre-image header (offset + timestampMillis); payload is appended after it.
    private static final int CRC_HEADER_BYTES = 8 + 8;
    /// File-size watermark past which a `truncate` triggers a compaction rewrite (else O(1) lazy).
    private static final long COMPACTION_THRESHOLD_BYTES = 8L * 1024 * 1024;
    private static final Consumer<WalRecord> NO_OP = _ -> {};

    private static final Fn1<Cause, Throwable> APPEND_FAILED = t -> new WalError.AppendFailed(t.getMessage());
    private static final Fn1<Cause, Throwable> TRUNCATE_FAILED = t -> new WalError.TruncateFailed(t.getMessage());
    private static final Fn1<Cause, Throwable> CLOSE_FAILED = t -> new WalError.CloseFailed(t.getMessage());

    private final Path file;
    private final Object writeLock = new Object();
    private final Object syncLock = new Object();

    private volatile FileChannel channel;
    private long nextSeq;                          // guarded by writeLock
    private volatile long writtenSeq;              // published under writeLock AFTER the write completes
    private volatile long syncedSeq;               // published under syncLock AFTER force(false)
    private volatile long writePosition;           // end of valid data; guarded by writeLock
    private volatile long lastOffset;              // last appended offset (-1 when none)
    private volatile long truncatedUpto = -1;      // in-memory discard watermark
    private volatile long lastCompactedUpto = -1;  // last physical compaction point
    private volatile boolean closed;

    private PartitionWal(Path file, FileChannel channel, long writePosition, long lastOffset) {
        this.file = file;
        this.channel = channel;
        this.writePosition = writePosition;
        this.lastOffset = lastOffset;
    }

    /// Open-or-create the WAL for `file`, positioned for further appends AFTER its last VALID
    /// record (a torn trailing record is physically truncated). Creates parent directories.
    public static Result<PartitionWal> open(Path file) {
        return FileOps.createDirectories(file.toAbsolutePath().getParent())
                      .flatMap(_ -> recover(file));
    }

    /// Append a record and GROUP-COMMIT fsync. The returned `Promise` resolves ONLY after this
    /// record's bytes are `force(false)`-durable; concurrent appends may share one fsync.
    public Promise<Unit> append(long offset, byte[] payload, long timestampMillis) {
        return closed
               ? WalError.General.WAL_CLOSED.promise()
               : promise(() -> appendDurably(offset, payload, timestampMillis));
    }

    /// Replay records in file order, skipping `offset <= afterOffset` (and any discarded by a lazy
    /// truncate), invoking `consumer` for each remaining VALID record. Stops cleanly at a torn
    /// final record or a CRC mismatch, returning the valid prefix consumed so far.
    public Result<Unit> replay(long afterOffset, Consumer<WalRecord> consumer) {
        return closed
               ? WalError.General.WAL_CLOSED.result()
               : readRegion().map(buf -> replayScan(buf, afterOffset, consumer));
    }

    /// Discard all records with `offset <= uptoOffset`; records with `offset > uptoOffset` remain
    /// replayable. Threshold-lazy: O(1) watermark bump until the file grows past the compaction
    /// threshold, then a single survivors-rewrite reclaims disk.
    public Result<Unit> truncate(long uptoOffset) {
        return closed
               ? WalError.General.WAL_CLOSED.result()
               : advanceWatermark(uptoOffset);
    }

    /// Flush + fsync + close the channel. Best-effort: a close-time I/O fault is logged, not thrown.
    @Contract
    @Override
    public void close() {
        closed = true;
        Result.lift(CLOSE_FAILED, () -> channel.force(false))
              .flatMap(_ -> Result.lift(CLOSE_FAILED, () -> channel.close()))
              .onFailure(cause -> log.warn("PartitionWal close issue for {}: {}", file, cause.message()));
    }

    public Path path() {
        return file;
    }

    /// Last appended offset, or `-1` when the WAL holds no valid record.
    public long lastOffset() {
        return lastOffset;
    }

    // === append path ===

    private Result<Unit> appendDurably(long offset, byte[] payload, long timestampMillis) {
        return writeRecord(offset, payload, timestampMillis).flatMap(this::groupCommit);
    }

    private Result<Long> writeRecord(long offset, byte[] payload, long timestampMillis) {
        synchronized (writeLock) {
            var seq = nextSeq + 1;
            var position = writePosition;
            var frame = ByteBuffer.wrap(frameBytes(offset, payload, timestampMillis));

            return writeFrameAt(frame, position)
                       .map(_ -> publishWrite(seq, offset, position + frame.capacity()));
        }
    }

    private Result<Unit> writeFrameAt(ByteBuffer frame, long position) {
        return Result.lift(APPEND_FAILED, () -> channel.write(frame, position))
                     .flatMap(written -> requireFullWrite(written, frame.capacity()));
    }

    private static Result<Unit> requireFullWrite(int written, int expected) {
        return written == expected
               ? unitResult()
               : new WalError.AppendFailed("short write: %d of %d bytes".formatted(written, expected)).result();
    }

    private long publishWrite(long seq, long offset, long newPosition) {
        nextSeq = seq;
        writePosition = newPosition;
        lastOffset = offset;
        writtenSeq = seq;
        return seq;
    }

    private Result<Unit> groupCommit(long mySeq) {
        return syncedSeq >= mySeq ? unitResult() : forceUpTo(mySeq);
    }

    private Result<Unit> forceUpTo(long mySeq) {
        synchronized (syncLock) {
            return syncedSeq >= mySeq ? unitResult() : forceAndPublish();
        }
    }

    private Result<Unit> forceAndPublish() {
        var target = writtenSeq;

        return Result.lift(APPEND_FAILED, () -> channel.force(false))
                     .onSuccess(_ -> syncedSeq = target);
    }

    // === replay path ===

    private Result<ByteBuffer> readRegion() {
        return FileOps.readBytes(file).map(PartitionWal::wrapBigEndian);
    }

    private Unit replayScan(ByteBuffer buf, long afterOffset, Consumer<WalRecord> consumer) {
        scan(buf, Math.max(afterOffset, truncatedUpto), consumer);
        return unit();
    }

    // === truncate / compaction path ===

    private Result<Unit> advanceWatermark(long uptoOffset) {
        truncatedUpto = Math.max(truncatedUpto, uptoOffset);

        return shouldCompact() ? compact() : unitResult();
    }

    private boolean shouldCompact() {
        return writePosition >= COMPACTION_THRESHOLD_BYTES && truncatedUpto > lastCompactedUpto;
    }

    private Result<Unit> compact() {
        synchronized (writeLock) {
            synchronized (syncLock) {
                return rewriteSurvivors(truncatedUpto);
            }
        }
    }

    private Result<Unit> rewriteSurvivors(long uptoOffset) {
        return survivorBytes(uptoOffset).flatMap(survivors -> swapIn(survivors, uptoOffset));
    }

    private Result<byte[]> survivorBytes(long uptoOffset) {
        return readRegion().map(buf -> collectSurvivors(buf, uptoOffset));
    }

    private Result<Unit> swapIn(byte[] survivors, long uptoOffset) {
        return writeTempSynced(survivors)
                   .flatMap(_ -> renameTempOverFile())
                   .flatMap(_ -> reopenAfterCompaction(survivors.length, uptoOffset));
    }

    private Result<Unit> writeTempSynced(byte[] survivors) {
        return Result.lift(TRUNCATE_FAILED, () -> writeSynced(tempPath(), survivors));
    }

    private Result<Unit> renameTempOverFile() {
        return Result.lift(TRUNCATE_FAILED, () -> Files.move(tempPath(), file, StandardCopyOption.REPLACE_EXISTING))
                     .mapToUnit();
    }

    private Result<Unit> reopenAfterCompaction(int newSize, long uptoOffset) {
        return Result.lift(TRUNCATE_FAILED, () -> channel.close())
                     .flatMap(_ -> openChannel(file))
                     .map(reopened -> installCompacted(reopened, newSize, uptoOffset));
    }

    private Unit installCompacted(FileChannel reopened, int newSize, long uptoOffset) {
        channel = reopened;
        writePosition = newSize;
        syncedSeq = writtenSeq;
        lastCompactedUpto = uptoOffset;
        return unit();
    }

    private Path tempPath() {
        return file.resolveSibling(file.getFileName() + ".compact");
    }

    // === open / recovery ===

    private static Result<PartitionWal> recover(Path file) {
        return openChannel(file).flatMap(channel -> recoverFrom(file, channel));
    }

    private static Result<PartitionWal> recoverFrom(Path file, FileChannel channel) {
        return FileOps.readBytes(file)
                      .map(PartitionWal::wrapBigEndian)
                      .map(buf -> scan(buf, Long.MIN_VALUE, NO_OP))
                      .flatMap(result -> truncateAndBuild(file, channel, result));
    }

    private static Result<PartitionWal> truncateAndBuild(Path file, FileChannel channel, ScanResult result) {
        return Result.lift(t -> new WalError.OpenFailed(file, t.getMessage()),
                           () -> channel.truncate(result.validEnd()))
                     .map(_ -> new PartitionWal(file, channel, result.validEnd(), result.lastOffset()));
    }

    private static Result<FileChannel> openChannel(Path file) {
        return Result.lift(t -> new WalError.OpenFailed(file, t.getMessage()),
                           () -> FileChannel.open(file,
                                                  StandardOpenOption.CREATE,
                                                  StandardOpenOption.READ,
                                                  StandardOpenOption.WRITE));
    }

    // === framing / scan helpers ===

    /// Scan `buf` in record order, invoking `consumer` for each VALID record whose
    /// `offset > afterOffset`. Returns the byte offset just past the last fully-valid record and
    /// that record's offset; stops at the first torn/CRC boundary without throwing.
    private static ScanResult scan(ByteBuffer buf, long afterOffset, Consumer<WalRecord> consumer) {
        var validEnd = 0L;
        var lastOffset = -1L;

        while (buf.remaining() >= HEADER_BYTES) {
            var recordStart = buf.position();
            var payloadLen = buf.getInt();
            var offset = buf.getLong();
            var timestampMillis = buf.getLong();
            var storedCrc = buf.getInt();

            if (payloadLen < 0 || payloadLen > buf.remaining()) {
                buf.position(recordStart);
                break;
            }
            var payload = new byte[payloadLen];
            buf.get(payload);

            if (crc32(offset, timestampMillis, payload) != storedCrc) {
                buf.position(recordStart);
                break;
            }
            validEnd = buf.position();
            lastOffset = offset;

            if (offset > afterOffset) {
                consumer.accept(new WalRecord(offset, timestampMillis, payload));
            }
        }
        return new ScanResult(validEnd, lastOffset);
    }

    private static byte[] collectSurvivors(ByteBuffer buf, long uptoOffset) {
        var out = new ByteArrayOutputStream();

        scan(buf, uptoOffset, record -> out.writeBytes(frameBytes(record.offset(), record.payload(), record.timestampMillis())));
        return out.toByteArray();
    }

    private static byte[] frameBytes(long offset, byte[] payload, long timestampMillis) {
        var buf = ByteBuffer.allocate(HEADER_BYTES + payload.length).order(ByteOrder.BIG_ENDIAN);

        buf.putInt(payload.length);
        buf.putLong(offset);
        buf.putLong(timestampMillis);
        buf.putInt(crc32(offset, timestampMillis, payload));
        buf.put(payload);
        return buf.array();
    }

    private static int crc32(long offset, long timestampMillis, byte[] payload) {
        var header = ByteBuffer.allocate(CRC_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);

        header.putLong(offset);
        header.putLong(timestampMillis);

        var crc = new CRC32();

        crc.update(header.array());
        crc.update(payload);
        return (int) crc.getValue();
    }

    private static ByteBuffer wrapBigEndian(byte[] bytes) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
    }

    /// Open a fresh temp file, write `data` in full and `force(true)` it before the caller renames
    /// it over the live WAL — so a crash during compaction can never expose a half-written file.
    /// Suppressed: open+write+fsync+close is a cohesive I/O leaf with no single-op equivalent, and
    /// try-with-resources guarantees the channel is closed even if `force` fails (resource safety a
    /// hand-composed `Result` chain could not provide). Matches the codebase's `readStreamBytes`
    /// idiom for unavoidable imperative I/O leaves.
    @SuppressWarnings("JBCT-EX-01")
    private static Unit writeSynced(Path path, byte[] data) throws IOException {
        try (var channel = FileChannel.open(path,
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.WRITE,
                                            StandardOpenOption.TRUNCATE_EXISTING)) {
            var buf = ByteBuffer.wrap(data);

            while (buf.hasRemaining()) {
                channel.write(buf);
            }
            channel.force(true);
        }
        return unit();
    }

    /// A single replayable event: caller-supplied `offset`, append `timestampMillis`, opaque `payload`.
    public record WalRecord(long offset, long timestampMillis, byte[] payload) {}

    private record ScanResult(long validEnd, long lastOffset) {}

    /// Failures surfaced by the WAL surface. I/O faults carry the underlying detail message; the
    /// enum holds the single fixed-message state error.
    public sealed interface WalError extends Cause {
        enum General implements WalError {
            WAL_CLOSED("Partition WAL is closed");

            private final String message;

            General(String message) {
                this.message = message;
            }

            @Override
            public String message() {
                return message;
            }
        }

        record OpenFailed(Path file, String detail) implements WalError {
            @Override
            public String message() {
                return "WAL open failed for %s: %s".formatted(file, detail);
            }
        }

        record AppendFailed(String detail) implements WalError {
            @Override
            public String message() {
                return "WAL append failed: " + detail;
            }
        }

        record TruncateFailed(String detail) implements WalError {
            @Override
            public String message() {
                return "WAL truncate failed: " + detail;
            }
        }

        record CloseFailed(String detail) implements WalError {
            @Override
            public String message() {
                return "WAL close failed: " + detail;
            }
        }
    }
}
