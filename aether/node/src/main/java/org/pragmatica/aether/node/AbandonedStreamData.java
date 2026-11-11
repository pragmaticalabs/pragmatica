// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.lift;


/// Boot-time detection of stream data left under the data root by an earlier NodeId (#2825, D7).
///
/// Every restart mints a fresh NodeId (#1543) and stream state lives under `stream-segments/<nodeId>/` and
/// `wal/<nodeId>` (the explicit `wal_path` base, or `stream-segments/<nodeId>/wal` when derived), so after a
/// whole-cluster restart the previous run's directories are abandoned: nothing reads them. The real fix is #1569.
/// This check only REPORTS them: one [OperatorWarningCode#STREAM_DATA_ABANDONED] naming the directories and their sizes,
/// or, when none is found, [OperatorWarningCode#STREAM_DATA_ABANDONED_CLEARED], which the aggregator publishes only
/// after a published warning for the same subject (the data root, which is stable across restarts). It never deletes,
/// moves or opens a data file; an unreadable directory is logged and skipped (forward recovery at the boot boundary: the
/// check is advisory and must not stop a node).
public interface AbandonedStreamData {
    Logger log = LoggerFactory.getLogger(AbandonedStreamData.class);

    /// A directory of another NodeId; `bytes` is -1 when its size could not be read.
    record Abandoned(Path path, long bytes) {}

    /// Scans both bases and raises the warning, or the recovery when nothing is abandoned.
    @Contract
    static void check(OperatorWarningSink sink, Path streamSegmentsDir, Path walBaseDir, String selfId) {
        var roots = Stream.of(streamSegmentsDir, walBaseDir)
                          .map(AbandonedStreamData::parentOf)
                          .distinct()
                          .toList();
        var found = roots.stream()
                         .flatMap(root -> foreignDirectories(root, selfId).stream())
                         .toList();
        var subject = parentOf(streamSegmentsDir).toString();

        raise(sink, found, subject);
    }

    @Contract
    private static void raise(OperatorWarningSink sink, List<Abandoned> found, String subject) {
        if (found.isEmpty()) {
            OperatorWarnings.raise(log, sink, OperatorWarningCode.STREAM_DATA_ABANDONED_CLEARED, subject,
                                   "No stream data of another NodeId under {}", subject);
        } else {
            OperatorWarnings.raise(log, sink, OperatorWarningCode.STREAM_DATA_ABANDONED, subject, "{}", message(found));
        }
    }

    /// The warning text: what was found, that nothing was touched, and the operator action.
    static String message(List<Abandoned> found) {
        var listing = found.stream()
                           .map(AbandonedStreamData::describe)
                           .collect(Collectors.joining(", "));

        return "Stream data of an earlier NodeId was found and is NOT read by this node: " + listing
               + ". After a whole-cluster restart stream records and durable-entity state are lost even when the disks survive"
               + " (every restart mints a fresh NodeId; fix: #1569). Nothing was deleted or moved. Recover by republishing from the"
               + " source; once nothing needs these directories, remove them by hand to reclaim the space.";
    }

    private static String describe(Abandoned abandoned) {
        return abandoned.bytes() < 0
               ? abandoned.path() + " (size unknown)"
               : abandoned.path() + " (" + abandoned.bytes() + " bytes)";
    }

    private static Path parentOf(Path base) {
        return base.toAbsolutePath()
                   .normalize()
                   .getParent();
    }

    private static List<Abandoned> foreignDirectories(Path root, String selfId) {
        return lift(Causes::fromThrowable, () -> listForeign(root, selfId))
                   .onFailure(cause -> log.warn("Cannot scan {} for abandoned stream data, skipping it: {}", root, cause.message()))
                   .or(List.of());
    }

    private static List<Abandoned> listForeign(Path root, String selfId) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }

        try (var entries = Files.list(root)) {
            return entries.filter(Files::isDirectory)
                          .filter(dir -> !dir.getFileName()
                                             .toString()
                                             .equals(selfId))
                          .sorted()
                          .map(dir -> new Abandoned(dir, sizeOf(dir)))
                          .toList();
        }
    }

    private static long sizeOf(Path dir) {
        return lift(Causes::fromThrowable, () -> walkSize(dir)).or(-1L);
    }

    private static long walkSize(Path dir) throws IOException {
        try (var files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                        .mapToLong(AbandonedStreamData::lengthOf)
                        .sum();
        }
    }

    private static long lengthOf(Path file) {
        return lift(Causes::fromThrowable, () -> Files.size(file)).or(0L);
    }
}
