// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.AppendLog.EpochStart;


/// The recovery segment of a divergent-tail cut (#2080): the records a replica is about to remove, written durably to a file
/// beside its WAL BEFORE anything is removed, so that nothing a writer was acknowledged for is destroyed by the cut. Nothing deletes
/// one automatically.
///
/// **Name.** `<wal file>.recovery-<first>-<last>-<wall-clock millis>.seg`, in the WAL's directory, so it sits on the volume the
/// records came from. The range and the clock keep two cuts of one partition apart; a name that is taken refuses the write.
///
/// **Layout** (big-endian, every record framed and checksummed):
/// ```
/// "AEPRSEG1"                                  8 bytes, magic
/// UTF stream name, int partition, long first, long last, long createdMillis
/// per record: long offset, long timestampMillis, byte hasEpoch [, UTF owner-epoch key], int payloadLength, payload, int crc32
/// long recordCount, "AEPRSEND"                trailer: a segment without it is incomplete and is refused by [#read]
/// ```
/// The owner-epoch key is the token the WAL's epoch history stores for the epoch the record was written under (the last start at or
/// below the record's offset); a copy whose history does not reach the record has none. The crc covers offset, timestamp and
/// payload. Re-injecting the records (follow-up tooling) assigns new offsets, so consumers may see duplicates or reordering.
///
/// **Durability.** The temporary file is forced, renamed into place, and the directory is forced; only then is the segment
/// returned, so a cut that follows can rely on it. Any failure refuses the segment, and the caller refuses the cut.
///
/// The private methods that declare `throws` are the JDK I/O boundary: every one is reached only through [Result#lift] in [#write] and
/// [#read], so no exception leaves this class.
public final class RecoverySegment {
    /// Events read from the ring per page while copying.
    static final int PAGE_EVENTS = 1024;
    private static final byte[] MAGIC = "AEPRSEG1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] END_MAGIC = "AEPRSEND".getBytes(StandardCharsets.US_ASCII);

    private RecoverySegment() {}

    /// Reads `maxEvents` records from `fromOffset`, the removed range being read from the ring that is about to lose it.
    @FunctionalInterface
    public interface Pages {
        Result<List<OffHeapRingBuffer.RawEvent>> read(long fromOffset, int maxEvents);
    }

    /// A segment that was written: where, what range, how many records.
    public record Preserved(Path file, long first, long last, long records) {}

    /// One preserved record.
    public record Entry(long offset, long timestampMillis, Option<String> epoch, byte[] payload) {}

    /// A segment read back.
    public record Contents(String streamName,
                           int partition,
                           long first,
                           long last,
                           long createdMillis,
                           List<Entry> entries) {}

    /// The fixed temporary name of a partition's segment in progress, beside its WAL.
    public static Path temporaryFor(Path wal) {
        return wal.resolveSibling(wal.getFileName() + ".recovery.tmp");
    }

    /// The segment file of the range `[first, last]` cut at `createdMillis`, beside `wal`.
    public static Path fileFor(Path wal, long first, long last, long createdMillis) {
        return wal.resolveSibling(wal.getFileName() + ".recovery-" + first + "-" + last + "-" + createdMillis + ".seg");
    }

    /// Writes the records `[first, last]` (`first <= last`) of `(streamName, partition)` to a segment beside `wal`, durably, or fails
    /// having left no segment under its final name. A range the pages do not fully deliver fails: a segment missing a record it
    /// names would be a false record of the loss.
    public static Result<Preserved> write(Path wal,
                                          String streamName,
                                          int partition,
                                          List<EpochStart> history,
                                          Pages pages,
                                          long first,
                                          long last,
                                          long createdMillis) {
        var temporary = temporaryFor(wal);
        var target = fileFor(wal, first, last, createdMillis);

        return Result.lift(RecoverySegment::writeFailed,
                           () -> writeTemporary(temporary,
                                                streamName,
                                                partition,
                                                history,
                                                pages,
                                                first,
                                                last,
                                                createdMillis))
                     .flatMap(result -> result)
                     .flatMap(count -> publish(temporary, target).map(_ -> new Preserved(target, first, last, count)));
    }

