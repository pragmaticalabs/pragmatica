// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.replica;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.AnchorPair;
import org.pragmatica.cluster.state.kvstore.IncarnationTag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.replica.FeedFixtures.TAG;

/// Spec 2.8: `ready()` is one computation of (a)..(f). Each test names the mutation of `ReplicaReadiness` that turns it red.
class ReplicaReadinessTest {
    private static final long MS = 1_000_000L;

    private final AtomicLong clock = new AtomicLong(1_000_000_000L);
    private final AtomicReference<ReplicaPosition> position = new AtomicReference<>();
    private final AtomicBoolean worker = new AtomicBoolean(true);
    private final AtomicBoolean directory = new AtomicBoolean(true);
    private final List<Boolean> published = new ArrayList<>();
    private ReplicaReadiness readiness;

    @BeforeEach
    void caughtUpAtSlot10() {
        position.set(ReplicaPosition.replicaPosition(TAG, 10, List.of(new AnchorPair(9, "x"))));
        readiness = ReplicaReadiness.replicaReadiness(clock::get, position::get, worker::get, directory::get, published::add, ReplicaClientLimits.DEFAULT);
        readiness.caughtUp(true);
        readiness.onHeartbeat(TAG, 10, true);
    }

    private void advance(long millis) {
        clock.addAndGet(millis * MS);
    }

    @Test
    void ready_whenAllSixHold() {
        assertThat(readiness.ready()).isTrue();
    }

    /// (a). Mutation: skip the tag comparison, or let `NONE` match `NONE`.
    @Test
    void ready_isFalse_whenTheHeartbeatTagIsAnotherLife() {
        readiness.sourceChanged();
        readiness.caughtUp(true);
        readiness.onHeartbeat(IncarnationTag.incarnationTag(2, "B"), 10, true);

        assertThat(readiness.ready()).isFalse();
    }

    @Test
    void ready_isFalse_whenNoHeartbeatWasCounted() {
        readiness.sourceChanged();
        readiness.caughtUp(true);

        assertThat(readiness.ready()).isFalse();
    }

    /// (b). Mutation: the 30 s bound compared with `>=` the wrong way, or never checked (today's latch).
    @Test
    void ready_isFalse_whenTheNewestHeartbeatIsOlderThan30Seconds() {
        advance(29_000);
        assertThat(readiness.ready()).isTrue();

        advance(1_500);
        assertThat(readiness.ready()).isFalse();
    }

    /// (c). Mutation: compare with the newest heartbeat instead of the newest one at least 5 s old; or drop the check.
    @Test
    void ready_isFalse_whenBehindAHeartbeatThatIsFiveSecondsOld() {
        readiness.onHeartbeat(TAG, 15, true);
        advance(4_000);
        assertThat(readiness.ready()).as("a 4 s old claim of 15 is not yet required").isTrue();

        advance(1_500);
        readiness.onHeartbeat(TAG, 15, true);
        assertThat(readiness.ready()).as("the 5.5 s old claim of 15 is required, applied is 10").isFalse();

        position.set(ReplicaPosition.replicaPosition(TAG, 15, List.of(new AnchorPair(14, "y"))));
        assertThat(readiness.ready()).isTrue();
    }

    /// (c), only a young claim: with no heartbeat at least 5 s old there is nothing to catch up to yet. Mutation: drop the `>= lagNanos` filter in
    /// `isCaughtUpToLag` (the young claim of 15 would then be required).
    @Test
    void ready_isTrue_whenTheOnlyHeartbeatIsYoungerThanTheLagWindow_evenIfBehindIt() {
        readiness.sourceChanged();
        readiness.caughtUp(true);
        readiness.onHeartbeat(TAG, 15, true);
        advance(1_000);

        assertThat(readiness.ready()).isTrue();
    }

