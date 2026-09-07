// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #727 review B1 — the POSITIVE control for [EmberCluster#status]'s node state.
///
/// `toNodeStatus` used to pass the string literal `"healthy"` into every `NodeStatus`, so every status
/// answer, every `/api/nodes/status` body and every failing test's state dump reported three healthy
/// nodes however broken the cluster was. It is now read from the node
/// (`AetherNode.isReady()` — consensus-active).
///
/// This class is one half of the pin and cannot stand alone. It shows that a cluster that really did
/// form reports [EmberCluster#STATE_ACTIVE], which rules out a replacement that always answers
/// "inactive" — a fabrication in the opposite direction, and one that a broken-cluster test alone
/// would never catch. The other half is `EmberClusterPartialStartFailureTest`, which shows a cluster
/// that did NOT form reports no active node at all. Reverting `observedState` to the old literal turns
/// that one red; replacing it with a constant `inactive` turns this one red. Neither test alone closes
/// the defect, which is exactly why the stream's healthy-cluster-only probes could not.
class EmberClusterObservedNodeStateTest {
    private static final int BASE_PORT = 25700;
    private static final int BASE_MGMT_PORT = 25740;
    private static final int BASE_APP_HTTP_PORT = 25780;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            assertThat(cluster.stop().await(STOP_BOUND).isSuccess())
                .describedAs("cluster stop must complete within %s", STOP_BOUND)
                .isTrue();
        }
    }

    @Test
    @Timeout(240)
    void everyNodeOfAFormedCluster_reportsItsObservedStateAsActive() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "obs");

        assertThat(cluster.start().await(START_BOUND).isSuccess())
            .describedAs("a three-node cluster on free ports must form within %s", START_BOUND)
            .isTrue();

        var nodes = cluster.status().nodes();

        assertThat(nodes).hasSize(3);
        assertThat(nodes).allSatisfy(node -> assertThat(node.state())
            .describedAs("node %s of a formed cluster", node.id())
            .isEqualTo(EmberCluster.STATE_ACTIVE));
        assertThat(cluster.lastStartFailure().isEmpty())
            .describedAs("a start that succeeded must leave no start-failure snapshot behind")
            .isTrue();
    }
}
