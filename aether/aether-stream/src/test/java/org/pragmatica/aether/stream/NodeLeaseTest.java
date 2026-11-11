// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLeaseKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLeaseValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2981 step 1 (design §2.1, §4, §8): the node lease on a fake clock. Each test names the property it pins.
class NodeLeaseTest {
    private static final long T = TimeUnit.SECONDS.toNanos(3);
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final NodeId SELF = NodeId.randomNodeId("self");
    private static final NodeId OTHER = NodeId.randomNodeId("other");

    private final AtomicLong mono = new AtomicLong(1_000_000_000L);
    private final AtomicLong wall = new AtomicLong(1_700_000_000_000L);
    private final AtomicBoolean replaying = new AtomicBoolean(false);
    private final List<Long> submitted = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private NodeLease lease;

    @BeforeEach
    void setUp() {
        lease = NodeLease.nodeLease(SELF,
                                    timeSpan(3).seconds(),
                                    timeSpan(1).seconds(),
                                    0.1,
                                    this::submit,
                                    replaying::get,
                                    new FakeClock(),
                                    new RecordingEvents());
    }

    @Test
    void valid_beforeAnyRenewalIsDelivered_isFalse() {
        assertThat(lease.valid()).isFalse();
        assertThat(events).isEmpty();
    }

    @Test
    void renew_completedButNotDelivered_doesNotExtendTheLease() {
        lease.renew();

        assertThat(submitted).hasSize(1);
        assertThat(lease.valid()).isFalse();

        deliverOwn(0);

        assertThat(lease.valid()).isTrue();
    }

    @Test
    void expiry_isAnchoredAtSubmissionTime_notAtDelivery() {
        lease.renew();
        advance(2_000);
        deliverOwn(0);
        advance(999);

        assertThat(lease.valid()).isTrue();

        advance(1);

        assertThat(lease.valid()).isFalse();
    }

    @Test
    void lease_withoutAnAppliedRenewalForT_lapsesAndReportsOnce() {
        lease.renew();
        deliverOwn(0);
        advance(2_000);
        lease.renew();
        advance(999);

        assertThat(lease.valid()).isTrue();

        advance(1);

        assertThat(lease.valid()).isFalse();
        assertThat(lease.valid()).isFalse();
        assertThat(events).containsExactly("lapsed EXPIRED 0");
    }

    @Test
    void expiry_neverDecreases_whenAnOlderRenewalIsDeliveredLate() {
        lease.renew();
        advance(1_000);
        lease.renew();
        deliverOwn(1);
        deliverOwn(0);
        advance(2_999);

        assertThat(lease.valid()).isTrue();
    }

    @Test
    void validity_followsTheMonotonicClock_notTheWallClock() {
        lease.renew();
        deliverOwn(0);
        wall.addAndGet(-TimeUnit.HOURS.toMillis(1));
        advance(2_999);

        assertThat(lease.valid()).isTrue();

        mono.addAndGet(MS);

        assertThat(lease.valid()).isFalse();
    }

    @Test
    void crossCheck_wallAheadOfMonotonicByMoreThanT_revokesAtTheCheck() {
        lease.renew();
        deliverOwn(0);
        advance(500);
        wall.addAndGet(10_000);

        assertThat(lease.valid()).isFalse();
        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).startsWith("lapsed CLOCK_SUSPEND");
    }

    @Test
    void crossCheck_renewalSubmittedBeforeTheSuspend_doesNotClearTheRevocation() {
        lease.renew();
        deliverOwn(0);
        advance(500);
        lease.renew();
        wall.addAndGet(10_000);

        assertThat(lease.valid()).isFalse();

        deliverOwn(1);

        assertThat(lease.valid()).isFalse();

        advance(1);
        lease.renew();
        deliverOwn(2);

        assertThat(lease.valid()).isTrue();
    }

    @Test
    void revoke_onlyARenewalSubmittedAfterTheLatestRevocationClearsIt() {
        lease.renew();
        deliverOwn(0);
        advance(10);
        lease.revoke();
        advance(10);
        lease.renew();
        advance(10);
        lease.revoke();
        deliverOwn(1);

        assertThat(lease.valid()).isFalse();

        advance(10);
        lease.renew();
        deliverOwn(2);

        assertThat(lease.valid()).isTrue();
    }

    @Test
    void renew_whileRevoked_keepsSubmitting() {
        lease.revoke();
        lease.renew();
        lease.renew();

        assertThat(submitted).hasSize(2);
    }

    @Test
    void onRenewal_replayItem_extendsNothingAndRecordsNothing() {
        lease.renew();
        replaying.set(true);
        deliverOwn(0);
        deliver(OTHER, 42);

        assertThat(lease.valid()).isFalse();
        assertThat(lease.renewalApplied(SELF).isEmpty()).isTrue();
        assertThat(lease.renewalApplied(OTHER).isEmpty()).isTrue();
    }

    @Test
    void onRenewal_anyNodesRenewal_recordsItsDeliveryTime() {
        advance(5);
        deliver(OTHER, 42);

        assertThat(lease.renewalApplied(OTHER).or(-1L)).isEqualTo(mono.get());
        assertThat(lease.valid()).isFalse();
    }

    @Test
    void onRenewal_ownSeqNotInFlight_extendsNothing() {
        lease.renew();
        deliver(SELF, submitted.getFirst() + 1_000);

        assertThat(lease.valid()).isFalse();
    }

    @Test
    void rateDivergence_wallRateOffByMoreThanRho_isReported() {
        lease.renew();
        deliverOwn(0);
        mono.addAndGet(1_000 * MS);
        wall.addAndGet(1_200);
        lease.renew();
        deliverOwn(1);

        assertThat(events).containsExactly("divergence 1000000000 1200000000");
    }

    @Test
    void rateDivergence_wallRateWithinRho_isNotReported() {
        lease.renew();
        deliverOwn(0);
        mono.addAndGet(1_000 * MS);
        wall.addAndGet(1_050);
        lease.renew();
        deliverOwn(1);

        assertThat(events).isEmpty();
    }

    private void advance(long millis) {
        mono.addAndGet(millis * MS);
        wall.addAndGet(millis);
    }

    private void deliverOwn(int index) {
        deliver(SELF, submitted.get(index));
    }

    private void deliver(NodeId submitter, long seq) {
        lease.onRenewal(new ValuePut<>(new KVCommand.Put<>(NodeLeaseKey.nodeLeaseKey(submitter),
                                                           NodeLeaseValue.nodeLeaseValue(seq)),
                                       Option.none()));
    }

    private Promise<List<Object>> submit(List<KVCommand<AetherKey>> commands) {
        commands.forEach(this::record);

        return Promise.success(List.of());
    }

    private void record(KVCommand<AetherKey> command) {
        if (command instanceof KVCommand.Put<AetherKey, ?> put && put.value() instanceof NodeLeaseValue value) {
            submitted.add(value.seq());
        }
    }

    private final class FakeClock implements NodeLease.LeaseClock {
        @Override
        public long nanoTime() {
            return mono.get();
        }

        @Override
        public long wallMillis() {
            return wall.get();
        }
    }

    private final class RecordingEvents implements NodeLease.Events {
        @Override
        public void lapsed(NodeLease.LapseReason reason, long nanos) {
            events.add("lapsed " + reason + " " + nanos);
        }

        @Override
        public void clockRateDivergence(long monotonicNanos, long wallNanos) {
            events.add("divergence " + monotonicNanos + " " + wallNanos);
        }
    }
}
