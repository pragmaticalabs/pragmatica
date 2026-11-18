// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember.ports;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// A claimant process for [CrossProcessDisjointnessReaderTest]: claims one block, meets its partners at the probe-to-bind
/// rendezvous, records its claim in `<outDir>/claimed-<label>`, then stays alive until every party has recorded, so the
/// claims overlap in time. Exit codes: 0 met and recorded, 2 claim failed, 3 rendezvous not met (inconclusive).
///
/// Args: lockDir rendezvousDir outDir label parties mode(ON|OFF) firstBase lastBase
public final class ClaimChild {
    private ClaimChild() {}

    public static void main(String[] args) throws IOException, InterruptedException {
        var outDir = Path.of(args[2]);
        var label = args[3];
        var parties = Integer.parseInt(args[4]);
        var rendezvous = EmberPortRendezvous.emberPortRendezvous(Path.of(args[1]), parties, 15_000, label);
        var allocator = EmberPortAllocator.emberPortAllocator()
                                          .lockDir(Path.of(args[0]))
                                          .range(Integer.parseInt(args[6]), Integer.parseInt(args[7]))
                                          .claims(EmberPortAllocator.Claims.valueOf(args[5]))
                                          .afterProbe(rendezvous.hook());
        var result = allocator.claim(4);

        if (result.isFailure()) {
            var message = result.fold(cause -> cause.message(), _ -> "");

            System.out.println("CLAIM FAILED: " + message);
            System.exit(message.contains("inconclusive") ? 3 : 2);
        }

        var claim = result.unwrap();

        Files.createDirectories(outDir);
        Files.writeString(outDir.resolve("claimed-" + label), Integer.toString(claim.block().base()));

        var deadline = System.nanoTime() + 30_000_000_000L;

        while (recorded(outDir) < parties && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        claim.close();
        System.exit(0);
    }

    private static long recorded(Path outDir) throws IOException {
        try (var files = Files.list(outDir)) {
            return files.filter(file -> file.getFileName().toString().startsWith("claimed-")).count();
        }
    }
}
