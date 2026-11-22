// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;


/// #3303: a re-record by [WireAssignmentBaselineWriter] keeps every `SEMANTIC` declaration already in the baseline, because the
/// CI check refuses a dropped one as an edit of append-only history and no build would otherwise notice the drop.
///
/// The writer's recording step is gated on a system property and never runs in a normal build; these tests run its `render`
/// step over fixture files, so a lost, reordered or misplaced declaration is red here.
class WireAssignmentBaselineCarryOverTest {
    private static final String GENERATION = "GENERATION 7";

    private static final List<String> ASSIGNMENT = List.of("ENUM a.E X=0,Y=1", "SHAPE a.A (x:int)", "TAG a.A 1");

    private static final String FIRST = "SEMANTIC a.A.epoch stale-if-below -> stale-if-below-or-equal";
    private static final String SECOND = "SEMANTIC a.B.mode accept-if-any -> accept-if-all";

    @TempDir
    Path dir;

    @Test
    void render_keepsBothSemanticLinesInOrderRightAfterGenerationAndNothingElseChanges() {
        var existing = write("existing.txt",
                             "# old header",
                             "GENERATION 6",
                             "ENUM a.E X=0",
                             FIRST,
                             "SHAPE a.A (x:long)",
                             "TAG a.A 9",
                             "",
                             SECOND,
                             "TAG a.Z 3");
        var lines = WireAssignmentBaselineWriter.render(existing, GENERATION, ASSIGNMENT).lines().toList();
        var generationAt = lines.indexOf(GENERATION);

        assertEquals(List.of(FIRST, SECOND), lines.subList(generationAt + 1, generationAt + 3));
        assertEquals(WireAssignmentBaselineWriter.render(dir.resolve("absent.txt"), GENERATION, ASSIGNMENT),
                     lines.stream()
                          .filter(line -> !line.equals(FIRST) && !line.equals(SECOND))
                          .collect(Collectors.joining("\n"))
                    + "\n");
    }

    @Test
    void render_keepsFileOrderNotSortedOrder() {
        var existing = write("order.txt", "GENERATION 6", SECOND, "TAG a.A 9", "SEMANTIC a.C.x one -> two", FIRST);
        var lines = WireAssignmentBaselineWriter.render(existing, GENERATION, ASSIGNMENT).lines().toList();
        var generationAt = lines.indexOf(GENERATION);

        assertEquals(List.of(SECOND, "SEMANTIC a.C.x one -> two", FIRST),
                     lines.subList(generationAt + 1, generationAt + 4));
    }

    @Test
    void render_overABaselineWithoutSemanticLinesAddsNone() {
        var existing = write("plain.txt", "# old header", "GENERATION 6", "ENUM a.E X=0", "TAG a.A 9");

        assertEquals(WireAssignmentBaselineWriter.render(dir.resolve("absent.txt"), GENERATION, ASSIGNMENT),
                     WireAssignmentBaselineWriter.render(existing, GENERATION, ASSIGNMENT));
    }

    @Test
    void render_carriesAMalformedLineVerbatimRatherThanLaunderingIt() {
        var existing = write("malformed.txt", "GENERATION 6", "semantic a.B.mode", "TAG a.A 9");
        var lines = WireAssignmentBaselineWriter.render(existing, GENERATION, ASSIGNMENT).lines().toList();

        assertEquals("semantic a.B.mode",
                     lines.get(lines.indexOf(GENERATION) + 1));
    }

    @Test
    void render_overAMissingFileCarriesNothingOver() {
        var text = WireAssignmentBaselineWriter.render(dir.resolve("absent.txt"), GENERATION, ASSIGNMENT);

        assertEquals(List.of(GENERATION),
                     text.lines().filter(line -> line.startsWith("GENERATION")).toList());
        assertEquals(List.of(), WireAssignmentTripwireTest.semanticCandidates(text));
    }

    private Path write(String name, String... lines) {
        return Result.lift(Causes::fromThrowable,
                           () -> Files.writeString(dir.resolve(name),
                                                   String.join("\n", lines) + "\n",
                                                   StandardCharsets.UTF_8))
                     .unwrap();
    }
}
