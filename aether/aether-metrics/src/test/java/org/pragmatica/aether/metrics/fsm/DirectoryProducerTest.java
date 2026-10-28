// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.fsm;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.metrics.ClusterDirectory.DhtReplication;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;

/// The leader's directory producer: the generation moves only when the content changes, restarts at 1 under a new epoch, and a DHT replication
/// that cannot be derived keeps the previous directory.
class DirectoryProducerTest {
    private static final DhtReplication DHT = new DhtReplication(3, 2, 3, 2, 1L, -1L, 0, 0, true);
    private static final Epoch EPOCH = Epoch.epoch(1L, 5L, 0L);

    private static List<NodeInfo> nodes(String... ids) {
        return java.util.Arrays.stream(ids)
                               .map(id -> NodeInfo.nodeInfo(NodeId.nodeId(id).unwrap(), new NodeAddress("localhost", 7000)))
                               .toList();
    }

    /// Mutation: increment on every call (the unchanged content is re-sent every tick), or never increment (a change is not seen).
    @Test
    void generation_movesOnlyWhenTheContentChanges() {
        var content = new AtomicReference<>(nodes("a"));
        var producer = new DirectoryProducer(content::get, () -> Result.success(DHT));

        assertThat(producer.produce(EPOCH).unwrap().generation()).isEqualTo(1);
        assertThat(producer.produce(EPOCH).unwrap().generation()).as("same content, same epoch: same directory").isEqualTo(1);

        content.set(nodes("a", "b"));

        assertThat(producer.produce(EPOCH).unwrap().generation()).isEqualTo(2);

        assertThat(producer.produce(EPOCH).unwrap().generation()).isEqualTo(2);
    }

    /// A change of the DHT replication alone is a change. Mutation: compare the nodes only.
    @Test
    void generation_movesWhenOnlyTheDhtReplicationChanges() {
        var replication = new AtomicReference<>(DHT);
        var producer = new DirectoryProducer(() -> nodes("a"), () -> Result.success(replication.get()));

        producer.produce(EPOCH);
        replication.set(new DhtReplication(5, 3, 3, 2, 2L, -1L, 0, 0, true));

        assertThat(producer.produce(EPOCH).unwrap().generation()).isEqualTo(2);
    }

    /// C-2. A new epoch (a new leader, or a counter bump) restarts at generation 1: the epoch triple orders first. Mutation: carry the
    /// generation across epochs.
    @Test
    void aNewEpoch_restartsAtGenerationOne_underTheNewTriple() {
        var producer = new DirectoryProducer(() -> nodes("a"), () -> Result.success(DHT));

        producer.produce(EPOCH);
        producer.produce(EPOCH.nextCounter());

        var next = producer.produce(Epoch.epoch(1L, 6L, 0L)).unwrap();

        assertThat(next.generation()).isEqualTo(1);
        assertThat(next.version().isStrictlyAfter(producer.produce(Epoch.epoch(1L, 6L, 0L)).unwrap().version())).as("an unchanged directory keeps its version").isFalse();
        assertThat(next.epochTerm()).isEqualTo(6L);
    }

    /// A DHT replication that cannot be derived keeps the previous directory, and offers none before there is one. Mutation: produce a
    /// directory with a default replication on failure.
    @Test
    void aDhtReplicationThatCannotBeDerived_keepsThePreviousDirectory() {
        var failing = new AtomicReference<>(false);
        var producer = new DirectoryProducer(() -> nodes("a"),
                                             () -> failing.get()
                                                   ? Causes.cause("invalid configuration").<DhtReplication> result()
                                                   : Result.success(DHT));

        failing.set(true);

        assertThat(producer.produce(EPOCH).isPresent()).as("nothing to offer yet").isFalse();

        failing.set(false);
        var first = producer.produce(EPOCH).unwrap();

        failing.set(true);

        assertThat(producer.produce(EPOCH).unwrap()).isSameAs(first);
    }
}
