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
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// #2981 step 6 on a REAL in-JVM cluster: the wire-generation join guard is installed on every assembled node, and the label it reads
/// is stamped by the consensus layer, not by `Main`.
///
/// Three initial nodes boot advertising generation 1 (`EmberCluster.nodeWireGeneration`, the seam an older binary stands for) and form
/// a cluster: a cold start of one generation is never refused. A node added afterwards advertises nothing, so the consensus layer stamps
/// this binary's generation 2 into its Hello; it sees core members at generation 1 and refuses to join, naming one of them.
@PortBudget
class EmberWireGenerationJoinGuardTest {
    private static final int MGMT_OFFSET = 40;
    private static final int APP_HTTP_OFFSET = 80;
    private static final String PREFIX = "wg";
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan JOIN_BOUND = TimeSpan.timeSpan(90).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    @Timeout(600)
    void aLaterNodeOfANewerGeneration_refusesToJoinAnOlderCluster_namingTheMember() {
        cluster = EmberTestPorts.startedCluster(new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST,
                                                                          EmberTestPorts.POOL_LAST,
                                                                          EmberTestPorts.POOL_STEP,
                                                                          2 * 3 + 4,
                                                                          MGMT_OFFSET,
                                                                          APP_HTTP_OFFSET),
                                                basePort -> emberCluster(3,
                                                                         basePort,
                                                                         basePort + MGMT_OFFSET,
                                                                         basePort + APP_HTTP_OFFSET,
                                                                         PREFIX).nodeWireGeneration("1"),
                                                START_BOUND);

        var joined = cluster.nodeWireGeneration("").addNode().await(JOIN_BOUND);

        joined.onSuccess(id -> fail("node " + id + " joined a generation-1 cluster"))
              .onFailure(cause -> assertThat(cause.message()).as("the refusal names a generation-1 member")
                                                             .matches("(?s).*Refusing to join: member " + PREFIX
                                                                      + "-\\d+ runs wire generation 1, this node runs 2.*"));
    }
}
