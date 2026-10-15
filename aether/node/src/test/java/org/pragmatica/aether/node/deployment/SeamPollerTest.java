// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.cluster.fsm2.Seams;
import org.pragmatica.aether.deployment.wiring.FsmTimers.TimerService;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.lang.Option;
import org.pragmatica.hlc.HlcTimestamp;

import static org.assertj.core.api.Assertions.assertThat;

/// W-14 (`SeamPoller`) and W-23 (the membership attachment, core arms) of the phase-3 design 9.2; MT-13a. The sink is a recording double; the
/// production sink (`ClusterFsmInterpreter`) and its coalescing are pinned in `aether-deployment` (W-12, W-33).
class SeamPollerTest {
    private static final NodeId SELF = NodeId.nodeId("core-1").unwrap();
    private static final NodeId PEER = NodeId.nodeId("core-2").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("core-3").unwrap();

    /// A clock that fires on demand.
    private static final class ManualClock implements TimerService {
        private final List<Runnable> due = new ArrayList<>();
        private final List<Long> delays = new ArrayList<>();

        @Override
        public Runnable schedule(Runnable body, long delayMs) {
            due.add(body);
            delays.add(delayMs);

            return () -> due.remove(body);
        }

        void fire() {
            var bodies = List.copyOf(due);

            due.clear();
            bodies.forEach(Runnable::run);
        }
    }

    private final List<String> log = Collections.synchronizedList(new ArrayList<>());
    private final List<Seams> refreshed = Collections.synchronizedList(new ArrayList<>());
    private final Map<NodeId, Long> tokens = new HashMap<>();
    private final AtomicReference<Set<NodeId>> counted = new AtomicReference<>(Set.of(SELF, PEER));
    private final AtomicReference<Option<Set<NodeId>>> coreView = new AtomicReference<>(Option.none());
    private final AtomicReference<Option<Set<NodeId>>> localAlive = new AtomicReference<>(Option.none());
    private final ManualClock clock = new ManualClock();
    private final SeamPoller.Sink sink = new SeamPoller.Sink() {
        @Override
        public boolean seamsRefreshed(Seams seams) {
            refreshed.add(seams);
            log.add("SeamsRefreshed");

            return true;
        }

        @Override
        public boolean processKnown(NodeId node, long token) {
            log.add("ProcessKnown:" + node.id() + ":" + token);

            return true;
        }
    };

    private SeamPoller poller() {
        return SeamPoller.seamPoller(new SeamPoller.Sources(counted::get,
                                                            counted::get,
                                                            Set::of,
                                                            coreView::get,
                                                            localAlive::get,
                                                            Set::of,
                                                            Map::of,
                                                            Map::of,
                                                            counted::get,
                                                            node -> tokens.getOrDefault(node, 0L)),
                                     sink,
                                     clock);
    }

    // -- W-14, MT-13a ---------------------------------------------------------------------------------------------------
    @Test
    void poll_unchangedSample_enqueuesNothingMore() {
        var poller = poller();

        poller.poll();
        poller.poll();
        poller.poll();

        assertThat(refreshed).as("MT-13a: a sample without a diff is not delivered").hasSize(1);
    }

    @Test
    void poll_aChange_enqueuesExactlyOne() {
        var poller = poller();

        poller.poll();
        counted.set(Set.of(SELF, PEER, OTHER));
        poller.poll();
        poller.poll();

        assertThat(refreshed).hasSize(2);
        assertThat(refreshed.get(1).countedCores()).containsExactlyInAnyOrder(SELF, PEER, OTHER);
    }

    @Test
    void poll_anUnwiredCoreViewAndLocalAlive_stayNone_notEmptySets() {
        poller().poll();

        assertThat(refreshed.get(0).coreView()).as("an unwired coreView is none, not an empty set (L-18 fail-safe)").isEqualTo(Option.none());
        assertThat(refreshed.get(0).localAlive()).isEqualTo(Option.none());
    }

    @Test
    void poll_aWiredCoreView_reachesTheSeams() {
        coreView.set(Option.some(Set.of(SELF, PEER)));

        poller().poll();

        assertThat(refreshed.get(0).coreView()).isEqualTo(Option.some(Set.of(SELF, PEER)));
    }

    @Test
    void sample_isAFreshReadEveryTime_forTheRebuildSnapshot() {
        var poller = poller();

        assertThat(poller.sample().countedCores()).containsExactlyInAnyOrder(SELF, PEER);
        counted.set(Set.of(SELF));
        assertThat(poller.sample().countedCores()).containsExactly(SELF);
        assertThat(refreshed).as("sample() delivers nothing").isEmpty();
    }

