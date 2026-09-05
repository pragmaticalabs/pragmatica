// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

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
/// ACTIVE #499 regression gate (closed 2026-07-23, converged 3×). The long-standing phase-9
/// non-convergence was NOT the "HRW re-resolves to an empty node that cannot catch up" mechanism this
/// class originally described — instrumented reproduces showed that loop belonged to the KILLED node's
/// zombie backfill executor (periodic SharedScheduler tasks were never cancelled on `node.stop()`,
/// #501) logging into the shared forge console, while the live cluster replicated correctly. The real
/// product defect was a FIRE-ONCE backfill-completion ack: a fresh replacement promotes CAUGHT_UP
/// milliseconds after its transport Hello, before its member view populates, so the one-shot #336
/// `ReplicateAck` resolved no owner and was silently skipped — on a write-idle partition nothing ever
/// re-sent it, freezing the owner's replicas-view at SYNCING@-1 over fully-replicated data. Fixed by
/// (a) cancelling node-scoped periodic tasks in `AetherNode.stop()` and (b) re-sending the idempotent
/// completion ack from the reverify no-op arm (`PartitionBackfill#reverifyNoOp`). This gate now proves
/// owner-kill → lossless reads (phases 1–8) AND RF-restoration convergence (phase 9) on every run.
///
/// Distinct base ports vs the default variant so both concrete classes can run in one JVM without TCP
/// contention; a separate class also guarantees a separate [EmberCluster] (a second kill-test in the
/// SAME `@TestInstance(PER_CLASS)` class would run on the already-degraded cluster).
@Tag("Heavy")
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
        // #685 review round 1 SHOULD-FIX 3 — `setAutoHealEnabled` writes through consensus KV and can
        // fail; discarding its Promise (as this call used to) makes that failure invisible and lets the
        // test proceed as if every node were pinned when one might not be. Await it, bounded, and fail
        // the test on failure — a pin that silently does not pin is worse than no pin.
        cluster.allNodes()
               .forEach(node -> node.clusterTopologyManager()
                                    .onPresent(ctm -> ctm.setAutoHealEnabled(false, PIN_REASON)
                                                         .await()
                                                         .onFailure(cause -> {
                                                             throw new AssertionError("auto-heal pin failed: " + cause.message());
                                                         })));
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
