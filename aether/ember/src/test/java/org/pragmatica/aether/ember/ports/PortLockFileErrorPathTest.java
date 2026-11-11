// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;


/// A lock ERROR (not a busy port) in the middle of a block releases the locks already taken (v3043 should-fix, mutant vc).
class PortLockFileErrorPathTest {
    private static final int FIRST_PORT = 31_300;
    private static final int INVALID_PORT = -1;

    @TempDir
    Path lockDir;

    @Test
    @Timeout(60)
    void lockFrom_errorAfterTwoLocksWereTaken_failsAsLockFailed_andReleasesBoth() throws Exception {
        try (var channel = FileChannel.open(lockDir.resolve("ports.lock"),
                                            StandardOpenOption.READ,
                                            StandardOpenOption.WRITE,
                                            StandardOpenOption.CREATE);
             var observer = FileChannel.open(lockDir.resolve("ports.lock"), StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var ports = List.of(FIRST_PORT, FIRST_PORT + 1, INVALID_PORT);

            var failure = PortLockFile.lockFrom(channel, ports, 0, List.of()).<Object> fold(cause -> cause, held -> held);

            assertThat(failure).isInstanceOf(PortError.LockFailed.class);
            assertThatCode(() -> observer.tryLock(FIRST_PORT, 2, false).release())
                    .as("both locks taken before the error were released")
                    .doesNotThrowAnyException();
        }
    }
}
