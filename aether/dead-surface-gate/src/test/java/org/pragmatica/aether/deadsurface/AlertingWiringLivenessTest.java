// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deadsurface;

import java.util.List;

import org.pragmatica.aether.api.AlertForwarder;
import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.api.DashboardMetricsPublisher;
import org.pragmatica.aether.config.AlertConfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;


/// Pins the PRODUCTION WIRING of alerting, using the same bytecode reachability scanner that would
/// have caught #957 before it shipped.
///
/// #957's signature was exactly this: `AlertForwarder.alertForwarder(AlertConfig)` had **one** hit in
/// `src/main` — its own declaration. Nothing constructed it, so no alert left the process by any
/// path, while every unit test around `AlertManager` stayed green because each break sits one hop
/// outside the unit under test.
///
/// These assertions are the negation of that signature. They are structural on purpose: with the
/// default `AlertConfig`, `AlertForwarder.forward()` early-returns on `!enabled || httpOps.isEmpty()`,
/// so **a bound forwarder and an unbound one are observationally identical at production defaults**.
/// No runtime observation can separate them until `AlertConfig` is plumbed from node configuration
/// (#957's wire-or-remove decision). Until then this is the only instrument that can tell whether the
/// wiring is live, and "the object exists" would not be enough — the whole defect class is objects
/// that exist and are never reached.
///
/// Each assertion names the single production call site it pins, so a reader can check it by deleting
/// that line and watching this redden.
class AlertingWiringLivenessTest {
    private static final List<java.nio.file.Path> PRODUCTION_ROOTS = ReactorRoots.productionRoots();

    /// Without this, a red here is ambiguous between "the production wiring was deleted" (the defect
    /// these tests exist to catch) and "the node module was simply not compiled in this working copy"
    /// -- and that ambiguity would land on whoever is debugging a CI failure at speed. Fail-safe
    /// either way, since an uncompiled module can only make a method look LESS reachable; this makes
    /// the red say which. Mirrors `ConfigKeyLivenessTest`'s precondition.
    private static void assertCorpusIsComplete() {
        var missing = ReactorRoots.missingProductionOutput();

        assertTrue(missing.isEmpty(),
                   "Corpus incomplete: these module(s) have src/main/java but no target/classes, so a "
                   + "call site living there would read as unreachable and fail these assertions for the "
                   + "wrong reason: " + missing + ". Run a full reactor build "
                   + "(`mvn -pl aether install -DskipTests`) before trusting this gate's result.");
    }

    /// Pinned call site: `AetherNode.assembleNode` -> `alertManager.withAlertForwarder(...)`.
    ///
    /// `withAlertForwarder` is the seam that constructs AND binds in one production expression, so it
    /// is the method the boot path calls directly. `bindAlertForwarder` is deliberately NOT asserted
    /// here: its only caller is `withAlertForwarder` in the SAME declaring class, and this scanner
    /// counts only callers outside the declaring class — asserting it would be asserting something
    /// that stays true after the boot call is deleted, which is the opposite of a pin.
    @Test
    void alertForwarderIsConstructedAndBoundByProductionCode() throws Exception {
        assertCorpusIsComplete();

        var reachability = BytecodeReachability.scan(PRODUCTION_ROOTS);

        assertTrue(reachability.isReachable(MethodRef.of(AlertManager.class.getDeclaredMethod("withAlertForwarder",
                                                                                              AlertConfig.class))),
                   "#957: AlertManager.withAlertForwarder(AlertConfig) must be called by production code "
                   + "(AetherNode.assembleNode). If this is unreachable, the forwarder is never bound and "
                   + "every raised alert is delivered nowhere -- the exact defect this fix removes, and one "
                   + "that leaves all 1256 aether/node tests green");

        assertTrue(reachability.isReachable(MethodRef.of(AlertForwarder.class.getDeclaredMethod("alertForwarder",
                                                                                                AlertConfig.class))),
                   "#957: AlertForwarder.alertForwarder(AlertConfig) must be constructed by production code. "
                   + "This is the literal signature #957 reported dead -- one hit in src/main, its own "
                   + "declaration");
    }

    /// Pinned call site: `ManagementServer.onServerStarted` -> `metricsPublisher.start()`.
    ///
    /// Pre-existing and not introduced by the alerting fix, but the fix HANGS OFF IT: `start()` is
    /// what schedules `publishMetrics`, which is the only production caller of `checkThreshold`.
    /// Delete that one line and threshold evaluation silently stops for the whole cluster while every
    /// test stays green — and the change would look unrelated to whoever made it.
    @Test
    void dashboardMetricsPublisherIsStartedByProductionCode() throws Exception {
        assertCorpusIsComplete();

        var reachability = BytecodeReachability.scan(PRODUCTION_ROOTS);

        assertTrue(reachability.isReachable(MethodRef.of(DashboardMetricsPublisher.class.getDeclaredMethod("start"))),
                   "DashboardMetricsPublisher.start() must be called by production code "
                   + "(ManagementServer.onServerStarted). It schedules publishMetrics, the only production "
                   + "caller of AlertManager.checkThreshold; unreachable here means no threshold is ever "
                   + "evaluated on any node");
    }
}
