// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.generatedgate;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Pins the evidence rule of `GateLog` on the log shapes the shipped goals print (#1998 review: the gate was blind to a skipped goal).
class GateLogTest {
    private static final String HEADERS = """
            [INFO] --- jbct:1.0.0-rc4:format-check (jbct-format-check) @ app ---
            [INFO] --- jbct:1.0.0-rc4:lint (jbct-lint) @ app ---
            """;

    private static final String RAN = HEADERS + """
            [INFO] Checking format of 2 Java file(s)
            [INFO] Linting 2 Java file(s)
            """;

    @Test
    void bothGatesOverThePromisedFiles_passes() {
        assertThat(GateLog.problems(RAN, 2)).isEmpty();
    }

    @Test
    void moreFilesThanPromised_passes() {
        assertThat(GateLog.problems(RAN, 1)).isEmpty();
    }

    @Test
    void skippedGates_failEvenThoughTheirHeadersAreInTheLog() {
        var skipped = HEADERS + """
                [INFO] Skipping JBCT format check
                [INFO] Skipping JBCT lint
                """;

        assertThat(GateLog.problems(skipped, 2)).anyMatch(problem -> problem.contains("a gate did not run"))
                                                .anyMatch(problem -> problem.startsWith("format-check reported no"))
                                                .anyMatch(problem -> problem.startsWith("lint reported no"));
    }

    @Test
    void emptyFileSet_failsWhenFilesWerePromised() {
        var empty = HEADERS + """
                [WARNING] JBCT format check examined NOTHING in app: no Java files under src/main/java
                [WARNING] JBCT lint examined NOTHING in app: no Java files under src/main/java
                """;

        assertThat(GateLog.problems(empty, 2)).anyMatch(problem -> problem.contains("examined NOTHING"))
                                              .anyMatch(problem -> problem.startsWith("format-check reported no"));
    }

    @Test
    void zeroCount_failsWhenFilesWerePromised() {
        var zero = HEADERS + """
                [INFO] Checking format of 0 Java file(s)
                [INFO] Linting 0 Java file(s)
                """;

        assertThat(GateLog.problems(zero, 1)).hasSize(2);
    }

    @Test
    void countBelowThePromise_fails_andThePrefixIsIrrelevant() {
        var fewer = HEADERS + """
                [WARNING] Checking format of 1 Java file(s)
                [WARNING] Linting 1 Java file(s)
                """;

        assertThat(GateLog.problems(fewer, 2)).hasSize(2);
    }

    @Test
    void variantWithoutJavaFiles_passesWithoutCounts_butNotWhenSkipped() {
        assertThat(GateLog.problems(HEADERS, 0)).isEmpty();
        assertThat(GateLog.problems(HEADERS + "[INFO] Skipping JBCT lint\n", 0)).hasSize(1);
        assertThat(GateLog.problems(RAN, 0)).hasSize(2);
    }
}
