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


/// A claim that misses a port held by ANOTHER PROCESS part-way through its block must release every port it had already
/// locked (`PortLockFile#missed`): a leaked lock would keep those ports unclaimable by this JVM until it exits. A held
/// lock of another process is only visible as a `tryLock` miss across processes, so the holder is a child JVM.
class LockMissReleaseTest {
    private static final int BASE = 29_680;

    @TempDir
    Path lockDir;

    @Test
    @Timeout(120)
    void claim_missingAPortHeldByAnotherProcess_releasesTheLocksItAlreadyTook() throws Exception {
        var holder = spawnHolder(BASE + 64, PortBlock.STRIDE);

        try {
            waitForHeld();

            var straddling = allocatorOver(BASE);
            var before = allocatorOver(BASE - 64);

            assertThat(straddling.claim(1).isFailure()).as("the straddling block misses at its 65th port").isTrue();

            try (var claim = before.claim(1).unwrap()) {
                assertThat(claim.block().base()).as("ports taken before the miss were released").isEqualTo(BASE - 64);
            }
        } finally {
            Files.writeString(lockDir.resolve("stop"), "1");
            assertThat(holder.waitFor(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private EmberPortAllocator allocatorOver(int first) {
        return EmberPortAllocator.emberPortAllocator()
                                 .lockDir(lockDir)
                                 .range(first, first + PortBlock.STRIDE - 1)
                                 .budget(200, 50);
    }

    private void waitForHeld() throws Exception {
        var deadline = System.nanoTime() + 30_000_000_000L;

        while (!Files.exists(lockDir.resolve("held")) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(Files.exists(lockDir.resolve("held"))).as("the holder child locked its range").isTrue();
    }

    private Process spawnHolder(int firstPort, int length) throws Exception {
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = List.of(java,
                              "-cp",
                              System.getProperty("java.class.path"),
                              HoldChild.class.getName(),
                              lockDir.toString(),
                              Integer.toString(firstPort),
                              Integer.toString(length));

        return new ProcessBuilder(command).redirectErrorStream(true)
                                          .redirectOutput(ProcessBuilder.Redirect.to(new File(lockDir.toFile(), "holder.log")))
                                          .start();
    }
}
