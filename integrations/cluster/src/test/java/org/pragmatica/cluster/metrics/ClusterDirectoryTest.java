// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.metrics.ClusterDirectory.DhtReplication;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// The directory's content is what a worker installs; its version only orders it. The leader's electorate is content (#2543).
class ClusterDirectoryTest {
    private static final DhtReplication DHT = new DhtReplication(3, 2, 3, 2, 1L, -1L, 0, 0, true);

    private static Option<VoterConfiguration> voters(long epoch, String... ids) {
        return Option.some(VoterConfiguration.voterConfiguration(epoch,
                                                                 java.util.Arrays.stream(ids).map(id -> NodeId.nodeId(id).unwrap()).toList())
                                             .unwrap());
    }

    private static ClusterDirectory directory(long generation, Option<VoterConfiguration> voters) {
        return new ClusterDirectory(generation, 1L, 5L, 0L, List.of(), DHT, voters);
    }

    @Test
    void sameContentAs_ignoresTheVersion() {
        assertThat(directory(1L, voters(1, "a", "b", "c")).sameContentAs(directory(9L, voters(1, "a", "b", "c")))).isTrue();
    }

    /// Mutation: leave `voters` out of the comparison.
    @Test
    void sameContentAs_isFalse_whenOnlyTheElectorateDiffers() {
        assertThat(directory(1L, voters(1, "a", "b", "c")).sameContentAs(directory(1L, voters(2, "a", "b", "d")))).isFalse();
        assertThat(directory(1L, voters(1, "a", "b", "c")).sameContentAs(directory(1L, Option.none()))).isFalse();
    }
}
