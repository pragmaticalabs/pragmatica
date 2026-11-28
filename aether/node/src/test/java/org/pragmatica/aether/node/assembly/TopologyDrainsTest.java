// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.metrics.DrainCommandRegistry;
import org.pragmatica.aether.metrics.DrainSubject;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #2315, failure input (c) at the topology manager's boundary: the manager's grace-terminate or withdrawal clears with a node id,
/// and by then the entry may be an operator's newer drain of the same node. The manager's drains are held by their ids.
class TopologyDrainsTest {
    private static final NodeId NODE = NodeId.nodeId("drained").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();

    private final AtomicLong process = new AtomicLong(1);
    private final DrainCommandRegistry registry = boundRegistry();
    private final TopologyDrains drains = TopologyDrains.topologyDrains(registry);

    private DrainCommandRegistry boundRegistry() {
        var created = DrainCommandRegistry.drainCommandRegistry();

        created.bindSubject(DrainSubject.drainSubject(_ -> process.get(), _ -> 0L));
        return created;
    }

    @Test
    void clear_endsTheDrainTheManagerSet_andReleasesItsId() {
        drains.request(NODE);
        assertThat(registry.isDrainRequested(NODE)).as("instrument check: the request reached the registry").isTrue();

        drains.clear(NODE);

        assertThat(registry.isDrainRequested(NODE)).isFalse();
        assertThat(drains.held()).isZero();
    }

    @Test
    void clear_afterAnOperatorsNewerDrainOfTheNode_leavesTheNewerEntry() {
        drains.request(NODE);
        process.set(2);
        registry.requestDrain(NODE);

        drains.clear(NODE);

        assertThat(registry.isDrainRequested(NODE)).as("the manager's id names the earlier drain").isTrue();
    }

    @Test
    void clear_ofANodeTheManagerNeverDrained_clearsNothing() {
        registry.requestDrain(OTHER);

        drains.clear(OTHER);

        assertThat(registry.isDrainRequested(OTHER)).as("an operator's drain is not the manager's to end").isTrue();
    }

    /// The node's process changed between two requests of the manager: the second is a new drain with a new id, and the manager
    /// holds THAT id. Holding the first would make the end of the attempt a clear by a stale id: refused, the entry left standing.
    @Test
    void request_afterTheNodesProcessChanged_holdsTheNewId_andClearEndsTheNewEntry() {
        drains.request(NODE);
        var first = registry.pinOf(NODE).unwrap().id();
        process.set(2);
        drains.request(NODE);
        assertThat(registry.pinOf(NODE).unwrap().id()).as("instrument check: the second request is a new drain").isNotEqualTo(first);

        drains.clear(NODE);

        assertThat(registry.isDrainRequested(NODE)).as("cleared by the held, current id: no entry is left behind").isFalse();
        assertThat(drains.held()).isZero();
    }

    @Test
    void request_forTheSameNodeTwice_holdsOneId() {
        drains.request(NODE);
        drains.request(NODE);

        assertThat(drains.held()).isEqualTo(1);
    }
}
