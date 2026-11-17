// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/// #3274: `Warnings.raise` (with rule O's hold and release) is the one writer of the family state; the family sites call it and never
/// assign a family field themselves. Pins that no fsm2 main source outside `Warnings.java` assigns a family state field. `ClusterModel.copy`
/// is excluded by file name and by its line shape (`c.<field> = <field>.rebound(meter);`), nothing else in `ClusterModel.java` is.
class WarningsFamilyStateWritersTest {
    private static final String FIELDS = "invalidPlacementWarned|retryExhaustedWarned|schemaUnknownWarned|schemaStalledWarned|strayWarned"
                                         + "|heldByArtifact|holdSignatures|unmatchedWarned|noPolicyWarned|unplacedWarned|unjudgeableWarned"
                                         + "|suppressedWarned|growthBlocked|applyDeadlineWarned";
    private static final Pattern ASSIGNMENT = Pattern.compile("\\b\\w+\\.(" + FIELDS + ")\\s*=(?!=)");
    private static final Pattern COPY = Pattern.compile("^\\s*c\\.(" + FIELDS + ")\\s*=\\s*\\1\\.rebound\\(meter\\);\\s*$");

    private static boolean offending(Path source, String line) {
        var file = source.getFileName()
                         .toString();

        if (file.equals("Warnings.java") || !ASSIGNMENT.matcher(line)
                                                     .find()) {
            return false;
        }

        return !(file.equals("ClusterModel.java") && COPY.matcher(line)
                                                         .matches());
    }

    private static List<String> offenders(Path root) throws IOException {
        var hits = new ArrayList<String>();

        try (Stream<Path> files = Files.walk(root)) {
            for (var source : files.filter(path -> path.toString()
                                                       .endsWith(".java"))
                                   .toList()) {
                for (var line : Files.readAllLines(source)) {
                    if (offending(source, line)) {
                        hits.add(source.getFileName() + ": " + line.trim());
                    }
                }
            }
        }

        return hits;
    }

    /// Red when a family site (or any fsm2 main source besides `Warnings.java` and the copy) assigns a family state field.
    @Test
    void familyStateFields_areAssignedOnlyInWarnings() throws IOException {
        var root = Tripwire.repositoryRoot()
                           .resolve("aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/fsm2");

        assertThat(Files.isRegularFile(root.resolve("Warnings.java"))).as("precondition: the scan reaches the fsm2 sources").isTrue();
        assertThat(offenders(root)).isEmpty();
    }

    /// Positive control for the matcher: a site assignment is flagged in any file but `Warnings.java`, the copy line only in
    /// `ClusterModel.java`, and a comparison never.
    @Test
    void matcher_flagsAssignmentsAndSparesTheCopy() {
        var site = "        m.retryExhaustedWarned = m.retryExhaustedWarned.add(artifact);";
        var copy = "        c.strayWarned = strayWarned.rebound(meter);";

        assertThat(offending(Path.of("Failures.java"), site)).isTrue();
        assertThat(offending(Path.of("ClusterModel.java"), site)).isTrue();
        assertThat(offending(Path.of("Warnings.java"), site)).isFalse();
        assertThat(offending(Path.of("ClusterModel.java"), copy)).isFalse();
        assertThat(offending(Path.of("Slices.java"), copy)).isTrue();
        assertThat(offending(Path.of("Slices.java"), "if (m.growthBlocked == null) {")).isFalse();
    }
}
