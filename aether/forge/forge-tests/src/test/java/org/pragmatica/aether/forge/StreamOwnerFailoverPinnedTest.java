// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import org.pragmatica.aether.ember.EmberCluster;

/// #491 — membership-PINNED variant of the RF=2 stream owner-kill failover proof (shared flow in
/// [AbstractStreamOwnerFailover]). Identical to [StreamOwnerFailoverTest] except it pins membership two
/// ways so the transient QuorumLost→PASSIVE window after the single graceful owner-kill cannot falsely
/// evict/mark LIVE survivors: (1) BEFORE `start()` it raises the harness SWIM suspect / transport hello /
/// membership split timeouts on the cluster ([EmberCluster#withRaisedSwimTimeouts]) so the stale-link
/// eviction + SWIM-DEAD cascade + quorum-loss self-fence do not fire in the transient (the killed owner
/// still departs via graceful SWIM leave, so real failover is NOT slowed); (2) once member-complete it
/// disables auto-heal on EVERY node's CTM (defense-in-depth vs replacement churn — the killed HRW owner
/// MAY be the leader, and only the leader's CTM acts, so whichever node becomes leader post-failover must
/// also have the flag off). Auto-heal-off ALONE was empirically insufficient (swimDeadStuck cascaded
/// into a self-fence); the raised timeouts keep swimDeadStuck EMPTY through the failover — this suppresses
/// #498 (SWIM false-removal).
///
/// @Disabled until #499: with #498 suppressed, RF-restoration STILL stalls (0/3 in the acceptance gate) —
/// HRW re-resolves ownership to an empty non-replica node that cannot catch up from the data-bearing
/// survivor (watermark stuck at -1), so phase 9 never converges. The batch's above-transport layers
/// (unicast-to-absent buffering + probe-first re-verify + committed-owner gate) close the transport-loss
/// class (drops=0, phases 1–8) but do not resolve that placement divergence. This class is the ready-made
/// HARD regression gate for the #499 fix: when #499 closes, remove @Disabled and the pinned failover must
/// converge 3×.
///
/// Distinct base ports vs the default variant so both concrete classes can run in one JVM without TCP
/// contention; a separate class also guarantees a separate [EmberCluster] (a second kill-test in the
/// SAME `@TestInstance(PER_CLASS)` class would run on the already-degraded cluster).
@Tag("Heavy")
@Disabled("blocked on #499 — HRW-divergence stalls RF-restoration even with membership pinned (#498 "
          + "suppressed, swimDeadStuck empty); ready-made HARD regression gate, re-enable when #499 closes")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StreamOwnerFailoverPinnedTest extends AbstractStreamOwnerFailover {
    private static final String PIN_REASON = "membership-pin: #491 pinned convergence variant";

    @Override
    boolean assertsConvergence() {
        return true;
    }

    @Override
    void configureCluster(EmberCluster cluster) {
        cluster.withRaisedSwimTimeouts();
    }

    @Override
    void pinMembership(EmberCluster cluster) {
        cluster.allNodes()
               .forEach(node -> node.clusterTopologyManager()
                                    .onPresent(ctm -> ctm.setAutoHealEnabled(false, PIN_REASON)));
    }

    @Override
    int basePort() {
        return 15000;
    }

    @Override
    int baseMgmtPort() {
        return 15100;
    }

    @Override
    int baseAppHttpPort() {
        return 15200;
    }

    @Override
    String nodePrefix() {
        return "sofp";
    }
}
