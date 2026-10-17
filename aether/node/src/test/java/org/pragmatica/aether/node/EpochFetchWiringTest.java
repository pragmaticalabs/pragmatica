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

/// #1873 (KIP-320): the epoch-validated fetch works only if the node binds its committed ownership records into the stream
/// partition manager (the serving side checks every consumer read against them), hands the owner activation the ring
/// incarnation and the commit that records where an epoch begins, gives the consumer runtime the validating reader, and
/// binds the operator-warning sink a consumer's re-seek is reported through. Each piece is pinned by its own unit test;
/// this pins the production wiring that connects them, which none of those can reach.
class EpochFetchWiringTest {
    @Test
    void theNodeWiresTheEpochValidatedFetch() {
        var code = assemblyCode();

        assertThat(code).contains("streamPartitionManager.ownershipRecords((stream,partition)->kvStore.getTyped(StreamPartitionOwnershipKey.streamPartitionOwnershipKey(stream,partition),StreamPartitionOwnershipValue.class));");
        assertThat(code).contains("streamPartitionManager.ringIncarnation(stream,partition).or(-1L),streamLineageCommit(()->kvStore.getTyped(LeaderKey.INSTANCE,LeaderValue.class),clusterCommandApplier,hlcClock));");
        assertThat(code).contains("StreamConsumerRuntime.validatingReader(");
        assertThat(code).contains("streamReadRouter::readValidated)");
        assertThat(code).contains("streamConsumerRuntime.operatorWarnings(operatorWarningSink);");
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
            var testClasses = Path.of(EpochFetchWiringTest.class.getProtectionDomain()
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
