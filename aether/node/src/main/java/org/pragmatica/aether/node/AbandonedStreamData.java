// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.lift;
import static org.pragmatica.lang.Unit.unit;


/// Boot-time detection of stream data left under the data root by an earlier NodeId (#2825, D7).
///
/// Every restart mints a fresh NodeId (#1543) and stream state lives under `stream-segments/<nodeId>/` and
/// `wal/<nodeId>` (the explicit `wal_path` base, or `stream-segments/<nodeId>/wal` when derived), so after a
/// restart the earlier incarnation's directories are abandoned: nothing reads them. After a whole-cluster restart that is all stream
/// data; a node restarted in place on its old disk abandons its own earlier directories the same way. The real fix is #1569.
/// This check only REPORTS them: one [OperatorWarningCode#STREAM_DATA_ABANDONED] naming the directories and their sizes.
/// The warning leaves a marker file [#MARKER] beside the stream data root. A later boot that finds nothing abandoned raises
/// [OperatorWarningCode#STREAM_DATA_ABANDONED_CLEARED] only when the marker exists, then deletes it, so a boot over a root that never
/// warned raises nothing. The recovery closes across nodes: the warning belongs to an earlier process, whose event layer is gone.
/// The marker holds the node ids that warned, and the aggregator key is code + node id + data root, so a clear closes only the warning
/// of the node that raised it, even when two nodes share a data-root path. A crash between the marker write and the raise yields one clear
/// for a never-raised warning (harmless), which is why the marker goes first. A marker that cannot be written is stated in the warning text.
/// A crash between the raise and the marker delete raises the clear once more on the next boot, which closes an already closed
/// warning and is harmless. The event hand-off is fire-and-forget, so "accepted" means the sink call returned; a process that dies
/// before the hand-off publishes leaves the warning open, as does any lost event. It never deletes, moves or opens a data file; an
/// unreadable directory is logged and skipped (forward recovery at the boot boundary: the check is advisory and must not stop a node).
public interface AbandonedStreamData {
    Logger log = LoggerFactory.getLogger(AbandonedStreamData.class);
    /// Name of the marker file written beside the stream data root while an abandoned-data warning may be open.
    String MARKER = ".stream-data-abandoned";

    /// A directory of another NodeId; `bytes` is -1 when its size could not be read.
    record Abandoned(Path path, long bytes) {
        public static Abandoned abandoned(Path path, long bytes) {
            return new Abandoned(path, bytes);
        }
    }

    /// Scans both bases and raises the warning, or the recovery when nothing is abandoned.
    @Contract
    static void check(OperatorWarningSink sink, Path streamSegmentsDir, Path walBaseDir, String selfId) {
        var roots = Stream.of(streamSegmentsDir, walBaseDir)
                          .filter(base -> isKeyedBy(base, selfId))
                          .map(AbandonedStreamData::parentOf)
                          .distinct()
                          .toList();
        var found = roots.stream().flatMap(root -> foreignDirectories(root, selfId).stream()).toList();
        var root = parentOf(streamSegmentsDir);

        if (found.isEmpty()) {
            recover(sink, root);
        } else {
            warn(sink, found, root, selfId);
        }
    }

    /// The marker goes down before the warning, so a warning that may be open always has one. A crash between the marker write and the
    /// raise leaves a marker for a warning never raised: the next clean boot raises one clear that closes nothing, which is harmless; the
    /// opposite order could leave a warning that never clears. A marker that cannot be written does not hide the warning (data at risk
    /// must surface): its text says the warning will not clear by itself (FER: the failure is carried to the operator in the warning).
    @Contract
    private static void warn(OperatorWarningSink sink, List<Abandoned> found, Path root, String selfId) {
        var marker = root.resolve(MARKER);
        var text = writeMarker(marker, selfId).fold(cause -> message(found) + unclearable(marker, cause),
                                                    _ -> message(found));

        OperatorWarnings.raise(log, sink, OperatorWarningCode.STREAM_DATA_ABANDONED, subject(selfId, root), "{}", text);
    }

    /// Raises one clear per node id the marker holds (each closes exactly the warning that node raised), then deletes the marker.
    @Contract
    private static void recover(OperatorWarningSink sink, Path root) {
        var marker = root.resolve(MARKER);

        if (Files.exists(marker)) {
            markedIds(marker).forEach(id -> clear(sink, root, id));
            deleteMarker(marker);
        }
    }

    @Contract
    private static void clear(OperatorWarningSink sink, Path root, String id) {
        OperatorWarnings.raise(log,
                               sink,
                               OperatorWarningCode.STREAM_DATA_ABANDONED_CLEARED,
                               subject(id, root),
                               "No stream data of another NodeId under {}",
                               root);
    }

    /// The aggregator key is code + subject: the node id keeps two nodes with one data-root path from closing each other's warning.
    private static String subject(String nodeId, Path root) {
        return nodeId + "@" + root;
    }

