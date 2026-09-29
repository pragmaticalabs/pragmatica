// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.controller;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.SliceFailureEvent.AllInstancesFailed;
import org.pragmatica.aether.metrics.ExecutionOutcomeKeys;
import org.pragmatica.cluster.metrics.MetricObservation;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

/// #1573: the leader-side verdict over per-window DELTAS of the cumulative pong counters.
class AllInstancesFailedDetectorTest {
    private static final Artifact V2 = Artifact.artifact("org.test:my-slice:2.0.0").unwrap();
    private static final NodeId A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId B = NodeId.nodeId("node-b").unwrap();
    private static final String METHOD = "doSomething";

    private final AtomicLong clock = new AtomicLong(1_000_000L);
    private final AtomicBoolean leader = new AtomicBoolean(true);
    private final Map<NodeId, MetricObservation> observations = new HashMap<>();
    private final Map<NodeId, Long> sequences = new HashMap<>();
    private final List<AllInstancesFailed> published = new ArrayList<>();
    private AllInstancesFailedDetector detector;

    @BeforeEach
    void setUp() {
        detector = AllInstancesFailedDetector.allInstancesFailedDetector(() -> Map.copyOf(observations),
                                                                          () -> Map.of(V2, Set.of(A, B)),
                                                                          leader::get,
                                                                          published::add,
                                                                          clock::get);
    }

    @Test
    void everyHostOnlyDefects_publishesOnce() {
        pong(A, 1, 0, 0);
        pong(B, 1, 0, 0);
        tick();
        pong(A, 1, 0, 3);
        pong(B, 1, 0, 4);
        tick();
        tick();

        assertThat(published).hasSize(1);
        assertThat(published.getFirst().artifact()).isEqualTo(V2);
        assertThat(published.getFirst().attemptedNodes()).containsExactly(A, B);
    }

    @Test
    void cumulativeTotals_withoutAnyNewDefect_neverPublish() {
        pong(A, 1, 0, 50);
        pong(B, 1, 0, 50);
        tick();
        pong(A, 1, 0, 50);
        pong(B, 1, 0, 50);
        tick();

        assertThat(published).as("old cumulative defects are a baseline, not window activity").isEmpty();
    }

    @Test
    void oneSuccessOnOneHost_noEvent() {
        pong(A, 1, 0, 0);
        pong(B, 1, 0, 0);
        tick();
        pong(A, 1, 0, 5);
        pong(B, 1, 1, 5);
        tick();

        assertThat(published).isEmpty();
    }

    @Test
    void oneHostBelowThreshold_noEvent() {
        pong(A, 1, 0, 0);
        pong(B, 1, 0, 0);
        tick();
        pong(A, 1, 0, 5);
        pong(B, 1, 0, 2);
        tick();

        assertThat(published).isEmpty();
    }

    @Test
    void staleHost_excludesTheVersion() {
        pong(A, 1, 0, 0);
        pong(B, 1, 0, 0);
        tick();
        pong(A, 1, 0, 5);
        pong(B, 1, 0, 5);
        tick();
        published.clear();
        advance(AllInstancesFailedDetector.FRESHNESS.millis() + 1);
        pong(A, 1, 0, 10);
        tick();

        assertThat(published).as("B's pong stopped advancing: undecidable, never a trigger").isEmpty();
    }

    /// v1608 N1: freshness ALONE decides here. `staleHost_excludesTheVersion` publishes (and latches) before B
    /// goes stale, so the latch keeps it green with the freshness check removed. Here nothing has published:
    /// B's defects are inside the window but B's pong stopped advancing before A's defects arrived, so every
    /// other condition holds and only B's staleness keeps the version undecidable.
    @Test
    void staleHostWithDefectsInTheWindow_neverTriggers_whenNothingHasPublishedYet() {
        pong(A, 1, 0, 0);
        pong(B, 1, 0, 0);
        tick();
        pong(B, 1, 0, 5);
        tick();
        advance(AllInstancesFailedDetector.FRESHNESS.millis() + 1);
        pong(A, 1, 0, 5);
        tick();

        assertThat(published).as("B is stale: its in-window defects must not complete a verdict").isEmpty();
    }

    @Test
    void counterReset_isAFreshBaseline_notANegativeCount() {
        pong(A, 1, 0, 0);
        pong(B, 1, 0, 0);
        tick();
        pong(A, 1, 5, 0);
        pong(B, 1, 0, 0);
        tick();
        // A restarts within the same incarnation view: its counters drop. The drop must neither count as
        // negative successes (which would erase A's success) nor as defects.
        pong(A, 1, 0, 3);
        pong(B, 1, 0, 3);
        tick();

        assertThat(published).as("A's earlier success is still in the window").isEmpty();
    }

    @Test
    void newIncarnation_isAFreshBaseline() {
        pong(A, 1, 0, 0);
        pong(B, 1, 0, 0);
        tick();
        observations.put(A, new MetricObservation(2, 1, clock.get(), counts(0, 100)));
        pong(B, 1, 0, 5);
        tick();

        assertThat(published).as("a restarted producer's cumulative defects are not a window delta").isEmpty();
    }

    @Test
    void defectsOutsideTheWindow_areForgotten() {
        pong(A, 1, 0, 0);
        pong(B, 1, 0, 0);
        tick();
        pong(A, 1, 0, 5);
        tick();
        advance(AllInstancesFailedDetector.WINDOW.millis() + 1);
        pong(A, 1, 0, 5);
        pong(B, 1, 0, 5);
        tick();

        assertThat(published).as("A's defects aged out before B's arrived").isEmpty();
    }

    @Test
    void nonLeader_publishesNothing_andForgets() {
        leader.set(false);
        pong(A, 1, 0, 0);
        pong(B, 1, 0, 0);
        tick();
        pong(A, 1, 0, 5);
        pong(B, 1, 0, 5);
        tick();

        assertThat(published).isEmpty();
        leader.set(true);
        tick();
        assertThat(published).as("a new leader starts from fresh baselines").isEmpty();
    }

    @Test
    void latch_rearmsAfterTheVerdictClears() {
        pong(A, 1, 0, 0);
        pong(B, 1, 0, 0);
        tick();
        pong(A, 1, 0, 5);
        pong(B, 1, 0, 5);
        tick();
        pong(A, 1, 1, 5);
        tick();
        advance(AllInstancesFailedDetector.WINDOW.millis() + 1);
        pong(A, 1, 1, 10);
        pong(B, 1, 0, 10);
        tick();

        assertThat(published).hasSize(2);
    }

    private void tick() {
        detector.tick();
        advance(1_000);
    }

    private void advance(long millis) {
        clock.addAndGet(millis);
    }

    private void pong(NodeId node, long incarnation, long successes, long defects) {
        var sequence = sequences.merge(node, 1L, Long::sum);

        observations.put(node, new MetricObservation(incarnation, sequence, clock.get(), counts(successes, defects)));
    }

    private static Map<String, Double> counts(long successes, long defects) {
        return Map.of(ExecutionOutcomeKeys.successKey(V2, METHOD),
                      (double) successes,
                      ExecutionOutcomeKeys.defectKey(V2, METHOD),
                      (double) defects,
                      "cpu",
                      0.5);
    }
}
