// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;


/// #2825 (D7): a node booted over a data root holding another NodeId's stream or WAL directories raises
/// `stream-data-abandoned` with the directories and their sizes; its own directory alone raises no warning; an unreadable
/// directory never stops the boot; nothing is ever deleted.
class AbandonedStreamDataTest {
    private static final String SELF = "node-new";

    @TempDir
    Path root;

    private final BlockingQueue<OperatorWarning> events = new LinkedBlockingQueue<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(events::add);

    @Test
    void otherNodeDirectories_raiseOneWarningNamingBothBasesAndTheirSizes() throws Exception {
        var segments = root.resolve("stream-segments");
        var oldSegments = write(segments.resolve("node-old").resolve("segments").resolve("a.blk"),
                                1000);
        var oldWal = write(root.resolve("wal").resolve("node-old2").resolve("p0.wal"),
                           234);

        AbandonedStreamData.check(sink,
                                  segments.resolve(SELF),
                                  root.resolve("wal").resolve(SELF),
                                  SELF);
        var warning = next();

        assertThat(warning.code()).isEqualTo(OperatorWarningCode.STREAM_DATA_ABANDONED);
        assertThat(warning.subject()).isEqualTo(segments.toAbsolutePath().normalize().toString());
        assertThat(warning.message()).contains(segments.resolve("node-old").toAbsolutePath().normalize()
                                              + " (1000 bytes)")
                  .contains(root.resolve("wal").resolve("node-old2").toAbsolutePath().normalize() + " (234 bytes)")
                  .contains("Nothing was deleted or moved")
                  .contains("#1569");
        assertThat(events.poll(300, TimeUnit.MILLISECONDS)).isNull();
        assertThat(oldSegments).exists();
        assertThat(oldWal).exists();
        assertThat(segments.resolve(AbandonedStreamData.MARKER)).as("marker beside the data root").exists();
    }

    @Test
    void laterCleanBoot_withMarker_raisesTheClear_andDeletesTheMarker() throws Exception {
        var segments = root.resolve("stream-segments");
        var old = write(segments.resolve("node-old").resolve("a.blk"), 9);

        boot(segments);
        assertThat(next().code()).isEqualTo(OperatorWarningCode.STREAM_DATA_ABANDONED);
        Files.delete(old);
        Files.delete(old.getParent());
        boot(segments);
        var cleared = next();

        assertThat(cleared.code()).isEqualTo(OperatorWarningCode.STREAM_DATA_ABANDONED_CLEARED);
        assertThat(cleared.subject()).isEqualTo(segments.toAbsolutePath().normalize().toString());
        assertThat(segments.resolve(AbandonedStreamData.MARKER)).doesNotExist();
        boot(segments);
        assertNothingRaised();
    }

    @Test
    void cleanBoot_withoutMarker_raisesNothing() throws Exception {
        var segments = root.resolve("stream-segments");

        write(segments.resolve(SELF).resolve("a.blk"), 9);
        boot(segments);
        assertNothingRaised();
        assertThat(segments.resolve(AbandonedStreamData.MARKER)).doesNotExist();
    }

    @Test
    void crashBetweenRaiseAndMarkerDelete_raisesTheClearOnceMore_andThenSettles() throws Exception {
        var segments = root.resolve("stream-segments");
        var marker = write(segments.resolve(AbandonedStreamData.MARKER), 1);

        boot(segments);
        assertThat(next().code()).isEqualTo(OperatorWarningCode.STREAM_DATA_ABANDONED_CLEARED);
        assertThat(marker).doesNotExist();
        Files.write(marker, new byte[1]);
        boot(segments);
        assertThat(next().code()).isEqualTo(OperatorWarningCode.STREAM_DATA_ABANDONED_CLEARED);
        boot(segments);
        assertNothingRaised();
    }

    /// v3046: a node restarted in place on its old disk while the rest of the cluster stays up gets the same warning (the check reads
    /// the disk only). Its text must be accurate there: it names the directories, says they are not read by THIS node, and
    /// ties "all of them" to a whole-cluster restart instead of asserting one.
    @Test
    void rollingRestartInPlace_warnsWithATextThatDoesNotClaimAWholeClusterRestart() throws Exception {
        var segments = root.resolve("stream-segments");

        write(segments.resolve("node-before-the-restart").resolve("a.blk"), 5);
        boot(segments);
        var message = next().message();

        assertThat(message).contains("node-before-the-restart")
                           .contains("NOT read by this node")
                           .contains("after a whole-cluster restart that is all of them")
                           .doesNotContain("After a whole-cluster restart stream records");
    }

