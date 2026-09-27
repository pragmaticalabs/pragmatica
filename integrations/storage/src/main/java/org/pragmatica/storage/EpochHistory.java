package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;

import static org.pragmatica.lang.Unit.unit;

/// The durable owner-epoch history of one [AppendLog] (#1567 A11, the KIP-101 leader-epoch checkpoint): the
/// offset at which each owner epoch began writing the log. Ranking replicas by `(last owner epoch, head)`
/// rather than by head alone is what keeps a deposed owner's unacknowledged tail from winning after an
/// ownership move, and the ranking has to survive a cold restart, so the history lives on the volume beside
/// the log -- `<log>.epochs`, never inside the WAL frames, whose format is unchanged.
///
/// Every change is durable before it is visible: the whole history is written to `<log>.epochs.tmp`, which
/// is forced with its metadata, renamed over `<log>.epochs` in one rename, and the directory is forced;
/// only then does the in-memory copy change. A crash at any point leaves either the old history or the new
/// one under the sidecar's name, never a mix, and a stale `.tmp` is ignored and overwritten. The file ends
/// in a CRC over everything before it, so a sidecar damaged on the device is refused -- loudly, not read
/// as a shorter history.
final class EpochHistory {
    static final String HEADER = "aether-append-log-epochs v1";
    private static final String CRC_PREFIX = "crc ";

    private final Path sidecar;
    private final Fn2<Result<Unit>, Path, byte[]> writer;
    private final Object lock = new Object();
    private volatile List<AppendLog.EpochStart> entries;

    private EpochHistory(Path sidecar, Fn2<Result<Unit>, Path, byte[]> writer, List<AppendLog.EpochStart> entries) {
        this.sidecar = sidecar;
        this.writer = writer;
        this.entries = entries;
    }

    /// `<name>.epochs` beside `<name>.wal` (any other log file name gets `.epochs` appended).
    static Path sidecarOf(Path logFile) {
        var name = logFile.getFileName().toString();
        var stem = name.endsWith(".wal")
                   ? name.substring(0, name.length() - ".wal".length())
                   : name;

        return logFile.resolveSibling(stem + ".epochs");
    }

    /// READ-ONLY (#1569 A3): the history on the volume, empty when no sidecar exists; nothing is written,
    /// renamed or removed, a stale `.tmp` included.
    static Result<List<AppendLog.EpochStart>> read(Path logFile) {
        var sidecar = sidecarOf(logFile);

        return FileOps.exists(sidecar)
               ? FileOps.readBytes(sidecar).flatMap(bytes -> decode(sidecar, bytes))
               : Result.success(List.of());
    }

    static Result<EpochHistory> load(Path logFile, Fn2<Result<Unit>, Path, byte[]> writer) {
        return read(logFile).map(history -> new EpochHistory(sidecarOf(logFile), writer, history));
    }

    List<AppendLog.EpochStart> entries() {
        return entries;
    }

    /// Monotonic: a lower epoch, or a start below the previous start, is refused; re-recording the last
    /// entry exactly is a no-op; the same epoch at a different start is refused.
    Result<Unit> recordStart(long ownerEpoch, long startOffset) {
        synchronized (lock) {
            return entries.isEmpty()
                   ? persist(appended(ownerEpoch, startOffset))
                   : recordAfter(entries.getLast(), ownerEpoch, startOffset);
        }
    }

    /// Drop every entry starting above `offset` -- the log was truncated back to `offset`, so no epoch began
    /// past it. Nothing to drop is a no-op, with no write.
    Result<Unit> truncateAbove(long offset) {
        synchronized (lock) {
            var kept = entries.stream().filter(entry -> entry.startOffset() <= offset).toList();

            return kept.size() == entries.size()
                   ? Result.unitResult()
                   : persist(kept);
        }
    }

