// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;

class CommunityReachabilityTest {
    @Test
    void leadership_unknownPeer_becomesAbsentAfterGrace() {
        var clock = new AtomicLong(100);
        var view = CommunityReachability.communityReachability(TimeSpan.timeSpan(20).nanos(), _ -> Option.none(), clock::get);
        var peer = new NodeId("unknown");
        assertThat(view.isAbsent(peer)).isFalse();
        clock.set(120);
        assertThat(view.isAbsent(peer)).isTrue();
        view.beginLeadership();
        assertThat(view.isAbsent(peer)).isFalse();
        clock.set(140);
        assertThat(view.isAbsent(peer)).isTrue();
    }

    @Test
    void leadership_knownStalePeer_doesNotGainFreshness() {
        var clock = new AtomicLong();
        var age = new AtomicLong(30);
        var view = CommunityReachability.communityReachability(TimeSpan.timeSpan(20).nanos(), _ -> Option.some(TimeSpan.timeSpan(age.get()).nanos()), clock::get);
        var peer = new NodeId("peer");
        view.beginLeadership();
        assertThat(view.isAbsent(peer)).isTrue();
        age.set(0);
        assertThat(view.isAbsent(peer)).isFalse();
    }
}