    private void boot(Path segments) {
        AbandonedStreamData.check(sink, segments.resolve(SELF), segments.resolve(SELF).resolve("wal"), SELF);
    }

    private void assertNothingRaised() throws InterruptedException {
        assertThat(events.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void bothBasesUnderOneRoot_reportEachDirectoryOnce() throws Exception {
        var segments = root.resolve("stream-segments");

        write(segments.resolve("node-old").resolve("a.blk"),
              7);
        AbandonedStreamData.check(sink, segments.resolve(SELF), segments.resolve(SELF), SELF);
        var message = next().message();

        assertThat(message.split("node-old", -1)).as("one mention of the directory").hasSize(2);
    }

    @Test
    void regularFilesInTheRoot_areNotReported() throws Exception {
        var segments = root.resolve("stream-segments");

        write(segments.resolve(".encryption-enabled"), 3);
        write(segments.resolve("node-old").resolve("a.blk"),
              7);
        AbandonedStreamData.check(sink,
                                  segments.resolve(SELF),
                                  segments.resolve(SELF).resolve("wal"),
                                  SELF);
        assertThat(next().message()).contains("node-old").doesNotContain(".encryption-enabled");
    }

    @Test
    void ownDirectoryOnly_raisesNoWarning() throws Exception {
        var segments = root.resolve("stream-segments");

        write(segments.resolve(SELF).resolve("segments").resolve("a.blk"),
              10);
        write(segments.resolve(SELF).resolve("wal").resolve("p0.wal"),
              10);
        AbandonedStreamData.check(sink,
                                  segments.resolve(SELF),
                                  segments.resolve(SELF).resolve("wal"),
                                  SELF);
        assertNothingRaised();
    }

    @Test
    void missingRoot_raisesNoWarning() throws Exception {
        AbandonedStreamData.check(sink,
                                  root.resolve("absent").resolve(SELF),
                                  root.resolve("absent").resolve(SELF).resolve("wal"),
                                  SELF);
        assertNothingRaised();
    }

    @Test
    void unreadableForeignDirectory_isReportedWithUnknownSize_andDoesNotThrow() throws Exception {
        var segments = root.resolve("stream-segments");
        var locked = segments.resolve("node-locked");

        write(locked.resolve("a.blk"), 5);
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(locked), "running as a user that ignores permissions");
            AbandonedStreamData.check(sink,
                                      segments.resolve(SELF),
                                      segments.resolve(SELF).resolve("wal"),
                                      SELF);
            var warning = next();

            assertThat(warning.code()).isEqualTo(OperatorWarningCode.STREAM_DATA_ABANDONED);
            assertThat(warning.message()).contains("node-locked (size unknown)");
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwx------"));
        }
    }

    @Test
    void unreadableRoot_doesNotThrowAndRaisesNoWarning() throws Exception {
        var segments = root.resolve("stream-segments");

        write(segments.resolve("node-old").resolve("a.blk"),
              5);
        Files.setPosixFilePermissions(segments, PosixFilePermissions.fromString("---------"));
        try {
            assumeFalse(Files.isReadable(segments), "running as a user that ignores permissions");
            AbandonedStreamData.check(sink,
                                      segments.resolve(SELF),
                                      segments.resolve(SELF).resolve("wal"),
                                      SELF);
            assertNothingRaised();
        } finally {
            Files.setPosixFilePermissions(segments, PosixFilePermissions.fromString("rwx------"));
        }
    }

    private OperatorWarning next() throws InterruptedException {
        var warning = events.poll(5, TimeUnit.SECONDS);

        assertThat(warning).as("operator warning handed to the sink").isNotNull();

        return warning;
    }

    private static Path write(Path file, int bytes) throws IOException {
        Files.createDirectories(file.getParent());

        return Files.write(file, new byte[bytes]);
    }
}
