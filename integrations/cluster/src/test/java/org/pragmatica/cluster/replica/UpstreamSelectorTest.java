// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// I-W8, cores only: the single place that picks a source. Each test names the mutation of `UpstreamSelector.select` that turns it red.
class UpstreamSelectorTest {
    private static final NodeId A = NodeId.nodeId("core-a").unwrap();
    private static final NodeId B = NodeId.nodeId("core-b").unwrap();
    private static final NodeId OUTSIDER = NodeId.nodeId("core-x").unwrap();
    private static final NodeId GOVERNOR = NodeId.nodeId("worker-gov").unwrap();
    private static final List<NodeId> UPLINKS = List.of(A, B);

    @Test
    void select_withoutACurrent_takesTheFirstUplink() {
        assertThat(UpstreamSelector.select(UPLINKS, Option.none(), Option.none(), Set.of())).isEqualTo(Option.some(A));
    }

    /// Mutation: always take the first unavoided uplink (flapping between sources).
    @Test
    void select_keepsTheCurrentWhileItIsNotAvoided() {
        assertThat(UpstreamSelector.select(UPLINKS, Option.none(), Option.some(B), Set.of())).isEqualTo(Option.some(B));
    }

    /// Mutation: ignore `avoided` for the current source.
    @Test
    void select_leavesACurrentThatIsAvoided() {
        assertThat(UpstreamSelector.select(UPLINKS, Option.none(), Option.some(A), Set.of(A))).isEqualTo(Option.some(B));
    }

    /// Mutation: keep a current source that is no longer an uplink.
    @Test
    void select_neverKeepsASourceOutsideTheUplinks() {
        assertThat(UpstreamSelector.select(UPLINKS, Option.none(), Option.some(OUTSIDER), Set.of())).isEqualTo(Option.some(A));
    }

    @Test
    void select_isEmpty_whenEveryUplinkIsAvoided() {
        assertThat(UpstreamSelector.select(UPLINKS, Option.none(), Option.some(A), Set.of(A, B)).isPresent()).isFalse();
    }

    /// Mutation: ignore the governor argument.
    @Test
    void select_prefersAGovernor_overTheCurrentCore() {
        assertThat(UpstreamSelector.select(UPLINKS, Option.some(GOVERNOR), Option.some(B), Set.of())).isEqualTo(Option.some(GOVERNOR));
        assertThat(UpstreamSelector.select(UPLINKS, Option.some(GOVERNOR), Option.none(), Set.of())).isEqualTo(Option.some(GOVERNOR));
    }

    /// I-W8: the silence switch is this same call. Mutation: ignore `avoided` for the governor.
    @Test
    void select_fallsThroughToACore_whenTheGovernorIsAvoided() {
        assertThat(UpstreamSelector.select(UPLINKS, Option.some(GOVERNOR), Option.some(GOVERNOR), Set.of(GOVERNOR))).isEqualTo(Option.some(A));
        assertThat(UpstreamSelector.select(UPLINKS, Option.some(GOVERNOR), Option.some(B), Set.of(GOVERNOR))).as("the current core is kept").isEqualTo(Option.some(B));
    }

    /// Mutation: keep a governor the node no longer has (current outside the uplinks and no governor argument).
    @Test
    void select_leavesAGovernorThatIsGone() {
        assertThat(UpstreamSelector.select(UPLINKS, Option.none(), Option.some(GOVERNOR), Set.of())).isEqualTo(Option.some(A));
    }

    @Test
    void select_isEmpty_whenTheGovernorAndEveryUplinkAreAvoided() {
        assertThat(UpstreamSelector.select(UPLINKS, Option.some(GOVERNOR), Option.none(), Set.of(GOVERNOR, A, B)).isPresent()).isFalse();
    }
}
