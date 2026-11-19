// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.assembly;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.ClusterTopologyManager;
import org.pragmatica.aether.deployment.cluster.DrainReason;
import org.pragmatica.aether.deployment.cluster.DrainSafetyGrace;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// #1880: a young core held back by the drain-safety grace is announced once per node per grace window, not on every
/// one-second voter-retirement tick. The choke point ([ClusterTopologyManager#drainNode]) stays the only enforcer: every
/// victim is still asked of it, a refusal for a held victim is absorbed, and a mature victim behind it is still drained.
class HeldCoreWarningsTest {
    private static final NodeId YOUNG = new NodeId("young-core");
    private static final NodeId OTHER_YOUNG = new NodeId("other-young-core");
    private static final NodeId MATURE = new NodeId("mature-core");
    private static final long WINDOW_MS = 120_000;

    private final AtomicLong clockMs = new AtomicLong();
    private final List<String> warnings = new CopyOnWriteArrayList<>();
    private final List<NodeId> asked = new CopyOnWriteArrayList<>();
    private final HeldCoreWarnings held = HeldCoreWarnings.heldCoreWarnings(clockMs::get, warnings::add);
    private final DrainSafetyGrace grace = DrainSafetyGrace.drainSafetyGrace(TimeSpan.timeSpan(60).seconds(), this::ageOf);

    @Test
    void youngCore_heldForTheWholeWindow_isWarnedOnce() {
        for (var tickMs = 0L; tickMs < WINDOW_MS; tickMs += 1000) {
            clockMs.set(tickMs);
            assertThat(tick(YOUNG).await().isSuccess()).as("a held victim is a deferral, not a failed tick").isTrue();
        }

        assertThat(warnings).as("one warning for 120 one-second ticks").hasSize(1);
        assertThat(warnings.getFirst()).contains(YOUNG.id());
        assertThat(asked).as("the choke point is asked on every tick").hasSize(120);
    }

    @Test
    void eachYoungCore_isWarnedOnce_andAMatureCoreBehindThemIsStillAsked() {
        tick(YOUNG, MATURE, OTHER_YOUNG);
        clockMs.set(1000);
        tick(YOUNG, MATURE, OTHER_YOUNG);

        assertThat(warnings).hasSize(2);
        assertThat(asked).containsExactly(YOUNG, MATURE, OTHER_YOUNG, YOUNG, MATURE, OTHER_YOUNG);
    }

    @Test
    void sameCoreHeldInANewWindow_isWarnedAgain() {
        held.announce(YOUNG, WINDOW_MS, "first");
        clockMs.set(WINDOW_MS - 1);
        held.announce(YOUNG, WINDOW_MS, "same window");
        clockMs.set(WINDOW_MS);
        held.announce(YOUNG, WINDOW_MS, "next window");

        assertThat(warnings).containsExactly("first", "next window");
    }

    @Test
    void refusalOfAMatureCore_stillFailsTheTick() {
        var refused = TopologyAssembly.retireSurplusCores(List.of(MATURE), topology(true), grace, held);

        assertThat(refused.await().isSuccess()).isFalse();
        assertThat(warnings).isEmpty();
    }

    private Promise<Unit> tick(NodeId... victims) {
        return TopologyAssembly.retireSurplusCores(List.of(victims), topology(false), grace, held);
    }

    /// Stands in for the choke point: refuses the nodes the grace holds (and every node when `refuseAll`).
    private ClusterTopologyManager topology(boolean refuseAll) {
        return (ClusterTopologyManager) Proxy.newProxyInstance(ClusterTopologyManager.class.getClassLoader(),
                                                               new Class<?>[]{ClusterTopologyManager.class},
                                                               (_, method, args) -> answer(method.getName(), args, refuseAll));
    }

    private Object answer(String method, Object[] args, boolean refuseAll) {
        return switch (method) {
            case "scalingHeld" -> false;
            case "drainNode" -> drain((NodeId) args[0], (DrainReason) args[1], refuseAll);
            default -> throw new UnsupportedOperationException(method);
        };
    }

    private Promise<Unit> drain(NodeId node, DrainReason reason, boolean refuseAll) {
        asked.add(node);

        return refuseAll || !grace.permits(node, reason)
               ? Causes.cause("refused").promise()
               : Promise.unitPromise();
    }

    private Option<Long> ageOf(NodeId node) {
        return node.equals(MATURE) ? Option.some(WINDOW_MS + 1) : Option.some(5_000L);
    }
}
