// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.pg.split;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Deterministic property/fuzz test. For each seed it builds a sequence of statement bodies,
/// each embedding a `;`-bearing span (string, escape string, doubled-quote string, dollar tag,
/// quoted identifier, line and block comments), joins them with a single top-level `;`, and
/// asserts the splitter reproduces exactly the original bodies — never splitting inside a span.
/// Randomness is derived from the seed (no `Math.random`).
class StatementSplitterPropertyTest {
    private static final DialectSpec PG = Dialects.POSTGRESQL;

    /// Span fragments that each carry an internal top-level-looking `;` which MUST NOT split.
    private static final List<String> SPANS = List.of(
        "'a;b'",
        "'it''s;ok'",
        "E'x\\n;y'",
        "$$body;with;semis$$",
        "$tag$nested $$ ; $$ tag$tag$",
        "\"weird;ident\"",
        "/* block ; comment */",
        "/* outer ; /* inner ; */ tail ; */");

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 5, 7, 11, 13, 17, 23, 42, 99, 256, 1000, 65535})
    void split_reproducesEveryStatement_forSeededSequence(int seed) {
        var bodies = bodiesForSeed(seed);
        var script = String.join(";", bodies);

        var produced = StatementSplitter.split(script, PG)
                                        .onFailure(cause -> fail("seed " + seed + ": " + cause.message()))
                                        .or(List.of())
                                        .stream()
                                        .map(Statement::text)
                                        .toList();

        assertThat(produced).as("seed %d", seed).containsExactlyElementsOf(bodies);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 4, 9, 16, 25, 36, 49, 64, 81, 128, 333, 777, 2048, 40000})
    void split_neverSplitsInsideSpan_forSeededSequence(int seed) {
        var bodies = bodiesForSeed(seed);
        var script = String.join(";", bodies);

        var produced = StatementSplitter.split(script, PG)
                                        .onFailure(cause -> fail("seed " + seed + ": " + cause.message()))
                                        .or(List.of());

        assertThat(produced).as("seed %d count", seed).hasSize(bodies.size());
    }

    /// Builds a deterministic, non-empty sequence of statement bodies from the seed. Each body
    /// is `SELECT <span> AS c<i>` so it is unambiguously non-blank and span-protected.
    private static List<String> bodiesForSeed(int seed) {
        var count = 1 + (seed % 5);
        var state = seed * 2654435761L + 1;
        var bodies = new ArrayList<String>(count);

        for (var i = 0; i < count; i++) {
            state = nextState(state);
            var span = SPANS.get((int) Math.floorMod(state >> 16, SPANS.size()));
            bodies.add("SELECT " + span + " AS c" + i);
        }
        return bodies;
    }

    private static long nextState(long state) {
        return state * 6364136223846793005L + 1442695040888963407L;
    }
}
