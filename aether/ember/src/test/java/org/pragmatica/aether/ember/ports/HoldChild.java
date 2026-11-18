// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// A foreign lock holder for [LockMissReleaseTest]: byte-range locks `length` ports from `firstPort` in
/// `<lockDir>/ports.lock` as another process, writes `<lockDir>/held` once locked and stays alive until `<lockDir>/stop`
/// exists (at most 60 s).
///
/// Args: lockDir firstPort length
public final class HoldChild {
    private HoldChild() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        var lockDir = Path.of(args[0]);

        try (var channel = FileChannel.open(lockDir.resolve("ports.lock"),
                                            StandardOpenOption.READ,
                                            StandardOpenOption.WRITE,
                                            StandardOpenOption.CREATE);
             var lock = channel.tryLock(Long.parseLong(args[1]), Long.parseLong(args[2]), false)) {
            if (lock == null) {
                System.exit(2);
            }
            Files.writeString(lockDir.resolve("held"), "1");

            var deadline = System.nanoTime() + 60_000_000_000L;

            while (!Files.exists(lockDir.resolve("stop")) && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        }
    }
}
