// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.config.PlacementPolicy;
import org.pragmatica.consensus.NodeId;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Wave 2 / W6 (cluster-topology-overhaul spec): `CORE_ONLY` placement is structurally
/// impossible on a worker. `coreNodes` is built from the CDM's core-scoped `activeNodes()`
/// (fed by `MembershipFsm.coreCountedMembers()` — descriptor-role-based), so a worker only ever
/// enters `mainWorkers`; these tests pin the pool's policy contract on top of that.
class AllocationPoolTest {
    private static final NodeId CORE_1 = new NodeId("node-core-1");
    private static final NodeId CORE_2 = new NodeId("node-core-2");
    private static final NodeId WORKER_1 = new NodeId("node-worker-1");

    @Test
    void nodesForPolicy_coreOnly_neverIncludesWorkers() {
        var pool = AllocationPool.allocationPool(List.of(CORE_1, CORE_2), List.of(WORKER_1));

        assertThat(pool.nodesForPolicy(PlacementPolicy.CORE_ONLY)).containsExactly(CORE_1, CORE_2);
        assertThat(pool.nodesForPolicy(PlacementPolicy.CORE_ONLY)).doesNotContain(WORKER_1);
    }

    @Test
    void nodesForPolicy_workersPreferred_targetsWorkersWhenPresent() {
        var pool = AllocationPool.allocationPool(List.of(CORE_1, CORE_2), List.of(WORKER_1));

        assertThat(pool.nodesForPolicy(PlacementPolicy.WORKERS_PREFERRED)).containsExactly(WORKER_1);
    }

    @Test
    void nodesForPolicy_workersPreferred_fallsBackToCoresWhenNoWorkers() {
        var pool = AllocationPool.coreOnly(List.of(CORE_1, CORE_2));

        assertThat(pool.nodesForPolicy(PlacementPolicy.WORKERS_PREFERRED)).containsExactly(CORE_1, CORE_2);
    }

    @Test
    void nodesForPolicy_all_unionsCoresAndWorkers() {
        var pool = AllocationPool.allocationPool(List.of(CORE_1, CORE_2), List.of(WORKER_1));

        assertThat(pool.nodesForPolicy(PlacementPolicy.ALL)).containsExactly(CORE_1, CORE_2, WORKER_1);
    }
}