    private static Cause writeFailed(Throwable cause) {
        return Causes.cause("recovery segment could not be written: " + cause);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Long> writeTemporary(Path temporary,
                                               String streamName,
                                               int partition,
                                               List<EpochStart> history,
                                               Pages pages,
                                               long first,
                                               long last,
                                               long createdMillis) throws IOException {
        try (var channel = FileChannel.open(temporary,
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.TRUNCATE_EXISTING,
                                            StandardOpenOption.WRITE);
             var out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel), 1 << 16))) {
            out.write(MAGIC);
            out.writeUTF(streamName);
            out.writeInt(partition);
            out.writeLong(first);
            out.writeLong(last);
            out.writeLong(createdMillis);
            var copied = copyRange(out, history, pages, first, last);

            if (copied.isFailure()) {
                return copied;
            }

            out.writeLong(copied.or(0L));
            out.write(END_MAGIC);
            out.flush();
            channel.force(true);

            return copied;
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Result<Long> copyRange(DataOutputStream out,
                                          List<EpochStart> history,
                                          Pages pages,
                                          long first,
                                          long last) throws IOException {
        var next = first;

        while (next <= last) {
            var page = pages.read(next, (int) Math.min(PAGE_EVENTS, last - next + 1));

            if (page.isFailure()) {
                return page.map(_ -> 0L);
            }

            var events = page.or(List.of());

            if (events.isEmpty() || events.getFirst().offset() != next) {
                return Causes.cause("the ring returned no record at offset " + next
                                   + " of the range to preserve [" + first
                                   + ", " + last
                                   + "]").result();
            }

            for (var event : events) {
                writeEntry(out, event, epochAt(history, event.offset()));
            }

            next = events.getLast().offset() + 1;
        }

        return Result.success(last - first + 1);
    }

    private static Option<String> epochAt(List<EpochStart> history, long offset) {
        return Option.from(history.stream().filter(start -> start.startOffset() <= offset).reduce((_, later) -> later)).map(start -> start.key()
                                                                                                                                          .token());
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void writeEntry(DataOutputStream out, OffHeapRingBuffer.RawEvent event, Option<String> epoch) throws IOException {
        out.writeLong(event.offset());
        out.writeLong(event.timestamp());
        out.writeByte(epoch.isPresent()
                      ? 1
                      : 0);
        if (epoch.isPresent()) {
            out.writeUTF(epoch.or(""));
        }

        out.writeInt(event.data().length);
        out.write(event.data());
        out.writeInt(checksum(event.offset(), event.timestamp(), event.data()));
    }

    private static int checksum(long offset, long timestampMillis, byte[] payload) {
        var crc = new CRC32();

        crc.update(java.nio.ByteBuffer.allocate(16).putLong(offset).putLong(timestampMillis).array());
        crc.update(payload);

        return (int) crc.getValue();
    }

    /// The temporary file is complete and forced: it becomes the segment, and the directory entry is made durable.
    private static Result<Unit> publish(Path temporary, Path target) {
        return Result.lift(RecoverySegment::writeFailed, () -> move(temporary, target)).flatMap(StreamPartitionManager::syncDirectory);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Path move(Path temporary, Path target) throws IOException {
        if (Files.exists(target)) {
            throw new IOException("the segment name is taken: " + target);
        }

        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);

        return target.getParent();
    }

    /// Reads a segment back, verifying every checksum, the trailer and the record count.
    public static Result<Contents> read(Path file) {
        return Result.lift(RecoverySegment::readFailed, () -> readFile(file));
    }

    private static Cause readFailed(Throwable cause) {
        return Causes.cause("recovery segment could not be read: " + cause);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Contents readFile(Path file) throws IOException {
        try (var in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            expect(in, MAGIC);
            var stream = in.readUTF();
            var partition = in.readInt();
            var first = in.readLong();
            var last = in.readLong();
            var created = in.readLong();
            var entries = new ArrayList<Entry>();

            for (var offset = first; offset <= last; offset++) {
                entries.add(readEntry(in));
            }

            var count = in.readLong();

            expect(in, END_MAGIC);
            if (count != entries.size()) {
                throw new IOException("trailer counts " + count + " records, the segment holds " + entries.size());
            }

            return new Contents(stream, partition, first, last, created, List.copyOf(entries));
        }
    }

    @SuppressWarnings("JBCT-EX-01")
    private static Entry readEntry(DataInputStream in) throws IOException {
        var offset = in.readLong();
        var timestamp = in.readLong();
        var epoch = in.readByte() == 1
                    ? Option.some(in.readUTF())
                    : Option.<String> none();
        var payload = new byte[in.readInt()];

        in.readFully(payload);
        if (in.readInt() != checksum(offset, timestamp, payload)) {
            throw new IOException("checksum mismatch at offset " + offset);
        }

        return new Entry(offset, timestamp, epoch, payload);
    }

    @SuppressWarnings("JBCT-EX-01")
    private static void expect(DataInputStream in, byte[] magic) throws IOException {
        var found = new byte[magic.length];

        in.readFully(found);
        if (!java.util.Arrays.equals(found, magic)) {
            throw new IOException("not a recovery segment (bad magic)");
        }
    }
}
