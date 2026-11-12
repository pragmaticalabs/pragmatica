// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.api.ClusterEventAggregator;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterEffect.Warn;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.SystemStreamFactories;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// CTO ruling A2 on #2831 (O3): the D.6 warnings across a leader change, through the REAL `ClusterEventAggregator` of each leader. The
/// deposed leader's warnings go to its own aggregator and the successor's to a fresh one (the successor's event layer never saw the
/// deposed leader's warnings), exactly as the effect translator raises them. The operator's view is both event logs together.
/// - A committed-state family (rows 18/19) is closed by the successor: its recovery closes across nodes.
/// - A leader-local family (row 10) is inherited by the successor from the committed open set (#2834): it is not raised again, and the
///   successor closes it when the condition ends, also when that happened while no leader watched.
class LeaderChangeRecoveriesTest {
    private static final long T_LOAD = 120_000L;
    private static final List<String> UNRESPONSIVE = List.of("slice-unresponsive", "slice-unresponsive-expired");
    private static final List<String> PLACEMENT = List.of("invalid-placement", "placement-valid");
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    /// Rows 18/19: the deposed leader excluded core-1 for ORDERS and raised `slice-unresponsive`; the successor rebuilds from the committed
    /// exclusion, and the instance going ends it. Red when: the successor's aggregator refuses `slice-unresponsive-expired` (no open mark of
    /// its own), so the warning stays open in the operator's view.
    @Test
    void committedFamily_isClosedByTheSuccessor() {
        var h = threeCores();

        seedTarget(h, ORDERS, 1, 1);
        h.activate();
        node(h, CORE_1, 11L).writes(ORDERS, SliceObservedState.LOADING);
        h.drain();
        at(h, T_LOAD + 1);
        at(h, T_LOAD);
        at(h, T_LOAD);
        var deposed = raisedSoFar(h, aggregator());

        leaderChange(h, 2L);
        h.seedRemove(new SliceObservedKey(CORE_1, ORDERS));
        h.drain();
        var successor = raisedSince(h, deposed.raised(), aggregator());

        assertThat(codes(deposed.aggregator(), UNRESPONSIVE)).as("the deposed leader published the opener").containsExactly("slice-unresponsive");
        assertThat(h.warns("slice-unresponsive-expired")).as("the successor raised the close from committed state").hasSize(1);
        assertThat(codes(successor, UNRESPONSIVE)).as("the successor publishes the close it never opened").containsExactly("slice-unresponsive-expired");
    }

    /// Row 10: the deposed leader raised `invalid-placement` and persisted it; the placement is still invalid when the successor activates,
    /// so the successor inherits the open warning (does not raise it again) and closes it when the placement parses. Red when: the open set
    /// is not inherited (the successor raises `invalid-placement` a second time, or its `placement-valid` is refused).
    @Test
    void leaderLocalFamily_isInheritedBySuccessor_notRaisedAgain_andClosedByIt() {
        var h = threeCores();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.activate();
        var deposed = raisedSoFar(h, aggregator());

        leaderChange(h, 2L);
        h.deliver(new ClusterInput.Reconcile());
        h.seed(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "CORE_ONLY"));
        h.drain();
        h.deliver(new ClusterInput.Reconcile());
        var successor = raisedSince(h, deposed.raised(), aggregator());