    @Test
    void afterMembershipInput_samplesBehindTheInput_notBefore() {
        var poller = poller();
        var inputs = MembershipInputs.membershipInputs(input -> log.add(input.getClass().getSimpleName()),
                                                       AdmissionSeam.admissionSeam(_ -> 7L, _ -> 1L),
                                                       poller);

        poller.poll();
        log.clear();
        counted.set(Set.of(SELF));
        inputs.onNodeRemoved(new MembershipDecision.NodeRemoved(PEER, List.of(SELF), 1L, HlcTimestamp.ZERO));

        assertThat(log).as("M1: the sample that lacks the removed node is appended behind NodeRemoved").containsExactly("NodeRemoved", "SeamsRefreshed");
    }

    // -- ProcessKnown (CI-15b) -------------------------------------------------------------------------------------------
    @Test
    void poll_aTokenGoingFromZeroToT_isReportedOnce() {
        var poller = poller();

        poller.poll();
        tokens.put(PEER, 42L);
        poller.poll();
        poller.poll();

        assertThat(log).filteredOn(line -> line.startsWith("ProcessKnown")).containsExactly("ProcessKnown:core-2:42");
    }

    @Test
    void poll_aTokenKnownAtTheFirstSample_isNotReported_andAChangeOfToken_isNotAZeroToT() {
        tokens.put(PEER, 42L);
        var poller = poller();

        poller.poll();
        tokens.put(PEER, 43L);
        poller.poll();

        assertThat(log).as("known at the first sample is not learned later; t to t' is not 0 to t").noneMatch(line -> line.startsWith("ProcessKnown"));
    }

    // -- the cadence ------------------------------------------------------------------------------------------------------
    @Test
    void start_samplesAtOnce_thenEveryInterval_untilStopped() {
        var poller = poller();

        poller.start();
        assertThat(refreshed).as("the first sample is immediate").hasSize(1);
        assertThat(clock.delays).containsExactly(SeamPoller.INTERVAL_MS);

        counted.set(Set.of(SELF));
        clock.fire();
        assertThat(refreshed).as("the tick sampled").hasSize(2);
        assertThat(clock.delays).as("and re-armed").hasSize(2);

        poller.stop();
        counted.set(Set.of(SELF, OTHER));
        clock.fire();
        assertThat(refreshed).as("a stopped poller samples no more").hasSize(2);
    }

    // -- W-23 (core arms) ---------------------------------------------------------------------------------------------------
    @Test
    void nodeJoined_carriesTheAdmission_withTheTokenTheRegistryHasNow() {
        tokens.put(PEER, 99L);
        var inputs = new ArrayList<ClusterInput>();
        var membership = MembershipInputs.membershipInputs(inputs::add, AdmissionSeam.admissionSeam(node -> tokens.getOrDefault(node, 0L), _ -> 5L), poller());

        membership.onNodeJoined(new MembershipDecision.NodeJoined(PEER, List.of(SELF, PEER), 1L, HlcTimestamp.ZERO));

        assertThat(inputs).hasSize(1);
        var joined = (ClusterInput.NodeJoined) inputs.get(0);

        assertThat(joined.node()).isEqualTo(PEER);
        assertThat(joined.admission().token()).as("W-23: admission(n) is attached").isEqualTo(99L);
        assertThat(joined.admission().process().bootIncarnation()).isEqualTo(5L);
    }

    @Test
    void nodeRemoved_isDelivered_andAnUnknownTokenIsAdmissionUnknownTokenZero() {
        var inputs = new ArrayList<ClusterInput>();
        var membership = MembershipInputs.membershipInputs(inputs::add, AdmissionSeam.admissionSeam(_ -> 0L, _ -> 0L), poller());

        membership.onNodeRemoved(new MembershipDecision.NodeRemoved(PEER, List.of(SELF), 2L, HlcTimestamp.ZERO));
        membership.onNodeJoined(new MembershipDecision.NodeJoined(OTHER, List.of(SELF, OTHER), 3L, HlcTimestamp.ZERO));

        assertThat(inputs.get(0)).isEqualTo(new ClusterInput.NodeRemoved(PEER));
        assertThat(((ClusterInput.NodeJoined) inputs.get(1)).admission().tokenKnown()).as("0 carries no process identity (C8)").isFalse();
    }

    @Test
    void admissions_forTheSnapshot_coverEveryNodeGiven() {
        tokens.put(PEER, 7L);
        var seam = AdmissionSeam.admissionSeam(node -> tokens.getOrDefault(node, 0L), _ -> 0L);

        var admissions = seam.admissions(Set.of(SELF, PEER));

        assertThat(admissions).containsOnlyKeys(SELF, PEER);
        assertThat(admissions.get(PEER).token()).isEqualTo(7L);
        assertThat(admissions.get(SELF).tokenKnown()).isFalse();
    }
}
