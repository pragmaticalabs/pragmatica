package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;


/// The acked marker of one [AppendLog] (#2201 B-04/B-17, I-05): how far the owner's VISIBLE position reached, the
/// highest offset its replicas had acknowledged, recorded beside the log as `<log>.acked` so a restarted owner need not
/// wait for every replica to re-ack the tail it had already exposed. Same family as the `.epochs` sidecar
/// ([EpochHistory]): a separate file, never inside the WAL frames, written through a temp file renamed over the
/// sidecar in one rename.
///
/// One line: `aether-append-log-acked v1 <epoch> <offset> <crc>`, where `<epoch>` is the [AppendLog.EpochKey] token the
/// log's epoch history attributes `<offset>` to (`-` for none) and `<crc>` is the hex CRC-32 of everything before it on
/// the line. A missing, torn or CRC-bad file is "no marker" (logged at WARN when damaged), never a failure: the marker
/// is an optimisation, and without it the tail waits for replica re-acks as it did before it existed.
///
/// Two kinds of write. RAISING it ([#advance], the owner's tick and close) writes the temp file without forcing it and
/// renames it: a power loss may lose the last advance, which only makes the restart wait for re-acks. LOWERING it
/// ([#lowerTo], before a cut and at the open-time clamp) and creating it ([#create]) are durable: the temp file is
/// forced, renamed, and the directory forced, the [EpochHistory] sequence, because a marker left above a cut would
/// vouch for records the cut removed. The in-memory copy changes only after the write.
///
/// Not thread-safe on its own: every method runs under the owning log's marker lock.
final class AckedMarker {
    static final String HEADER = "aether-append-log-acked v1";
    private static final String NO_EPOCH = "-";
    private static final Pattern TOKEN = Pattern.compile("\\p{Graph}+");
    private static final Pattern OFFSET = Pattern.compile("-1|\\d{1,18}");
    private static final Logger log = LoggerFactory.getLogger(AckedMarker.class);

    private final Path sidecar;
    private final Fn2<Result<Unit>, Path, byte[]> forcedWriter;
    private final Fn2<Result<Unit>, Path, byte[]> plainWriter;
    private final AtomicBoolean restorePending;
    private volatile Option<AppendLog.AckedMark> mark;
    /// A raise must exceed this: the last mark written or read, or the visible position the ring was seeded at
    /// ([#baseline]), so a log with no marker is never given one by its own seed.
    private volatile long highWater;

    private AckedMarker(Path sidecar,
                        Fn2<Result<Unit>, Path, byte[]> forcedWriter,
                        Fn2<Result<Unit>, Path, byte[]> plainWriter,
                        Option<AppendLog.AckedMark> mark) {
        this.sidecar = sidecar;
        this.forcedWriter = forcedWriter;
        this.plainWriter = plainWriter;
        this.mark = mark;
        this.highWater = mark.map(AppendLog.AckedMark::offset).or(Long.MIN_VALUE);
        this.restorePending = new AtomicBoolean(mark.filter(m -> m.offset() >= 0).isPresent());
    }

    /// `<name>.acked` beside `<name>.wal` (any other log file name gets `.acked` appended).
    static Path sidecarOf(Path logFile) {
        var name = logFile.getFileName().toString();
        var stem = name.endsWith(".wal")
                   ? name.substring(0,
                                    name.length() - ".wal".length())
                   : name;

        return logFile.resolveSibling(stem + ".acked");
    }

    static Path tempOf(Path sidecar) {
        return sidecar.resolveSibling(sidecar.getFileName() + ".tmp");
    }

    /// READ-ONLY: the marker on the volume; [Option#none] when there is none, or when it is torn or damaged.
    static Option<AppendLog.AckedMark> read(Path logFile) {
        var sidecar = sidecarOf(logFile);

        return FileOps.exists(sidecar)
               ? FileOps.readBytes(sidecar)
                        .flatMap(bytes -> decode(sidecar, bytes))
                        .onFailure(cause -> log.warn("Acked marker {} is ignored (no marker): {}",
                                                     sidecar,
                                                     cause.message()))
                        .option()
               : Option.none();
    }

    static AckedMarker load(Path logFile,
                            Fn2<Result<Unit>, Path, byte[]> forcedWriter,
                            Fn2<Result<Unit>, Path, byte[]> plainWriter) {
        return new AckedMarker(sidecarOf(logFile), forcedWriter, plainWriter, read(logFile));
    }

    Option<AppendLog.AckedMark> mark() {
        return mark;
    }

    Path sidecar() {
        return sidecar;
    }

    /// The marker read at open, once: the first call returns it (as lowered since by a cut or the clamp, or raised by a
    /// later write), every later call returns none. A log opened with no marker, or with a `-1` one, has nothing to
    /// restore.
    Option<AppendLog.AckedMark> takeRestore() {
        return restorePending.getAndSet(false)
               ? mark
               : Option.none();
    }