    private static List<String> markedIds(Path marker) {
        return lift(Causes::fromThrowable,
                    () -> Files.readAllLines(marker)).onFailure(cause -> log.warn("Cannot read marker {}, its warning stays open: {}",
                                                                                  marker,
                                                                                  cause.message()))
                   .or(List.of())
                   .stream()
                   .filter(id -> !id.isBlank())
                   .toList();
    }

    private static Result<Unit> writeMarker(Path marker, String selfId) {
        return existingIds(marker).map(ids -> idsWith(ids, selfId))
                          .flatMap(content -> lift(Causes::fromThrowable,
                                                   () -> Files.writeString(marker, content)))
                          .mapToUnit()
                          .onFailure(cause -> log.warn("Cannot write marker {}, the warning will not be cleared by a later boot: {}",
                                                       marker,
                                                       cause.message()));
    }

    private static String idsWith(List<String> existing, String selfId) {
        return Stream.concat(existing.stream(),
                             Stream.of(selfId))
                     .distinct()
                     .collect(Collectors.joining("\n", "", "\n"));
    }

    private static Result<List<String>> existingIds(Path marker) {
        return Files.exists(marker)
               ? lift(Causes::fromThrowable, () -> Files.readAllLines(marker))
               : Result.success(List.of());
    }

    private static String unclearable(Path marker, Cause cause) {
        return " The marker " + marker
             + " that lets a later boot close this warning could not be written (" + cause.message()
             + "), so this warning will not clear automatically.";
    }

    private static Unit deleteMarker(Path marker) {
        return lift(Causes::fromThrowable,
                    () -> Files.deleteIfExists(marker)).mapToUnit()
                   .onFailure(cause -> log.warn("Cannot delete marker {}, the next boot clears again: {}",
                                                marker,
                                                cause.message()))
                   .or(unit());
    }

    /// The warning text: what was found, that nothing was touched, and the operator action.
    static String message(List<Abandoned> found) {
        var listing = found.stream().map(AbandonedStreamData::describe).collect(Collectors.joining(", "));

        return "Stream data of an earlier NodeId was found and is NOT read by this node: " + listing
             + ". Every restart mints a fresh NodeId, so stream records and durable-entity state held only in these directories are"
             + " unavailable to this node even though the disks survived; after a whole-cluster restart that is all of them"
             + " (fix: #1569). Nothing was deleted or moved. Recover by republishing from the"
             + " source; once nothing needs these directories, remove them by hand to reclaim the space.";
    }

    private static String describe(Abandoned abandoned) {
        return abandoned.bytes() < 0
               ? abandoned.path() + " (size unknown)"
               : abandoned.path() + " (" + abandoned.bytes() + " bytes)";
    }

    /// A base is `<root>/<nodeId>` only when its last segment is this node's id. A derived WAL base (`<own dir>/wal`) is not: its
    /// parent is this node's own directory, whose children are not other nodes'.
    private static boolean isKeyedBy(Path base, String selfId) {
        return base.getFileName()
                   .toString()
                   .equals(selfId);
    }

    private static Path parentOf(Path base) {
        return base.toAbsolutePath()
                   .normalize()
                   .getParent();
    }

    private static List<Abandoned> foreignDirectories(Path root, String selfId) {
        return Files.isDirectory(root)
               ? scan(root, selfId)
               : List.of();
    }

    private static List<Abandoned> scan(Path root, String selfId) {
        return lift(Causes::fromThrowable,
                    () -> Files.list(root)).flatMap(entries -> lift(Causes::fromThrowable,
                                                                    () -> foreign(entries, selfId)))
                   .onFailure(cause -> log.warn("Cannot scan {} for abandoned stream data, skipping it: {}",
                                                root,
                                                cause.message()))
                   .or(List.of());
    }

    private static List<Abandoned> foreign(Stream<Path> entries, String selfId) {
        try (entries) {
            return entries.filter(Files::isDirectory)
                          .filter(dir -> !dir.getFileName()
                                             .toString()
                                             .equals(selfId))
                          .sorted()
                          .map(dir -> Abandoned.abandoned(dir,
                                                          sizeOf(dir)))
                          .toList();
        }
    }

    private static long sizeOf(Path dir) {
        return lift(Causes::fromThrowable,
                    () -> Files.walk(dir)).flatMap(files -> lift(Causes::fromThrowable,
                                                                 () -> sumOfFiles(files)))
                   .or(-1L);
    }

    private static long sumOfFiles(Stream<Path> files) {
        try (files) {
            return files.filter(Files::isRegularFile)
                        .mapToLong(AbandonedStreamData::lengthOf)
                        .sum();
        }
    }

    private static long lengthOf(Path file) {
        return lift(Causes::fromThrowable, () -> Files.size(file)).or(0L);
    }
}
