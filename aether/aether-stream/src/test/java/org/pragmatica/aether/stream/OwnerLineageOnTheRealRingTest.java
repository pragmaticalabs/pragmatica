// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1873 round 2 (v1873 C1, C4): the owner's lineage commit over the REAL ring and the REAL gate, not a hand-fed incarnation.
/// `OwnerActivationLineageTest.aRebuiltRing_bumpsTheEpoch_evenInTheSameProcess` calls `activate()` by hand, so it already
/// assumes the gate re-runs after a rebuild; here the only thing that can make it re-run is `isActivated` seeing the new ring.
class OwnerLineageOnTheRealRingTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = new NodeId("self");
    private static final Epoch E1 = Epoch.epoch(1L, 2L, 3L);

    private final AtomicReference<Option<StreamPartitionOwnershipValue>> record = new AtomicReference<>(Option.none());
    private final AtomicReference<ReplicaSetController.Role> role = new AtomicReference<>(ReplicaSetController.Role.OWNER);
    private final List<String> commits = new ArrayList<>();
    private StreamPartitionManager manager;
    private OwnerActivation activation;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(Long.MAX_VALUE);
        manager.placementRoleSupplier((_, _) -> role.get());
        manager.ownershipRecords((_, _) -> record.get());
        activation = OwnerActivation.ownerActivation(SELF,
                                                     (_, _) -> record.get(),
                                                     (_, _) -> true,
                                                     Option.none(),
                                                     () -> List.of(SELF),
                                                     (_, _, _) -> Promise.success(-1L),
                                                     (stream, partition) -> head(),
                                                     (_, _, _, _) -> Promise.success(0L),
                                                     () -> true,
                                                     (_, _, _, _, _) -> Promise.success(List.of()),
                                                     _ -> Unit.unit(),
                                                     TimeSpan.timeSpan(1).hours(),
                                                     (stream, partition) -> manager.ringIncarnation(stream, partition).or(-1L),
                                                     this::commit);
        manager.ownerServeGate(activation::admit);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /// C1: destroy + create in ONE process leaves the committed record (and the activation memory) untouched, with a fresh ring
    /// whose offsets restart. Before round 2 `isActivated` compared only the record, so the owner stayed "activated", no
    /// re-activation ran, no bump was committed, and a consumer of the first life kept reading the second one as its own.
    @Test
    void destroyAndRecreateInOneProcess_isNotActivated_untilTheGateBumpsTheEpoch() {
        record.set(Option.some(committed(List.of())));
        assertThat(manager.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();
        assertThat(activation.activate(STREAM, PARTITION).await().isSuccess()).isTrue();
        assertThat(commits).containsExactly("start@0");
        publish("old", 3);
        assertThat(manager.readServing(STREAM, PARTITION, 0L, 10, E1).isSuccess()).as("control: the first life is served").isTrue();

        assertThat(manager.destroyStream(STREAM).isSuccess()).isTrue();
        assertThat(manager.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();

        assertThat(record.get().unwrap().ownerEpoch()).as("the record outlived the stream, unchanged").isEqualTo(E1);
        assertThat(activation.isActivated(STREAM, PARTITION)).as("a rebuilt ring is not the activated one").isFalse();

        // A read drives the activation (the fixture note: publish only AFTER it, or the start is head+1 of an unread ring).
        manager.readServing(STREAM, PARTITION, 0L, 10, E1);

        assertThat(commits).as("the second life committed its own epoch").containsExactly("start@0", "restart@0");
        assertThat(activation.isActivated(STREAM, PARTITION)).isTrue();

        publish("new", 2);

        var consumerOfTheFirstLife = manager.readServing(STREAM, PARTITION, 3L, 10, E1);

        assertThat(consumerOfTheFirstLife.isFailure()).as("the first life's consumer is told, not served").isTrue();
        consumerOfTheFirstLife.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class,
                                                                                         diverged -> assertThat(diverged.resumeAt()).isZero()));
    }

    /// C4: a read of an owner whose ring does not exist yet must not commit an epoch start. The start is `head + 1` of a ring,
    /// and with none it would be offset 0 of a ring that the safety valve later seeds ABOVE the sealed floor.
    @Test
    void readBeforeTheRingExists_commitsNoEpochStart_andIsRefusedWithoutActivating() {
        role.set(ReplicaSetController.Role.NONE);
        record.set(Option.some(committed(List.of())));
        assertThat(manager.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();
        assertThat(manager.ringIncarnation(STREAM, PARTITION)).as("control: no ring is built here").isEqualTo(Option.none());

        var read = manager.readServing(STREAM, PARTITION, 0L, 10, E1);

        assertThat(read.isFailure()).isTrue();
        assertThat(commits).as("no start was committed for a ring that does not exist").isEmpty();
        assertThat(record.get().unwrap().lastEpochStart().isPresent()).isFalse();
        assertThat(activation.isActivated(STREAM, PARTITION)).isFalse();
        assertThat(activation.activate(STREAM, PARTITION).await().isFailure()).isTrue();
        assertThat(commits).isEmpty();
    }

    /// C4's open risk, measured: an owner whose ring is not built yet (the config Put has not been reconciled) is written to.
    /// The write path's safety valve builds the ring only AFTER admission, and admission now refuses without a ring, so unless
    /// admission builds it the write is refused until the reconcile tick does. It must not wait for one.
    @Test
    void firstWriteToAnOwnerWithoutARing_buildsTheRing_andIsActivatedWithoutWaitingForReconcile() {
        role.set(ReplicaSetController.Role.NONE);
        record.set(Option.some(committed(List.of())));
        assertThat(manager.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();
        role.set(ReplicaSetController.Role.OWNER);

        var first = manager.publishLocal(STREAM, PARTITION, "first".getBytes(StandardCharsets.UTF_8), 1L);

        var second = manager.publishLocal(STREAM, PARTITION, "second".getBytes(StandardCharsets.UTF_8), 2L);

        assertThat(second.isSuccess()).as("first=%s second=%s: by the second write the gate has activated the ring the first one built", first, second).isTrue();
        assertThat(manager.ringIncarnation(STREAM, PARTITION).isPresent()).isTrue();
        assertThat(commits).containsExactly("start@0");
    }

    private Promise<Unit> commit(String stream, int partition, StreamPartitionOwnershipValue current, long start, boolean restarted) {
        commits.add((restarted ? "restart@" : "start@") + start);
        record.set(Option.some(restarted
                               ? current.restarted(start, HlcTimestamp.ZERO)
                               : current.withEpochStart(start)));

        return Promise.success(Unit.unit());
    }

    private void publish(String prefix, int count) {
        for (var i = 0; i < count; i++) {
            assertThat(manager.publishLocal(STREAM, PARTITION, (prefix + "-" + i).getBytes(StandardCharsets.UTF_8), 1L)
                              .isSuccess()).isTrue();
        }
    }

    private long head() {
        return manager.partitionInfo(STREAM, PARTITION)
                      .map(StreamPartitionManager.PartitionInfo::headOffset)
                      .or(-1L);
    }

    private static StreamPartitionOwnershipValue committed(List<EpochStart> starts) {
        return new StreamPartitionOwnershipValue(SELF, E1, 3L, HlcTimestamp.ZERO, List.of(SELF), 1L, false, List.of(), starts);
    }
}