    /// The visible position the ring was seeded at: a raise must exceed it.
    Unit baseline(long seededVisible) {
        highWater = Math.max(highWater, seededVisible);

        return unit();
    }

    /// Born with a durable `-1` marker (know `6665558916`, S6): a log created by this build has acked nothing.
    Result<Unit> create() {
        return persistDurably(new AppendLog.AckedMark(Option.none(), -1L));
    }

    /// Raise the marker to `next` unless it does not exceed the high water (a no-op then); refused unwritten above
    /// `bound`, the highest offset the log's data reaches. Not forced (see the class doc).
    Result<Unit> advance(AppendLog.AckedMark next, long bound) {
        if (next.offset() <= highWater) {
            return Result.unitResult();
        }

        return next.offset() > bound
               ? AppendLog.WalError.AckedMarkerAboveHead.FACTORY.apply(sidecar, next.offset(), bound).result()
               : persist(next, plainWriter).map(_ -> install(next));
    }

    /// Lower the marker durably to `next` when it lies above `next`'s offset; a marker at or below it, or none, is left
    /// as it is, with no write.
    Result<Unit> lowerTo(AppendLog.AckedMark next) {
        return mark.filter(current -> current.offset() > next.offset())
                   .map(_ -> persistDurably(next))
                   .or(Result::unitResult);
    }

    private Result<Unit> persistDurably(AppendLog.AckedMark next) {
        return persist(next, forcedWriter).flatMap(_ -> forceDirectory())
                                          .map(_ -> install(next));
    }

    private Result<Unit> forceDirectory() {
        return FileOps.forceDirectory(sidecar.toAbsolutePath().getParent()).mapError(this::writeFailed);
    }

    private Result<Unit> persist(AppendLog.AckedMark next, Fn2<Result<Unit>, Path, byte[]> writer) {
        var temp = tempOf(sidecar);

        return writer.apply(temp,
                            encode(next))
                     .flatMap(_ -> FileOps.moveAtomic(temp, sidecar))
                     .mapError(this::writeFailed)
                     .mapToUnit();
    }

    private Cause writeFailed(Cause cause) {
        return AppendLog.WalError.AckedMarkerWriteFailed.FACTORY.apply(sidecar, cause);
    }

    private Unit install(AppendLog.AckedMark next) {
        highWater = next.offset();
        mark = Option.some(next);

        return unit();
    }

    static byte[] encode(AppendLog.AckedMark mark) {
        var body = HEADER + " " + mark.epoch()
                                      .map(AppendLog.EpochKey::token)
                                      .or(NO_EPOCH) + " " + mark.offset();

        return (body + " " + Long.toHexString(crc(body)) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static Result<AppendLog.AckedMark> decode(Path sidecar, byte[] bytes) {
        var text = new String(bytes, StandardCharsets.UTF_8);
        var crcAt = text.lastIndexOf(' ');

        return crcAt < 0 || !text.endsWith("\n")
               ? damaged(sidecar, "torn")
               : decodeChecked(sidecar,
                               text.substring(0, crcAt),
                               text.substring(crcAt + 1).trim());
    }

    private static Result<AppendLog.AckedMark> decodeChecked(Path sidecar, String body, String storedCrc) {
        if (!Long.toHexString(crc(body)).equals(storedCrc)) {
            return damaged(sidecar, "checksum mismatch");
        }

        var fields = body.split(" ");

        return fields.length != 4 || !(fields[0] + " " + fields[1]).equals(HEADER)
               ? damaged(sidecar, "unknown format")
               : Result.all(parseEpoch(sidecar, fields[2]), parseOffset(sidecar, fields[3])).map(AppendLog.AckedMark::new);
    }

    private static Result<Option<AppendLog.EpochKey>> parseEpoch(Path sidecar, String field) {
        return NO_EPOCH.equals(field)
               ? Result.success(Option.none())
               : Result.success(field)
                       .filter(corrupt(sidecar, "not an epoch key: '" + field + "'"), TOKEN.asMatchPredicate())
                       .flatMap(AppendLog.EpochKey::epochKey)
                       .map(Option::some);
    }

    private static Result<Long> parseOffset(Path sidecar, String field) {
        return Result.success(field)
                     .filter(corrupt(sidecar, "not an offset: '" + field + "'"), OFFSET.asMatchPredicate())
                     .map(Long::parseLong);
    }

    private static <T> Result<T> damaged(Path sidecar, String detail) {
        return corrupt(sidecar, detail).result();
    }

    private static AppendLog.WalError.AckedMarkerCorrupt corrupt(Path sidecar, String detail) {
        return AppendLog.WalError.AckedMarkerCorrupt.FACTORY.apply(sidecar, detail);
    }

    private static long crc(String body) {
        var crc = new CRC32();

        crc.update(body.getBytes(StandardCharsets.UTF_8));

        return crc.getValue();
    }
}