        assertThat(codes(deposed.aggregator(), PLACEMENT)).containsExactly("invalid-placement");
        assertThat(codes(successor, PLACEMENT)).as("inherited, not raised again; closed by the successor")
                                    .containsExactly("placement-valid");
    }

    /// #2834: a leader-local warning whose condition ended between leaders is closed once, by the successor that inherits it from the
    /// committed open set. Red when: the open set is not committed or not inherited, so the successor never publishes `placement-valid`.
    @Test
    void leaderLocalFamily_conditionEndedBetweenLeaders_isClosed() {
        var views = endedBetweenLeaders();

        assertThat(codes(views.getLast(), PLACEMENT)).containsExactly("placement-valid");
    }

    /// The deposed leader raises `invalid-placement`; the placement is fixed while it is deposed; the successor activates. Returns the
    /// deposed leader's aggregator and the successor's.
    private static List<ClusterEventAggregator> endedBetweenLeaders() {
        var h = threeCores();

        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "NOWHERE"));
        h.activate();
        var deposed = raisedSoFar(h, aggregator());

        h.deliver(new ClusterInput.Deactivate());
        h.seedQuietly(new SliceTargetKey(ORDERS.base()), target(ORDERS, 2, 1, "CORE_ONLY"));
        h.commitLeader(FsmHarness.SELF, 2L);
        h.drain();
        h.activate();
        h.deliver(new ClusterInput.Reconcile());
        assertThat(countGoals(h, ORDERS)).as("precondition: the successor placed the fixed placement").isEqualTo(2L);

        return List.of(deposed.aggregator(), raisedSince(h, deposed.raised(), aggregator()));
    }

    // -- the leader change and the event layer -------------------------------------------------------------------
    /// A second incarnation: the same node leads again under a higher viewSequence and rebuilds from the store alone.
    private static void leaderChange(FsmHarness h, long viewSequence) {
        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(FsmHarness.SELF, viewSequence);
        h.drain();
        h.activate();
    }

    private static FsmHarness at(FsmHarness h, long ms) {
        h.advance(ms);

        return h.deliver(new ClusterInput.Reconcile());
    }

    private record Raised(ClusterEventAggregator aggregator, int raised) {}

    /// Every warning the FSM raised so far, raised on `target` as the effect translator does. Returns the count, where the successor starts.
    private static Raised raisedSoFar(FsmHarness h, ClusterEventAggregator target) {
        var warns = h.effects(Warn.class);

        warns.forEach(warn -> raise(target, warn));

        return new Raised(target, warns.size());
    }

    private static ClusterEventAggregator raisedSince(FsmHarness h, int from, ClusterEventAggregator target) {
        var warns = h.effects(Warn.class);

        warns.subList(from, warns.size())
             .forEach(warn -> raise(target, warn));

        return target;
    }

    /// `EffectTranslator.raise`: a known code becomes an `OperatorWarning` on the event layer.
    private static void raise(ClusterEventAggregator target, Warn warn) {
        Arrays.stream(OperatorWarningCode.values())
              .filter(known -> known.code()
                                    .equals(warn.code()))
              .findFirst()
              .ifPresent(known -> target.onOperatorWarning(OperatorWarning.operatorWarning(known, warn.subject(), warn.message())));
    }

    /// The codes `aggregator` published, of the family `family` only (an unrelated warning, such as absence on a harness with no
    /// absence signals, is not under test).
    private static List<String> codes(ClusterEventAggregator aggregator, List<String> family) {
        return aggregator.events()
                         .await()
                         .or(List.of())
                         .stream()
                         .map(ClusterEvent::details)
                         .map(details -> details.get("code"))
                         .filter(family::contains)
                         .toList();
    }

    /// A leader's event layer, built as `ClusterEventAggregatorTest` builds it: a real single-partition cluster-events stream, this node
    /// its owner and the leader.
    private static ClusterEventAggregator aggregator() {
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        var config = StreamConfig.streamConfig(SystemStreams.CLUSTER_EVENTS.asString(),
                                               1,
                                               RetentionPolicy.retentionPolicy(10_000, 64L * 1024 * 1024, Long.MAX_VALUE, RetentionMode.ANY),
                                               "earliest",
                                               64L * 1024,
                                               ConsistencyMode.EVENTUAL,
                                               1);
        var publisher = new AtomicReference<FrameworkStreamPublisher<ClusterEvent>>(SystemStreamFactories.<ClusterEvent>systemStreamPublisher(SystemStreams.CLUSTER_EVENTS,
                                                                                                                                              manager,
                                                                                                                                              CODEC,
                                                                                                                                              config)
                                                                                                         .unwrap());
        var consumer = new AtomicReference<FrameworkStreamConsumer<ClusterEvent>>(SystemStreamFactories.<ClusterEvent>systemStreamConsumer(SystemStreams.CLUSTER_EVENTS,
                                                                                                                                           manager,
                                                                                                                                           CODEC,
                                                                                                                                           CODEC,
                                                                                                                                           config)
                                                                                                       .unwrap());
        var self = new NodeId("self-node");

        return ClusterEventAggregator.clusterEventAggregator(publisher::get,
                                                             consumer::get,
                                                             () -> true,
                                                             self,
                                                             HlcClock.hlcClock(self),
                                                             () -> 1,
                                                             () -> false,
                                                             () -> true);
    }
}
