// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReplicationContext;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;

/// #1564: the committed cluster config read as replication state, the one conversion deploy and activation share.
class ClusterReplicationTest {
    @Test
    void context_noCommittedConfig_isTheBuiltInDefaultWithNoCoreCount() {
        assertThat(ClusterReplication.context(Option.none()).unwrap()).isEqualTo(ReplicationContext.BUILT_IN);
    }

    @Test
    void context_committedConfig_readsTheReplicationSectionAndTheDesiredCoreCount() {
        var committed = committed("""
                                  [replication]
                                  replication_factor = 5
                                  confirmation_factor = 3
                                  """, 5);

        assertThat(ClusterReplication.context(Option.some(committed)).unwrap())
            .isEqualTo(ReplicationContext.replicationContext(new ReplicationFactors(5, 3), 5));
    }

    @Test
    void context_seedConfigWithBlankToml_isTheBuiltInDefaultWithTheCoreCount() {
        assertThat(ClusterReplication.context(Option.some(committed("", 3))).unwrap())
            .isEqualTo(ReplicationContext.replicationContext(ReplicationFactors.BUILT_IN, 3));
    }

    /// Cluster events keep CF 1 until the owner decides the acked-but-lost question; RF is the desired core count.
    @Test
    void clusterEventsFactors_default_isConfirmationOneOverTheCoreCount() {
        assertThat(ClusterReplication.clusterEventsFactors(Option.some(committed("", 5))).unwrap())
            .isEqualTo(new ReplicationFactors(5, 1));
        assertThat(ClusterReplication.clusterEventsFactors(Option.none()).unwrap())
            .isEqualTo(new ReplicationFactors(3, 1));
    }

    @Test
    void clusterEventsFactors_declaredConfirmation_isRead() {
        var committed = committed("""
                                  [replication.cluster_events]
                                  confirmation_factor = 2
                                  """, 5);

        assertThat(ClusterReplication.clusterEventsFactors(Option.some(committed)).unwrap()).isEqualTo(new ReplicationFactors(5, 2));
    }

    private static ClusterConfigValue committed(String toml, int cores) {
        return ClusterConfigValue.clusterConfigValue(toml,
                                                     "c1",
                                                     "1.0.0",
                                                     List.of(TopologyEntry.topologyEntry("local", TopologyEntry.CORE_ROLE, cores)),
                                                     cores,
                                                     cores,
                                                     "embedded",
                                                     1L);
    }
}
