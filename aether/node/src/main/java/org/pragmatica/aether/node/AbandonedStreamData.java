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
            warn(sink, found, root);
        }
    }

    /// The marker goes down before the warning, so a warning that may be open always has one.
    @Contract
    private static void warn(OperatorWarningSink sink, List<Abandoned> found, Path root) {
        writeMarker(root.resolve(MARKER));
        OperatorWarnings.raise(log, sink, OperatorWarningCode.STREAM_DATA_ABANDONED, root.toString(), "{}", message(found));
    }

    /// Raises the clear only for a marker an earlier boot left, and deletes the marker after the raise.
    @Contract
    private static void recover(OperatorWarningSink sink, Path root) {
        var marker = root.resolve(MARKER);

        if (Files.exists(marker)) {
            OperatorWarnings.raise(log,
                                   sink,
                                   OperatorWarningCode.STREAM_DATA_ABANDONED_CLEARED,
                                   root.toString(),
                                   "No stream data of another NodeId under {}",
                                   root);
            deleteMarker(marker);
        }
    }

    private static Unit writeMarker(Path marker) {
        return lift(Causes::fromThrowable,
                    () -> Files.writeString(marker, "stream-data-abandoned\n")).mapToUnit()
                   .onFailure(cause -> log.warn("Cannot write marker {}, the warning will not be cleared by a later boot: {}",
                                                marker,
                                                cause.message()))
                   .or(unit());
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
