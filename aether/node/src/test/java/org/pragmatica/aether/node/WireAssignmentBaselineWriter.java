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
import java.util.List;
import java.util.stream.Collectors;

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
///
/// The first non-comment line is `GENERATION <n>`, this binary's `JoinGenerationGuard.WIRE_GENERATION` (#3303 RA-2). A
/// changed or removed TAG/ENUM/SHAPE line needs `n` one above the base's, and an added-only change needs it unchanged;
/// `scripts/wire-generation-check.sh` gates both in CI.
///
/// `SEMANTIC` lines are hand-written declarations, not derivable from the registry, so a re-record carries over every one
/// already in the file (after the `GENERATION` line, in file order). Dropping them would be refused by the CI check as an
/// edit of append-only history; the writer never adds one (the declaring PR does).
class WireAssignmentBaselineWriter {
    private static final Path BASELINE = Path.of("src", "test", "resources", "wire-assignment-baseline.txt");

    @Test
    @EnabledIfSystemProperty(named = "wire.baseline.record", matches = "true")
    void recordBaseline() {
        var header = """
                     # Wire assignment baseline — #964, #1450. GENERATED; re-record with WireAssignmentBaselineWriter.
                     #
                     # TAG   <type> <wire tag>            the tag a peer must agree with to reach the right codec
                     # ENUM  <type> <NAME=ordinal,...>    the ordinal IS the encoding for a @Codec enum
                     # SHAPE <type> (<name>:<type>,...)   record components in DECLARATION ORDER — the body is a
                     #                                    positional concatenation, so this list IS the layout
                     #
                     # SEMANTIC <value> <old->new>        a declared SEMANTIC wire break: replicas now JUDGE an existing wire value
                     #                                    differently while no TAG, SHAPE or ENUM line moved. Written BY HAND, in the PR that
                     #                                    changes the judgement, and kept by the writer on every re-record. Adding one requires
                     #                                    GENERATION = base + 1 (scripts/wire-generation-check.sh); history is append-only, so
                     #                                    removing or editing one is refused. Form: SEMANTIC <value> <one-line old->new judgement>
                     #
                     # Every changed line is something a peer running the previous build decodes differently.
                     # Read the diff before committing it.
                     #
                     # On a SHAPE line: add / remove / reorder / retype a component = WIRE BREAK (no field count,
                     # no skip metadata, so an old reader desynchronises rather than ignoring the change).
                     # Rename only, same type and position = byte-neutral, pinned so a same-typed name swap is
                     # visible in this diff. See WireAssignmentTripwireTest for the derivation from the generator.
                     """;

        try {
            Files.createDirectories(BASELINE.getParent());
            var semantic = existingSemanticLines().stream().map(line -> line + "\n").collect(Collectors.joining());
            Files.writeString(BASELINE,
                              header + WireAssignmentTripwireTest.generationLine() + "\n" + semantic
                              + String.join("\n", WireAssignmentTripwireTest.currentAssignment()) + "\n",
                              StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> existingSemanticLines() throws IOException {
        if (!Files.exists(BASELINE)) {
            return List.of();
        }
        return Files.readAllLines(BASELINE, StandardCharsets.UTF_8)
                    .stream()
                    .filter(line -> line.startsWith("SEMANTIC"))
                    .toList();
    }
}
