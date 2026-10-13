// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// R-3.3.8: the reconcile steps run in the spec's order. The order is the process: stray adoption before placement (an adopted stray
/// counts as placed), placement before the drains, the backstop before the divergence clocks, completion repair before GC. The pass is a
/// sequence of calls in `Reconcile.run`, so the order is read from it.
class ReconcileOrderTest {
    private static final Path RECONCILE = Path.of("..",
                                                  "aether-deployment",
                                                  "src",
                                                  "main",
                                                  "java",
                                                  "org",
                                                  "pragmatica",
                                                  "aether",
                                                  "deployment",
                                                  "cluster",
                                                  "fsm2",
                                                  "Reconcile.java");

    /// Red when: two steps of R-3.3.8 are swapped, or one is dropped from the pass.
    @Test
    void run_callsTheStepsInTheSpecsOrder() throws IOException {
        var text = Files.readString(RECONCILE);
        var run = text.substring(text.indexOf("static ClusterModel run("),
                                 text.indexOf("private static ClusterModel collect("));
        var steps = List.of("Blueprints.sweepAwaited(m, ctx)",
                            "Blueprints.redrive(m, ctx)",
                            "Strays.sweep(m, ctx)",
                            "Placement.run(m, ctx)",
                            "Drains.resume(m, ctx)",
                            "Communities.run(m, ctx)",
                            "Orphans.run(m, ctx)",
                            "Backstop.run(m, ctx)",
                            "Divergence.run(m, ctx)",
                            "Outcomes.sweep(m, ctx)",
                            "collect(m, ctx)");
        var at = steps.stream().map(run::indexOf).toList();

        assertThat(at).as("every step is called").allMatch(index -> index >= 0);
        assertThat(at).as("in the order of R-3.3.8: awaited, intake, strays, placement, drains, communities, orphans, backstop, divergence, completion, GC")
                  .isSorted();
    }

    /// Red when: step 10's parts run in another order than R-3.3.8 lists (desired keys, departed rows, the ADMITTED rewrite,
    /// the `departing` re-reads, pending joins).
    @Test
    void collect_callsTheGcPartsInTheSpecsOrder() throws IOException {
        var text = Files.readString(RECONCILE);
        var collect = text.substring(text.indexOf("private static ClusterModel collect("));
        var parts = List.of("Gc.removeDesired(m, pair)",
                            "DepartureFlow.sweep(m, ctx)",
                            "DepartureFlow.rereadOverdue(m, ctx)",
                            "Joins.retryPending(m, ctx)");
        var at = parts.stream().map(collect::indexOf).toList();

        assertThat(at).allMatch(index -> index >= 0);
        assertThat(at).isSorted();
    }
}
