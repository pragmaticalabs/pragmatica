// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;


/// Crash reclaim: a holder that dies by SIGKILL runs no shutdown path, yet the kernel drops its byte-range locks, so the
/// block is claimable again although the stale `ports.lock` file remains. Pins the fcntl-drop the allocator relies on
/// instead of any pid/liveness file (v3043 should-fix).
class KillReclaimTest {
    private static final int BASE = 31_000;
    private static final int SIGKILL_EXIT = 137;

    @TempDir
    Path lockDir;

    @Test
    @Timeout(120)
    void claim_blockOfASigkilledHolder_isRefusedWhileAlive_andClaimableOnceKilled() throws Exception {
        var allocator = EmberPortAllocator.emberPortAllocator()
                                          .lockDir(lockDir)
                                          .range(BASE, BASE + PortBlock.STRIDE - 1)
                                          .budget(300, 50);
        var holder = spawnHolder();

        try {
            waitForHeld();
            assertThat(allocator.claim(1).isFailure()).as("control: the live holder's block is refused").isTrue();

            holder.destroyForcibly();
            assertThat(holder.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(holder.exitValue()).as("SIGKILL: no shutdown path ran").isEqualTo(SIGKILL_EXIT);
            assertThat(lockDir.resolve("ports.lock")).as("the stale lock file stays").exists();

            try (var claim = allocator.claim(1).unwrap()) {
                assertThat(claim.block().base()).isEqualTo(BASE);
            }
        } finally {
            holder.destroyForcibly();
        }
    }

    private void waitForHeld() throws Exception {
        var deadline = System.nanoTime() + 30_000_000_000L;

        while (!Files.exists(lockDir.resolve("held")) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(lockDir.resolve("held")).as("the holder child locked its range").exists();
    }

    private Process spawnHolder() throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = List.of(java,
                              "-cp",
                              System.getProperty("java.class.path"),
                              HoldChild.class.getName(),
                              lockDir.toString(),
                              Integer.toString(BASE),
                              Integer.toString(PortBlock.STRIDE));

        return new ProcessBuilder(command).redirectErrorStream(true)
                                          .redirectOutput(ProcessBuilder.Redirect.to(new File(lockDir.toFile(), "holder.log")))
                                          .start();
    }
}
