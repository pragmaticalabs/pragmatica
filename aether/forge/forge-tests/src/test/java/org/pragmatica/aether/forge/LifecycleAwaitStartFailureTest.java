// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.ServerSocket;

import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;

/// #915 review round 2 — the pin for the ONE-ARGUMENT [LifecycleAwait#snapshot(EmberCluster)].
///
/// `LifecycleAwaitTest` supplies its inputs directly and therefore only ever drives the two-argument
/// overload. That left the accessor's own wiring unpinned: swapping `present.lastStartFailure()` for
/// [Option#none] there kept all 14 tests green, because nothing reached it with a start failure
/// actually retained. The accessor is what all 92 call sites in this module use, so the branch it
/// selects is worth a test of its own.
///
/// This is the one case in the #915 set that CANNOT be supplied directly: [EmberCluster] captures the
/// failure into a private field during `start()`, and there is no seam to set it. So the start really
/// is made to fail — one management port is bound before the cluster is built — and the assertions
/// below are about what the accessor renders afterwards.
///
/// Kept out of `LifecycleAwaitTest` deliberately: that class states that no cluster is started in it,
/// and that statement should stay true.
class LifecycleAwaitStartFailureTest {
    private static final int BASE_PORT = 27800;
    private static final int BASE_MGMT_PORT = 27840;
    private static final int BASE_APP_HTTP_PORT = 27880;
    /// The start is expected to FAIL, not to expire; this only stops a wedge from hanging the class.
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    /// `toNodeStatus` derives mgmt as `baseMgmtPort + (clusterPort - basePort)`, so holding this port
    /// is what fails the second node.
    private static final int HELD_MGMT_PORT = BASE_MGMT_PORT + 1;

    @Test
    void aFailedStart_isRenderedByTheOneArgAccessor_fromTheRetainedCapture() throws Exception {
        try (var heldByAnotherProcess = new ServerSocket(HELD_MGMT_PORT)) {
            assertThat(heldByAnotherProcess.isBound()).describedAs("the whole test rests on this port being taken before the "
                                                                  + "cluster asks for it")
                      .isTrue();

            var cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "la-sf");

            try {
                var outcome = cluster.start().await(START_BOUND);

                assertThat(outcome.isFailure()).describedAs("a management port that cannot be bound must fail the start — "
                                                           + "if it succeeded this test would pin nothing")
                          .isTrue();
                assertThat(cluster.status().nodes()).describedAs("#913's abort clears the live registry BEFORE the caller sees "
                                                                + "the failure; that emptiness is why the capture exists")
                          .isEmpty();

                var snapshot = LifecycleAwait.snapshot(cluster);

                assertThat(snapshot).describedAs("the accessor must read the retained capture — rendering NO_STATE here is "
                                                + "exactly the un-named empty dump #913 added it to prevent")
                          .isNotEqualTo(ClusterSnapshot.NO_STATE);
                assertThat(snapshot).contains("captured when the start failed");
                assertThat(snapshot).describedAs("the node that could not bind must be identifiable by its port")
                          .contains("mgmt=" + HELD_MGMT_PORT);
                assertThat(snapshot).describedAs("and the reason must be named, not left to the reader")
                          .contains("Address already in use");
            } finally {
                LifecycleAwait.bestEffort("cluster stop after the failed start", cluster, cluster.stop());
            }
        }
    }
}
