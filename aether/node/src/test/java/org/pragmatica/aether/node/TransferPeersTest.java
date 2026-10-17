// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #1803: the peers a bootstrapping core initiates toward are its mint-time peers PLUS the electorate it learns
/// after joining.
class TransferPeersTest {
    private static final NodeId MINT_TIME = new NodeId("hetzner-core-0");
    private static final NodeId NEW_LEADER = new NodeId("aether-new-leader");
    private static final NodeId STRANGER = new NodeId("stranger");

    @Test
    void transferPeers_includesAVoterThatIsNotInTheMintTimeList() {
        var peers = AetherNode.transferPeers(Set.of(MINT_TIME), () -> Set.of(MINT_TIME, NEW_LEADER));

        assertThat(peers.test(NEW_LEADER)).as("the current leader, absent from the mint-time list").isTrue();
        assertThat(peers.test(MINT_TIME)).isTrue();
        assertThat(peers.test(STRANGER)).as("neither configured nor a voter").isFalse();
    }

    @Test
    void transferPeers_followsTheElectorateAsItChanges() {
        var electorate = new AtomicReference<Set<NodeId>>(Set.of());
        var peers = AetherNode.transferPeers(Set.of(MINT_TIME), electorate::get);

        assertThat(peers.test(NEW_LEADER)).as("before the electorate is installed").isFalse();

        electorate.set(Set.of(MINT_TIME, NEW_LEADER));

        assertThat(peers.test(NEW_LEADER)).as("read at call time, not captured at construction").isTrue();
    }
}
