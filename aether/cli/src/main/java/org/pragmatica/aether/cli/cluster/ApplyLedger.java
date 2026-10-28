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
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The durable write of the apply ledger: a UNIQUE temp file in the ledger directory, `force`, atomic rename,
/// `force` of the directory. A write that fails leaves the previous file exactly as it was and removes its own temp
/// file (a torn ledger would be refused as unreadable and block resume and rollback, the very operations that need
/// it), and a failure is a typed failure the caller must act on. The temp name is unique per write because two
/// writers (two CLI processes on one cluster, or a future beat thread) sharing one temp name tear each other's file.
@SuppressWarnings({"JBCT-UTIL-02", "JBCT-EX-01"})
final class ApplyLedger {
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
        var temp = Files.createTempFile(dir, "apply-state-", ".tmp");

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
