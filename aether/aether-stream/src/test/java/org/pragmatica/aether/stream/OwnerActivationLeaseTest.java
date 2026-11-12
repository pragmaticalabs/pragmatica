// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;

/// #2981 step 2 (design §2.2 check 1, F-1a-5, F-1a-8): the lease branch of [OwnerActivation#admit] runs FIRST for a stream with
/// confirmation factor 2 or more, refuses with the transient [StreamError.OwnerLeaseLapsed], starts no activation and leaves the
/// activation as it was; a CF=1 stream takes no lease check.
class OwnerActivationLeaseTest {
    private static final String DURABLE = "orders";
    private static final String LOCAL = "clicks";
    private static final int PARTITION = 0;
    private static final NodeId SELF = new NodeId("self");

    private final AtomicBoolean leaseValid = new AtomicBoolean();
    private final AtomicInteger rounds = new AtomicInteger();
    private final OwnerActivation activation = OwnerActivation.ownerActivation(SELF,
                                                                               (_, _) -> Option.some(ownedBySelf()),
                                                                               (_, _) -> true,
                                                                               Option.some(this::round),
                                                                               () -> List.of(SELF),
                                                                               (_, _, _) -> Promise.success(-1L),
                                                                               (_, _) -> 0L,
                                                                               (_, _, _, _) -> Promise.success(0L),
                                                                               () -> true,
                                                                               (_, _, _, _, _) -> Promise.success(List.of()),
                                                                               _ -> Unit.unit(),
                                                                               PromotionTestRanges.NEVER_ALARM,
                                                                               OwnerActivation.RingIncarnation.NONE,
                                                                               OwnerActivation.LineageCommit.NONE,
                                                                               leaseValid::get,
                                                                               stream -> DURABLE.equals(stream) ? 2 : 1);

    /// Mutations: the lease branch removed (admit starts a gate and answers `OwnerNotActivated`), or the branch calling `attempt`
    /// (a round is ordered) -> RED.
    @Test
    void admit_cfTwoUnderALapsedLease_refusesWithOwnerLeaseLapsed_andStartsNoActivation() {
        var refused = activation.admit(DURABLE, PARTITION);

        assertThat(causeOf(refused)).isInstanceOf(StreamError.OwnerLeaseLapsed.class);
        LockSupport.parkNanos(300_000_000L);
        assertThat(rounds).as("no gate started by a lapse").hasValue(0);
        assertThat(activation.isActivated(DURABLE, PARTITION)).isFalse();
    }

    /// F-1a-8: a lapse never clears `activated`, so the next valid lease admits with no re-gate. Mutation: the lease branch placed
    /// after the activation check (an activated owner admits under a lapsed lease) -> RED.
    @Test
    void admit_lapseOfAnActivatedOwner_refusesAndKeepsTheActivation_soARenewalAdmitsWithoutAReGate() {
        leaseValid.set(true);
        assertThat(activation.activate(DURABLE, PARTITION).await().isSuccess()).isTrue();
        assertThat(activation.admit(DURABLE, PARTITION).isSuccess()).isTrue();
        var roundsWhenActivated = rounds.get();

        leaseValid.set(false);

        assertThat(causeOf(activation.admit(DURABLE, PARTITION))).isInstanceOf(StreamError.OwnerLeaseLapsed.class);
        assertThat(activation.isActivated(DURABLE, PARTITION)).as("the lapse left the activation").isTrue();

        leaseValid.set(true);

        assertThat(activation.admit(DURABLE, PARTITION).isSuccess()).isTrue();
        assertThat(rounds).as("no re-gate").hasValue(roundsWhenActivated);
    }

    /// Design §0 scope: CF=1 takes no lease check. Mutation: the threshold lowered to `>= 1` -> RED.
    @Test
    void admit_cfOneUnderALapsedLease_takesNoLeaseCheck() {
        assertThat(activation.activate(LOCAL, PARTITION).await().isSuccess()).isTrue();

        assertThat(activation.admit(LOCAL, PARTITION).isSuccess()).isTrue();
    }

    private Promise<Unit> round(String stream, int partition) {
        rounds.incrementAndGet();

        return Promise.success(Unit.unit());
    }

    private static Object causeOf(Result<Unit> result) {
        return result.fold(cause -> cause, _ -> "admitted");
    }

    private static StreamPartitionOwnershipValue ownedBySelf() {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(SELF, Epoch.epoch(0L, 1L, 0), 1L, HlcTimestamp.ZERO);
    }
}
