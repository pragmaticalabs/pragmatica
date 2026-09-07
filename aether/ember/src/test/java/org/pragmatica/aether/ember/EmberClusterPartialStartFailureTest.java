// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.io.IOException;
import java.net.ServerSocket;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;

/// #727 — `EmberCluster.start()` must SETTLE when a partial start leaves no quorum to wait for.
///
/// A node whose start fails settles its promise at once; a node whose start succeeds settles only on
/// consensus quorum. With two of three management ports already taken, the two bind failures settled
/// and the third node's start waited for a quorum of one that could never form — so `start()` never
/// settled, and the caller's untimed `await()` in a forge `@BeforeAll` sat past JUnit's 8-minute
/// interrupt (`PromiseImpl.await` re-parks until resolved) until failsafe's 30-minute fork wall, with
/// no test named in the report. Observed 2026-09-06 on a quiet box, by accident: two runs of the same
/// forge class split one port range between them.
///
/// The pin is on the CAUSE, not merely on "it failed": a bounded `await` returns a `Timeout` failure
/// too, and that is exactly the old behaviour. Reverting `EmberCluster.abortStart` turns this red
/// with a 60-second `Timeout` cause instead of the bind failure.
class EmberClusterPartialStartFailureTest {
    private static final int BASE_PORT = 25600;
    private static final int BASE_MGMT_PORT = 25640;
    private static final int BASE_APP_HTTP_PORT = 25680;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(30).seconds();

    private EmberCluster cluster;

    /// #727 review S3 — the `Result` used to be discarded, so a stop that exhausted the bound passed
    /// silently: a bounded await whose expiry names nothing is the same blind wait this ticket exists
    /// to remove. It doubles as the pin for review N4: by the time this runs, `abortStart` has already
    /// stopped every node, so a green assertion here is evidence that the second stop is idempotent
    /// rather than a docstring claiming it is.
    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).isSuccess())
                .describedAs("stopping an already-aborted cluster must complete within %s", STOP_BOUND)
                .isTrue();
        }
    }

    @Test
    @Timeout(150)
    void start_settlesWithTheBindFailure_whenTwoOfThreeNodesCannotBindTheirManagementPort() throws IOException {
        // Slots are assigned in node order: node 1 -> BASE_MGMT_PORT, node 2 -> +1, node 3 -> +2.
        try (var taken1 = new ServerSocket(BASE_MGMT_PORT); var taken2 = new ServerSocket(BASE_MGMT_PORT + 1)) {
            cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "partial");
            var outcome = cluster.start().await(START_BOUND).fold(Cause::message, _ -> "started");

            assertThat(outcome).contains("Address already in use");
            assertStartFailureSnapshotSurvivedTheAbort();
        }
        // The survivor was stopped as part of the abort: its management port is reclaimable.
        try (var reclaimed = new ServerSocket(BASE_MGMT_PORT + 2)) {
            assertThat(reclaimed.isBound()).isTrue();
        }
    }

    /// #727 review B2 — the abort clears `nodes`, `nodeInfos` and the rest BEFORE the failure reaches
    /// the caller, so a caller that reports `status()` on a start failure reads an emptied registry:
    /// the state dump added for this very failure was `leader=none` with zero node lines, on exactly
    /// the failures it exists for. [EmberCluster#lastStartFailure] is captured before the stops begin.
    ///
    /// Red-before is a hunk revert, not a rewrite: move `captureStartFailure(startFailures)` in
    /// `EmberCluster.abortStart` from above the stop list to below `clearClusterStateOnFailure`, and
    /// the node assertions here fail on an empty snapshot.
    ///
    /// #727 review B1 — the same snapshot is where a fabricated state does the most damage, so it is
    /// asserted here too: not one node of a cluster that never formed may report itself active. With
    /// the old hardcoded `"healthy"` literal all three did.
    private void assertStartFailureSnapshotSurvivedTheAbort() {
        assertThat(cluster.status().nodes())
            .describedAs("the abort clears the live registry; this is the condition the retained "
                         + "snapshot exists for, and it must hold or the test proves nothing")
            .isEmpty();

        var snapshot = cluster.lastStartFailure()
                              .or(() -> {
                                  throw new AssertionError("no start-failure snapshot was retained; the state "
                                                           + "dump for this failure would be empty");
                              });

        assertThat(snapshot.status().nodes())
            .describedAs("the snapshot must carry the nodes the failed start had created")
            .hasSize(3);
        assertThat(snapshot.status().nodes())
            .describedAs("no node of a cluster that never formed may report itself as active")
            .noneMatch(node -> EmberCluster.STATE_ACTIVE.equals(node.state()));
        assertThat(snapshot.nodeFailures())
            .describedAs("the snapshot must name the nodes whose start failed, and why")
            .isNotEmpty();
        assertThat(snapshot.nodeFailures().values())
            .allSatisfy(message -> assertThat(message).contains("Address already in use"));
    }
}
