// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli.cluster;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// The durable write of the apply ledger: temp file, `force`, atomic rename, `force` of the directory. A write
/// that fails leaves the previous file exactly as it was (a torn file would be refused as unreadable and block
/// resume and rollback, the very operations that need it), and a failure is a typed failure the caller must act on.
@SuppressWarnings({"JBCT-UTIL-02", "JBCT-EX-01"})
final class ApplyLedger {
    private static final String TEMP_FILE_NAME = "apply-state.json.tmp";

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

    static Result<Unit> write(Path dir, String fileName, String json) {
        return Result.lift(ApplyState.PersistenceError::new, () -> doWrite(dir, fileName, json));
    }

    private static Unit doWrite(Path dir, String fileName, String json) throws Exception {
        var temp = dir.resolve(TEMP_FILE_NAME);

        Files.createDirectories(dir);
        tempWriter.write(temp, json.getBytes(StandardCharsets.UTF_8));
        Files.move(temp, dir.resolve(fileName), StandardCopyOption.ATOMIC_MOVE);

        return forceDirectory(dir);
    }

    private static Unit writeAndForce(Path temp, byte[] content) throws IOException {
        try (var channel = FileChannel.open(temp,
                                            StandardOpenOption.CREATE,
                                            StandardOpenOption.TRUNCATE_EXISTING,
                                            StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(content);

            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }

            channel.force(true);
        }

        return Unit.unit();
    }

    private static Unit forceDirectory(Path dir) throws IOException {
        if (DIRECTORY_FORCE_SUPPORTED) {
            try (var channel = FileChannel.open(dir, StandardOpenOption.READ)) {
                channel.force(true);
            }
        }

        return Unit.unit();
    }
}
