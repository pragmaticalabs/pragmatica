// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// A fresh sample of the read seams queues a reconcile when any of the three sets the reconcile reads changed (C11e). One test per
/// set, each changing only that set, so that no other arm can queue the reconcile in its place.
class GapSeamsTest {
    /// Red when: a change of the counted-core set alone queues no reconcile, so a core that began to count is never used.
    @Test
    void countedCoresChange_alone_queuesAReconcile() {
        var h = harness(CORE_1, CORE_2);

        h.seams = h.seams.withCountedCores(Set.of(CORE_1));
        seedTarget(h, ORDERS, 2, 1);
        h.activate();
        assertThat(hosts(h, ORDERS)).containsExactly(CORE_1);
        h.deliver(new ClusterInput.SeamsRefreshed(h.seams.withCountedCores(Set.of(CORE_1, CORE_2))));
        assertThat(hosts(h, ORDERS)).containsExactly(CORE_1, CORE_2);
    }

    /// Red when: a change of the ready set alone queues no reconcile, so a core that became ready is never used.
    @Test
    void readyChange_alone_queuesAReconcile() {
        var h = harness(CORE_1, CORE_2);

        h.seams = h.seams.withReady(Set.of(CORE_1));
        seedTarget(h, ORDERS, 2, 1);
        h.activate();
        assertThat(hosts(h, ORDERS)).containsExactly(CORE_1);
        h.deliver(new ClusterInput.SeamsRefreshed(h.seams.withReady(Set.of(CORE_1, CORE_2))));
        assertThat(hosts(h, ORDERS)).containsExactly(CORE_1, CORE_2);
    }

    /// Red when: a change of the draining set alone queues no reconcile, so a drain announced by the seams is not started until
    /// some unrelated input arrives.
    @Test
    void drainingChange_alone_queuesAReconcile_thatStartsTheDrain() {
        var h = harness(CORE_1, CORE_2);

        seedTarget(h, ORDERS, 2, 1);
        h.activate();
        assertThat(hosts(h, ORDERS)).containsExactly(CORE_1, CORE_2);
        h.deliver(new ClusterInput.SeamsRefreshed(h.seams.withDraining(Set.of(CORE_2))));
        assertThat(h.model().drainLoops.contains(CORE_2)).isTrue();
    }
}
