// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.membership.ntt.DrainProcedure;
import org.pragmatica.aether.deployment.drain.InFlightRequestTracker;
import org.pragmatica.aether.metrics.NodeReportedStateHolder;
import org.pragmatica.utility.react.ReactExecutor;
import org.pragmatica.utility.react.ReactFailureSink;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// #3193 ruling item 2, assembly seam: the halt the worker wiring hands to `OwnDirectiveRetirement`. The full
/// `workerActivation` needs a whole node (KV store, SWIM, router), so this pins its two parts that carry the decision:
/// the drain it starts and the way it is submitted. The observer's own conditions are in `OwnDirectiveRetirementTest`.
class DirectiveRetiredHaltTest {

    @Test
    void directiveRetiredHalt_startsALocalDrainUnderItsOwnReason_andRecordsNoOtherReason() {
        var reasons = new CopyOnWriteArrayList<DrainReason>();
        var exits = new AtomicInteger();
        var procedure = DrainProcedure.drainProcedure(InFlightRequestTracker.inFlightRequestTracker(),
                                                      () -> {},
                                                      reasons::add,
                                                      exits::incrementAndGet);
        var holder = NodeReportedStateHolder.nodeReportedStateHolder(() -> true);

        DrainAssembly.directiveRetiredHalt(procedure, holder)
                     .run();

        assertEquals(List.of(DrainReason.DIRECTIVE_RETIRED), reasons);
        assertTrue(procedure.state() != DrainProcedure.DrainState.INACTIVE);
    }

    /// A closed gate drops a side-effect reaction but never an undo, so the halt submitted by `haltThrough` still runs when
    /// the engine is stopping. Submitting it as a `react` instead would leave this latch unreleased.
    @Test
    void haltThrough_closedGate_stillRunsTheHalt() throws InterruptedException {
        var react = ReactExecutor.reactExecutor("test", () -> false, ReactFailureSink.loggingOnly());
        var ran = new CountDownLatch(1);

        WorkerHierarchyAssembly.haltThrough(react, ran::countDown)
                               .run();

        assertTrue(ran.await(5, TimeUnit.SECONDS));
        react.close();
    }
}
