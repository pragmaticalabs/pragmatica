// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/// Re-records the baseline that [WireAssignmentTripwireTest] compares against (#964).
///
/// Run it deliberately, never as part of a normal build:
///
/// ```
/// mvn test -pl aether/node -Dtest=WireAssignmentBaselineWriter -Dwire.baseline.record=true
/// ```
///
/// Gated on a system property rather than `@Disabled` so that the recording step is a decision
/// somebody typed, and so it cannot be re-enabled by deleting an annotation during an unrelated
/// cleanup. A tripwire that silently re-records itself is not a tripwire.
///
/// The rewritten file is meant to be READ in the diff before committing: each changed line is a value
/// some other node decodes differently.
class WireAssignmentBaselineWriter {
    private static final Path BASELINE = Path.of("src", "test", "resources", "wire-assignment-baseline.txt");

    @Test
    @EnabledIfSystemProperty(named = "wire.baseline.record", matches = "true")
    void recordBaseline() {
        var header = """
                     # Wire assignment baseline — #964. GENERATED; re-record with WireAssignmentBaselineWriter.
                     #
                     # TAG  <type> <wire tag>            the tag a peer must agree with to reach the right codec
                     # ENUM <type> <NAME=ordinal,...>    the ordinal IS the encoding for a @Codec enum
                     #
                     # Every changed line is something a peer running the previous build decodes differently.
                     # Read the diff before committing it.
                     """;

        try {
            Files.createDirectories(BASELINE.getParent());
            Files.writeString(BASELINE,
                              header + String.join("\n", WireAssignmentTripwireTest.currentAssignment()) + "\n",
                              StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
