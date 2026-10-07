// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// B5/B7 (#1730 phase 2): a node that is not the owner of the committed epoch serves, and acknowledges, what it holds at and above
/// that epoch's start only once it has been compared with that epoch's owner. The case that motivated it: a DEMOTED live owner X,
/// with no restart, still holds its old records 5..9 while the committed epoch E2 began at 5; a consumer correctly diverged to 5
/// re-reads from X and was served x-old-5..9 stamped E2.
class ReplicaEpochVerificationTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);

    private final AtomicReference<Epoch> committed = new AtomicReference<>(E1);
    private final AtomicReference<Role> role = new AtomicReference<>(Role.OWNER);
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager(Long.MAX_VALUE);
        manager.ownerEpochSource((_, _) -> committed.get());
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 600_000), "earliest"))
               .onFailure(cause -> fail(cause.message()));
        manager.placementRoleSupplier((_, _) -> role.get());
        manager.epochStarts((_, _, epoch) -> epoch.equals(E2)
                                              ? Option.some(5L)
                                              : Option.none());
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /// A demoted owner: it appended 0..9 as the owner of E1, then the committed epoch became E2 (started at 5) with another owner.
    @Test
    void demotedOwner_holdingItsOldTail_servesNothingFromTheNewEpochsStart_untilVerified() {
        appendOld(10);
        role.set(Role.REPLICA);
        committed.set(E2);

        assertThat(manager.replicaVerified(STREAM, PARTITION)).as("it holds 5..9 it has not compared with E2's owner").isFalse();

        var refused = manager.readServing(STREAM, PARTITION, 5L, 10);

        assertThat(refused.isFailure()).as("got %s", refused).isTrue();
        refused.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReplicaNotVerified.class));
        assertThat(texts(manager.readServing(STREAM, PARTITION, 0L, 10).unwrap())).as("below the epoch's start nothing is held back")
                                                                                   .containsExactly("old-0", "old-1", "old-2", "old-3", "old-4");

        manager.markVerifiedForEpoch(STREAM, PARTITION, E2);

        assertThat(manager.replicaVerified(STREAM, PARTITION)).isTrue();
        assertThat(manager.readServing(STREAM, PARTITION, 5L, 10).unwrap()).as("verified for E2: served").hasSize(5);
    }

    /// Control: a verified replica that is simply quiet keeps serving and acknowledging, and a verification for an OLDER epoch
    /// does not count for the new one.
    @Test
    void control_aReplicaVerifiedForTheCurrentEpoch_keepsServing_andAnOlderVerificationDoesNotCount() {
        appendOld(10);
        role.set(Role.REPLICA);
        committed.set(E2);
        manager.markVerifiedForEpoch(STREAM, PARTITION, E1);

        assertThat(manager.replicaVerified(STREAM, PARTITION)).as("verified for E1, not E2").isFalse();

        manager.markVerifiedForEpoch(STREAM, PARTITION, E2);
        committed.set(E2);

        assertThat(manager.replicaVerified(STREAM, PARTITION)).isTrue();
    }

    /// Control: a replica that held nothing at or above the new epoch's start when the epoch began has nothing to doubt: the
    /// owner's later records at and above the start are served without any compare.
    @Test
    void control_aReplicaThatHeldNothingFromTheStart_isTrusted_andServesTheNewLineage() {
        appendOld(3);
        role.set(Role.REPLICA);
        committed.set(E2);

        assertThat(manager.replicaVerified(STREAM, PARTITION)).as("head 2 is below the start 5").isTrue();

        for (var i = 3; i < 8; i++) {
            manager.appendRecovered(STREAM, PARTITION, i, ("new-" + i).getBytes(UTF_8), 2000L + i, E2).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();

        assertThat(manager.replicaVerified(STREAM, PARTITION)).as("still trusted after the new lineage reached it").isTrue();
        assertThat(manager.readServing(STREAM, PARTITION, 5L, 10).isSuccess()).isTrue();
    }

    /// No committed ownership record (epoch ZERO): nothing to doubt.
    @Test
    void noCommittedEpoch_isNeverDoubted() {
        appendOld(10);
        role.set(Role.REPLICA);
        committed.set(Epoch.ZERO);

        assertThat(manager.replicaVerified(STREAM, PARTITION)).isTrue();
    }

    private void appendOld(int count) {
        for (var i = 0; i < count; i++) {
            manager.appendRecovered(STREAM, PARTITION, i, ("old-" + i).getBytes(UTF_8), 1000L + i, E1).unwrap();
        }
        manager.syncReplicated(STREAM, PARTITION).await();
    }

    private static List<String> texts(List<OffHeapRingBuffer.RawEvent> events) {
        return events.stream().map(event -> new String(event.data(), UTF_8)).toList();
    }
}
