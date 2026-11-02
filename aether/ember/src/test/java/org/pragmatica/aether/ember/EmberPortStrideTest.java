// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ember;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;

/// The M-1 measurement harness (#2512) needs more than 64 slots and, past 100 slots, a port stride (a node's SWIM UDP port is its
/// cluster port + 100, so with consecutive slots it lands on another slot's QUIC port). This pins that both knobs are inert by default:
/// the slot cap still refuses what it refused for tests (negative, over the cap, after start), the default stride is 1 (consecutive
/// cluster ports from the base), and a stride of 3 spaces the cluster ports by 3 and keeps them clear of every SWIM port.
@PortBudget
class EmberPortStrideTest {
    private static final int CORES = 3;
    private static final int STRIDE = 3;
    private static final int SLOTS_STRIDE_1 = 2 * CORES;
    private static final int SLOTS_STRIDE_3 = 2 * CORES * STRIDE;
    private static final int MGMT_OFFSET = 60;
    private static final int APP_OFFSET = 120;
    private static final TimeSpan START_BOUND = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan STOP_BOUND = TimeSpan.timeSpan(60).seconds();

    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await(STOP_BOUND);
        }
    }

    @Test
    void slotCap_acceptsUpTo256_andStillRefusesTheRest() {
        var fresh = emberCluster(CORES);

        assertThat(fresh.withAdditionalNodeSlots(64).isSuccess()).as("64 was always allowed").isTrue();
        assertThat(fresh.withAdditionalNodeSlots(256).isSuccess()).as("256 is the new cap").isTrue();
        assertThat(fresh.withAdditionalNodeSlots(257).isSuccess()).as("one past the cap").isFalse();
        assertThat(fresh.withAdditionalNodeSlots(-1).isSuccess()).as("negative").isFalse();
        assertThat(fresh.withPortStride(0).isSuccess()).as("a stride below 1").isFalse();
        assertThat(fresh.withPortStride(3).isSuccess()).as("a stride before start").isTrue();
    }

    @Test
    @Timeout(300)
    void defaultStride_givesConsecutiveClusterPorts() {
        var block = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST, EmberTestPorts.POOL_LAST, EmberTestPorts.POOL_STEP,
                                             SLOTS_STRIDE_1, MGMT_OFFSET, APP_OFFSET);

        cluster = EmberTestPorts.startedCluster(block,
                                                base -> emberCluster(CORES, base, base + MGMT_OFFSET, base + APP_OFFSET, "strd1"),
                                                START_BOUND);

        var ports = clusterPorts();

        assertThat(ports).as("default stride is 1: the three cores take consecutive ports").hasSize(CORES);
        assertThat(ports.getLast() - ports.getFirst()).isEqualTo(CORES - 1);
    }

    @Test
    @Timeout(300)
    void stride3_spacesClusterPortsByThree_andKeepsThemClearOfSwim() {
        var block = new EmberTestPorts.Block(EmberTestPorts.POOL_FIRST, EmberTestPorts.POOL_LAST, EmberTestPorts.POOL_STEP,
                                             SLOTS_STRIDE_3, MGMT_OFFSET, APP_OFFSET);

        cluster = EmberTestPorts.startedCluster(block, base -> {
            var started = emberCluster(CORES, base, base + MGMT_OFFSET, base + APP_OFFSET, "strd3");

            assertThat(started.withPortStride(STRIDE).isSuccess()).isTrue();
            return started;
        }, START_BOUND);

        var ports = clusterPorts();
        var swim = ports.stream().map(port -> port + EmberTestPorts.SWIM_PORT_OFFSET).toList();

        assertThat(ports).hasSize(CORES);
        for (int i = 1; i < ports.size(); i++) {
            assertThat((ports.get(i) - ports.get(0)) % STRIDE).as("cluster ports are multiples of the stride apart").isZero();
        }
        assertThat(swim).as("no SWIM port lands on a cluster port").doesNotContainAnyElementsOf(ports);
    }

    private List<Integer> clusterPorts() {
        return cluster.getNodeInfos().stream().map(NodeInfo::address).map(address -> address.port()).sorted().toList();
    }
}
