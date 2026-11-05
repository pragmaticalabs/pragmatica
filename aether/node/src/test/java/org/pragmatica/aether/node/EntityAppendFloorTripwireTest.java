// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.resource.entity.EntityLogError;
import org.pragmatica.aether.resource.entity.EntityLogSubstrate;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.streamEntityLogSubstrate;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.replication.ReplicationManager.replicationManager;

/// Streams audit A-03, variant 5 (#2201): the entity path has no pre-append replica floor. `StreamEntityLogSubstrate#append` calls the
/// four-argument `publishLocal` (`NO_REPLICA_FLOOR`) and checks the barrier only AFTER the append. With the committed ISR already shrunk
/// below `CF - 1`, the record is appended and then reported `ReplicationBarrierUnmet`; the owner's write-path state applies it (B-09).
///
/// Real `StreamEntityLogSubstrate`, real `StreamPartitionManager`, real `DefaultReplicationManager`; the committed ISR is the only fixture.
/// TRIPWIRE (enabled, GREEN) beside the REAL assertion (`@Disabled`, RED today).
class EntityAppendFloorTripwireTest {
    private static final NodeId SELF = new NodeId("owner-self");
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 0;
    private static final String ENABLE = "A-03 tripwire (entity path): an entity append now has a pre-append floor; delete this tripwire and enable the real assertion beside it (#2201)";

    private StreamPartitionManager manager;
    private EntityLogSubstrate substrate;
    private String arc;

    @BeforeEach
    void setUp() {
        var replication = replicationManager(SELF, ReplicaRegistry.replicaRegistry());

        replication.inSyncReplicaSource((_, _) -> Option.some(List.of(SELF)));
        manager = streamPartitionManager(64L * 1024 * 1024, EvictionListener.NOOP, replication);
        substrate = streamEntityLogSubstrate(manager,
                                             (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, false),
                                             null,
                                             null,
                                             EvictionListener.NOOP,
                                             null,
                                             null,
                                             null);
        substrate.ensureLog(KEYSPACE, 1, new ReplicationFactors(3, 2)).unwrap();
        arc = EntityPartitionArc.arcName(KEYSPACE);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void append_withTheIsrShrunkBelowTheFloor_appendsThenReportsBarrierUnmet_tripwire() {
        var outcome = substrate.append(KEYSPACE, PARTITION, new byte[] {1, 2, 3}).await();

        assertThat(outcome.isFailure()).as("control: the barrier is unsatisfiable").isTrue();
        outcome.onFailure(cause -> assertThat(cause).as(ENABLE).isInstanceOf(EntityLogError.ReplicationBarrierUnmet.class));
        assertThat(manager.nextExpectedOffset(arc, PARTITION)).as(ENABLE).isEqualTo(1L);
    }

    @Disabled("A-03 real assertion (entity path), RED today: an entity append the ISR cannot confirm must be refused before the append (#2201)")
    @Test
    void append_withTheIsrShrunkBelowTheFloor_isRefusedBeforeTheAppend() {
        substrate.append(KEYSPACE, PARTITION, new byte[] {1, 2, 3}).await();

        assertThat(manager.nextExpectedOffset(arc, PARTITION)).isZero();
    }
}
