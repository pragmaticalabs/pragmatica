// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The durable write of the apply ledger: a UNIQUE temp file in the ledger directory, `force`, atomic rename,
/// `force` of the directory. A write that fails leaves the previous file exactly as it was and removes its own temp
/// file (a torn ledger would be refused as unreadable and block resume and rollback, the very operations that need
/// it), and a failure is a typed failure the caller must act on. The temp name is unique per write because two
/// writers (two CLI processes on one cluster, or a future beat thread) sharing one temp name tear each other's file.
@SuppressWarnings({"JBCT-UTIL-02", "JBCT-EX-01"})
final class ApplyLedger {
    private static final String TEMP_PREFIX = "apply-state-";
    private static final String TEMP_SUFFIX = ".tmp";
    /// A temp file older than this belongs to a writer that died. One write is a file write plus an fsync and a
    /// rename (milliseconds); ten minutes is orders of magnitude past any live writer, so a concurrent writer's temp
    /// file in flight is never swept. A writer stalled longer than that (SIGSTOP, a hung disk) loses its temp file
    /// and its own rename then fails as a typed failure with the previous ledger intact.
    static final Duration STALE_TEMP_AGE = Duration.ofMinutes(10);

    private static final CopyOption[] MOVE_OPTIONS = {StandardCopyOption.ATOMIC_MOVE};

    // A directory cannot be opened for `force` on Windows; there the rename stays atomic but durability across
    // power loss is not claimed.
    private static final boolean DIRECTORY_FORCE_SUPPORTED = !System.getProperty("os.name", "")
                                                                    .toLowerCase(Locale.ROOT)
                                                                    .contains("win");

    private ApplyLedger() {}

    /// The step a test replaces to make a write fail part-way. It is handed the TEMP path, never the ledger.
    @FunctionalInterface
    interface TempWriter {
        Unit write(Path temp, byte[] content) throws Exception;
    }

    static TempWriter tempWriter = ApplyLedger::writeAndForce;

    /// Each durability step announces itself here AFTER it ran (`force-file:metadata=true`, `move:[ATOMIC_MOVE]`,
    /// `force-directory:metadata=true`), so a test can pin their order and arguments, which an outcome cannot show.
    static Consumer<String> trace = _ -> {};

    static Result<Unit> write(Path dir, String fileName, String json) {
        return Result.lift(ApplyState.PersistenceError::new, () -> doWrite(dir, fileName, json));
    }

    private static Unit doWrite(Path dir, String fileName, String json) throws Exception {
        Files.createDirectories(dir);
        var temp = Files.createTempFile(dir, TEMP_PREFIX, TEMP_SUFFIX);

        try {
            tempWriter.write(temp, json.getBytes(StandardCharsets.UTF_8));
            Files.move(temp, dir.resolve(fileName), MOVE_OPTIONS);
            trace.accept("move:" + Arrays.toString(MOVE_OPTIONS));
        } catch (Exception | Error e) {
            Files.deleteIfExists(temp);

            throw e;
        }

        return forceDirectory(dir);
    }

    /// Removes the ledger's own temp files (`apply-state-*.tmp`) left by a killed process and older than
    /// [#STALE_TEMP_AGE]; nothing else in the directory is touched. Each removal is reported on stderr.
    // FER: a sweep that cannot run or cannot delete is reported and skipped. Guarantee earned: reading the ledger never
    // fails because of leftover files. Mechanism: the leftovers are inert (unique names, never read) and the next
    // read tries again.
    static Unit sweepStaleTemps(Path dir) {
        return Result.lift(cause -> new SweepFailed(dir, cause),
                           () -> doSweep(dir))
                     .onFailure(cause -> System.err.println("Warning: " + cause.message()))
                     .or(Unit.unit());
    }

    private static Unit doSweep(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var entries = Files.list(dir)) {
                entries.filter(ApplyLedger::isStaleTemp).forEach(ApplyLedger::removeStaleTemp);
            }
        }

        return Unit.unit();
    }

    private static boolean isStaleTemp(Path file) {
        var name = file.getFileName().toString();

        return name.startsWith(TEMP_PREFIX)
               && name.endsWith(TEMP_SUFFIX)
               && olderThanBound(file);
    }

    private static boolean olderThanBound(Path file) {
        return Result.lift(cause -> new SweepFailed(file, cause),
                           () -> Files.getLastModifiedTime(file).toInstant())
                     .map(modified -> modified.isBefore(Instant.now().minus(STALE_TEMP_AGE)))
                     .or(false);
    }

    @Contract
    private static void removeStaleTemp(Path file) {
        Result.lift(cause -> new SweepFailed(file, cause),
                    () -> Files.deleteIfExists(file))
              .onSuccess(_ -> System.err.println("Removed stale apply-state temp file " + file
                                                + " (older than " + STALE_TEMP_AGE.toMinutes()
                                                + " minutes, left by a killed writer)"))
              .onFailure(cause -> System.err.println("Warning: " + cause.message()));
    }

    record SweepFailed(Path path, Throwable cause) implements org.pragmatica.lang.Cause {
        @Override
        public String message() {
            return "could not sweep stale apply-state temp files at " + path + ": " + cause.getMessage();
        }
    }

    private static Unit writeAndForce(Path temp, byte[] content) throws IOException {
        try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
            writeFully(channel, ByteBuffer.wrap(content));

            return forceChannel(channel, true, "file");
        }
    }

    /// A channel may accept fewer bytes than offered, or none (a full disk raises on the next write): keep writing
    /// until the buffer is empty, so a short write is never renamed into place as a complete ledger.
    static Unit writeFully(WritableByteChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }

        return Unit.unit();
    }

    private static Unit forceDirectory(Path dir) throws IOException {
        if (DIRECTORY_FORCE_SUPPORTED) {
            try (var channel = FileChannel.open(dir, StandardOpenOption.READ)) {
                return forceChannel(channel, true, "directory");
            }
        }

        return Unit.unit();
    }

    private static Unit forceChannel(FileChannel channel, boolean metaData, String what) throws IOException {
        channel.force(metaData);
        trace.accept("force-" + what + ":metadata=" + metaData);

        return Unit.unit();
    }
}
