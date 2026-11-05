// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// Survivor pins for [Indexer] (F1 closure, lane fsmf1): each test names the line whose mutation it holds.
class IndexerSurvivorPinsTest {
    private static ClusterModel model() {
        return MbWorld.world(CORE_1).activate().h.model();
    }

    /// Red when: a directive with no community is indexed under the empty community, or a departed node's community directive is indexed
    /// (`&&` weakened to `||`).
    @Test
    void indexDirective_indexesOnlyACommunityDirectiveOfALiveNode() {
        var m = model();
        var core = node("x-core");
        var worker = node("x-worker");
        var gone = node("x-gone");

        m.departed = m.departed.add(gone);
        Indexer.indexDirective(m, core, ActivationDirectiveValue.core());
        Indexer.indexDirective(m, worker, worker("comm-1"));
        Indexer.indexDirective(m, gone, worker("comm-1"));

        assertThat(Idx.members(m.assignedByCommunity, "").toList()).as("no community: not indexed").isEmpty();
        assertThat(Idx.members(m.assignedByCommunity, "comm-1").toList()).as("only the live worker").containsExactly(worker);
    }
}
