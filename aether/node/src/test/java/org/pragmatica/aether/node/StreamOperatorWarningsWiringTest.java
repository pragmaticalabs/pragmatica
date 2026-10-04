// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #1730 phase 2: a replica's divergent-tail truncation reaches the operator only if the node binds its own
/// operator-warning sink into the stream partition manager; unbound, the manager keeps the log-only default and the
/// `confirmation_factor` 1 data-loss warning would be a log line nobody reads. `StreamPartitionManagerDivergentTailTest`
/// pins what is raised; this pins the production wiring that connects it, which that test cannot reach.
class StreamOperatorWarningsWiringTest {
    @Test
    void theNodeBindsItsOperatorWarningSinkIntoTheStreamPartitionManager() {
        assertThat(assemblyCode()).contains("streamPartitionManager.operatorWarnings(operatorWarningSink);");
    }

    /// [weak pin] source text, like the test above: the catch-up transport reports a source that never answers as a replica
    /// through the node's own sink (`stream-catchup-source-not-answering`). What is raised is pinned behaviourally by
    /// `ForwardCatchupTransportTest`; only this wiring is a grep.
    @Test
    void theNodeBindsItsOperatorWarningSinkIntoTheCatchupTransport() {
        assertThat(assemblyCode()).contains("forwardCatchupTransport(streamForwardClient,STREAM_CATCHUP_BATCH_SIZE,operatorWarningSink,System::currentTimeMillis)");
    }

    private static String assemblyCode() {
        var file = sourceRoot().resolve("org/pragmatica/aether/node/AetherNode.java");

        assertThat(file).exists();

        return readFile(file).lines()
                             .map(line -> line.replaceFirst("//.*$", ""))
                             .collect(Collectors.joining())
                             .replaceAll("\\s+", "");
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new AssertionError("Cannot read " + path, e);
        }
    }

    private static Path sourceRoot() {
        try {
            var testClasses = Path.of(StreamOperatorWarningsWiringTest.class.getProtectionDomain()
                                                                             .getCodeSource()
                                                                             .getLocation()
                                                                             .toURI());

            return testClasses.getParent()
                              .getParent()
                              .resolve("src/main/java");
        } catch (URISyntaxException e) {
            throw new AssertionError("Cannot locate module source root", e);
        }
    }
}