    /// Runs under `lock`.
    private Result<Unit> recordAfter(AppendLog.EpochStart last, long ownerEpoch, long startOffset) {
        if (last.ownerEpoch() == ownerEpoch && last.startOffset() == startOffset) {
            return Result.unitResult();
        }

        return ownerEpoch <= last.ownerEpoch() || startOffset < last.startOffset()
               ? new AppendLog.WalError.EpochRegression(ownerEpoch, startOffset, last.ownerEpoch(), last.startOffset()).result()
               : persist(appended(ownerEpoch, startOffset));
    }

    private List<AppendLog.EpochStart> appended(long ownerEpoch, long startOffset) {
        var next = new ArrayList<>(entries);

        next.add(new AppendLog.EpochStart(ownerEpoch, startOffset));

        return List.copyOf(next);
    }

    /// Runs under `lock`. The in-memory copy changes only after the directory force: a caller never sees an
    /// entry a crash could take back.
    private Result<Unit> persist(List<AppendLog.EpochStart> next) {
        var temp = sidecar.resolveSibling(sidecar.getFileName() + ".tmp");

        return writer.apply(temp, encode(next))
                     .flatMap(_ -> FileOps.moveAtomic(temp, sidecar))
                     .flatMap(_ -> FileOps.forceDirectory(sidecar.toAbsolutePath().getParent()))
                     .mapError(cause -> new AppendLog.WalError.EpochWriteFailed(sidecar, cause.message()))
                     .map(_ -> install(next));
    }

    private Unit install(List<AppendLog.EpochStart> next) {
        entries = next;

        return unit();
    }

    static byte[] encode(List<AppendLog.EpochStart> history) {
        var body = new StringBuilder(HEADER).append('\n');

        history.forEach(entry -> body.append(entry.ownerEpoch()).append(' ').append(entry.startOffset()).append('\n'));

        return (body + CRC_PREFIX + Long.toHexString(crc(body.toString())) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Result<List<AppendLog.EpochStart>> decode(Path sidecar, byte[] bytes) {
        var text = new String(bytes, StandardCharsets.UTF_8);
        var crcAt = text.lastIndexOf(CRC_PREFIX);

        return crcAt < 0 || !text.endsWith("\n")
               ? corrupt(sidecar, "no checksum line")
               : decodeChecked(sidecar, text.substring(0, crcAt), text.substring(crcAt + CRC_PREFIX.length()).trim());
    }

    private static Result<List<AppendLog.EpochStart>> decodeChecked(Path sidecar, String body, String storedCrc) {
        if (!Long.toHexString(crc(body)).equals(storedCrc)) {
            return corrupt(sidecar, "checksum mismatch");
        }

        var lines = body.split("\n");

        return lines.length == 0 || !lines[0].equals(HEADER)
               ? corrupt(sidecar, "unknown header")
               : Result.allOf(Arrays.stream(lines, 1, lines.length)
                                              .map(line -> parseEntry(sidecar, line))
                                              .toList());
    }

    private static Result<AppendLog.EpochStart> parseEntry(Path sidecar, String line) {
        var fields = line.split(" ");

        return fields.length != 2
               ? corrupt(sidecar, "malformed entry '" + line + "'")
               : Result.all(parseLong(sidecar, fields[0]), parseLong(sidecar, fields[1])).map(AppendLog.EpochStart::new);
    }

    private static Result<Long> parseLong(Path sidecar, String field) {
        return Result.lift(_ -> new AppendLog.WalError.EpochHistoryCorrupt(sidecar, "not a number: '" + field + "'"),
                           () -> Long.parseLong(field));
    }

    private static <T> Result<T> corrupt(Path sidecar, String detail) {
        return new AppendLog.WalError.EpochHistoryCorrupt(sidecar, detail).result();
    }

    private static long crc(String body) {
        var crc = new CRC32();

        crc.update(body.getBytes(StandardCharsets.UTF_8));

        return crc.getValue();
    }
}