    /// Spec 2.8 (b)/(c) "from the current source": a claim of the previous source, older than the lag window, must not hold the replica back after a
    /// source change. Mutation: drop `beats.clear()` in `sourceChanged`.
    @Test
    void afterASourceChange_aStaleClaimOfTheOldSource_isNotRequired() {
        readiness.onHeartbeat(TAG, 100, true);
        advance(6_000);
        readiness.sourceChanged();
        readiness.caughtUp(true);
        readiness.onHeartbeat(TAG, 10, true);

        assertThat(readiness.ready()).isTrue();
    }

    /// (d). Mutation: drop the role check.
    @Test
    void ready_isFalse_whenTheOwnDirectiveIsNotWorker() {
        worker.set(false);

        assertThat(readiness.ready()).isFalse();
    }

    /// (e). Mutation: drop the catch-up check.
    @Test
    void ready_isFalse_beforeTheInitialCatchUp() {
        readiness.caughtUp(false);

        assertThat(readiness.ready()).isFalse();
    }

    /// (f). Mutation: drop the directory check.
    @Test
    void ready_isFalse_withoutAnInstalledDirectory() {
        directory.set(false);

        assertThat(readiness.ready()).isFalse();
    }

    /// Mutation: count a heartbeat from a source that is not serving.
    @Test
    void aHeartbeatFromASourceThatIsNotServing_isNeverCounted() {
        readiness.sourceChanged();
        readiness.caughtUp(true);
        readiness.onHeartbeat(TAG, 10, false);

        assertThat(readiness.ready()).isFalse();
    }

    /// Mutation: count an AHEAD heartbeat (`nextSlot < applied` under an equal tag).
    @Test
    void anAheadHeartbeat_isNeverCounted() {
        readiness.sourceChanged();
        readiness.caughtUp(true);

        assertThat(readiness.isAhead(TAG, 9)).isTrue();
        readiness.onHeartbeat(TAG, 9, true);

        assertThat(readiness.ready()).isFalse();
    }

    /// I-W6: one publisher. Mutation: publish on every call, or never publish the falling edge (today's latch).
    @Test
    void refresh_publishesTheFirstValue_andEveryChange_only() {
        readiness.refresh();
        readiness.refresh();
        worker.set(false);
        readiness.refresh();
        readiness.refresh();
        worker.set(true);
        readiness.refresh();

        assertThat(published).containsExactly(true, false, true);
    }

    /// I-W5 input: `servable()` is (a)-(c), (e) plus `applied >= nextSlot` of the NEWEST counted heartbeat; the worker role (d) and the directory (f)
    /// are not part of it. Mutation: compare against the oldest heartbeat only (the 5 s lag one), or require (d) or (f).
    @Test
    void servable_isTrue_whenCaughtUpToTheNewestHeartbeat_whateverTheRoleAndDirectory() {
        worker.set(false);
        directory.set(false);

        assertThat(readiness.ready()).as("control: (d) and (f) fail").isFalse();
        assertThat(readiness.servable()).isTrue();
    }

    @Test
    void servable_isFalse_whenTheSourceIsAheadOfTheReplicaByAFreshHeartbeat() {
        readiness.onHeartbeat(TAG, 12, true);

        assertThat(readiness.ready()).as("control: the 5 s lag rule has not looked at the new heartbeat yet").isTrue();
        assertThat(readiness.servable()).as("a governor behind its core's newest frontier must not serve members").isFalse();

        position.set(ReplicaPosition.replicaPosition(TAG, 12, List.of(new AnchorPair(11, "y"))));

        assertThat(readiness.servable()).isTrue();
    }

    /// Mutation: drop (e) or (b) from the shared computation.
    @Test
    void servable_isFalse_beforeTheInitialCatchUp_andWhenTheHeartbeatsAreStale() {
        readiness.caughtUp(false);
        assertThat(readiness.servable()).isFalse();

        readiness.caughtUp(true);
        advance(30_500);
        assertThat(readiness.servable()).isFalse();
    }
}
