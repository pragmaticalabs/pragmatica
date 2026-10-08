// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.api.AlertManager;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #2014: the leader's broadcast drain set must reach an observer that never saw `DrainRequested`, and it
/// marks the commanded nodes only. The mark is what lets the DEAD edge on a FOLLOWER read a planned
/// departure as one; the mark is held by the observer, so it survives a later leader change (the new
/// leader's pings carry an empty set and the observer is not invoked for it).
class AetherNodeDrainSetObserverTest {
    private static final NodeId SELF = NodeId.nodeId("observer").unwrap();
    private static final NodeId DRAINED = NodeId.nodeId("drained").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other").unwrap();

    @SuppressWarnings("unchecked")
    private static AlertManager alerts() {
        return AlertManager.readOnly((KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    @Test
    void drainSet_marksTheCommandedNodeOnAnObserverThatNeverSawDrainRequested() {
        var alerts = alerts();

        AetherNode.drainSetObserver(alerts, SELF).accept(Set.of(DRAINED));

        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
        assertThat(alerts.hasAnnouncedDeparture(OTHER)).as("an uncommanded node stays unmarked: an unplanned kill must alert").isFalse();
    }

    @Test
    void drainSet_neverMarksSelf() {
        var alerts = alerts();

        AetherNode.drainSetObserver(alerts, SELF).accept(Set.of(SELF, DRAINED));

        assertThat(alerts.hasAnnouncedDeparture(SELF)).isFalse();
        assertThat(alerts.hasAnnouncedDeparture(DRAINED)).isTrue();
    }
}
