// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;
import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherKey.ArtifactJudgmentKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ArtifactJudgmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;


/// NT-78: every input is counted, and no ordinary input iterates a store-wide index (R-7.3.1).
class ScanAndMetricsTest {
    private static final int NODES = 40;
    private static final int PAIRS = 2_400;

    private static NodeId nodeOf(int i) {
        return node("w-" + (i % NODES));
    }

    private static Artifact artifactOf(int i) {
        return artifact("org.example:a" + i + ":1.0.0");
    }

    /// A store with thousands of pairs, each node holding fewer than a bucket's worth, rebuilt by the FSM.
    private static FsmHarness big() {
        var h = leader();

        for (var i = 0; i < PAIRS; i++) {
            var goal = desired(SliceGoal.ACTIVE, gen(1));

            h.seedQuietly(new SliceDesiredKey(nodeOf(i), artifactOf(i)), goal);
            h.seedQuietly(new SliceObservedKey(nodeOf(i), artifactOf(i)),
                          row(SliceObservedState.ACTIVE, 1L, gen(1), process(7)));
        }

        h.activate();

        return h;
    }

    // NT-78
    // Red when: an input does not increment inputs_total.
    @Test
    void everyInput_incrementsInputsTotal_exactlyOnce() {
        var h = leader();

        h.activate();
        h.deliver(new ClusterInput.SeamsRefreshed(h.seams));
        h.deliver(new ClusterInput.KvPut(new SliceTargetKey(ORDERS.base()),
                                         SliceTargetValue.sliceTargetValue(ORDERS.version(), 1, 1)));
        h.advance(31_000L);
        h.deliver(new ClusterInput.WitnessStale());
        assertThat(h.steps).isNotEmpty();
        for (var effects : h.steps) {
            assertThat(effects.stream()
                              .filter(e -> e instanceof ClusterEffect.Metric metric && metric.name()
                                                                                             .equals("aether_deployment_fsm_inputs_total"))
                              .count()).as("one inputs_total per step")
                      .isEqualTo(1);
        }

        for (var type : new String[]{"Activate", "Rebuild", "SeamsRefreshed", "KvPut", "Timer", "Reconcile", "Flush", "WitnessStale"}) {
            assertThat(h.metricTotal("aether_deployment_fsm_inputs_total",
                                     t -> type.equals(t.get("input")) && "applied".equals(t.get("result")))).as(type)
                      .isPositive();
        }
    }

    private static long scans(FsmHarness h) {
        return h.metricTotal("aether_deployment_fsm_scans_total", t -> true);
    }

    // NT-78
    // Red when: an ordinary input does a forEach over a store-wide index.
    @Test
    void ordinaryInputs_scanNothing_whileRebuildAndReconcileDo() {
        var h = big();

        assertThat(scans(h)).as("control: Rebuild and the first reconcile do scan").isGreaterThan(0L);
        assertThat(h.model().observed.size()).isEqualTo(PAIRS);
        var pair = pair(nodeOf(5), artifactOf(5));
        var next = row(SliceObservedState.ACTIVE, 2L, gen(1), process(7));
        var inputs = new ClusterInput[]{new ClusterInput.KvPut(new SliceObservedKey(pair.node(), pair.artifact()),
                                                               next), new ClusterInput.KvPut(new SliceDesiredKey(pair.node(),
                                                                                                                 pair.artifact()),
                                                                                             desired(SliceGoal.ACTIVE,
                                                                                                     gen(1))), new ClusterInput.KvPut(new SliceTargetKey(pair.artifact()
                                                                                                                                                             .base()),
                                                                                                                                      SliceTargetValue.sliceTargetValue(pair.artifact()
                                                                                                                                                                            .version(),
                                                                                                                                                                        1,
                                                                                                                                                                        1)), new ClusterInput.KvPut(new ArtifactJudgmentKey(pair.artifact()),
                                                                                                                                                                                                    new ArtifactJudgmentValue(false,
                                                                                                                                                                                                                              Map.of(pair.node(),
                                                                                                                                                                                                                                     1),
                                                                                                                                                                                                                              Map.of())), new ClusterInput.KvPut(new AppBlueprintKey(blueprintId("org.example:app:1.0.0")),
                                                                                                                                                                                                                                                                 blueprint("org.example:app:1.0.0",
                                                                                                                                                                                                                                                                           true,
                                                                                                                                                                                                                                                                           ATTEMPT,
                                                                                                                                                                                                                                                                           slice(pair.artifact(),
                                                                                                                                                                                                                                                                                 1,
                                                                                                                                                                                                                                                                                 1,
                                                                                                                                                                                                                                                                                 Set.of()))), new ClusterInput.KvRemove(new SliceObservedKey(pair.node(),
                                                                                                                                                                                                                                                                                                                                             pair.artifact()))};

        for (var input : inputs) {
            var before = scans(h);

            h.step(input);
            assertThat(scans(h)).as(input.toString().substring(0, 40)).isEqualTo(before);
        }

        var before = scans(h);

        h.step(new ClusterInput.Reconcile());
        assertThat(scans(h)).as("control: a reconcile pass scans").isGreaterThan(before);
    }

    // NT-78 (the audit)
    // Red when: the full observed scan runs on a tick other than every tenth (an orphan is found early), or never.
    @Test
    void theOrphanAudit_findsAnOrphanOnlyOnEveryTenthTick() {
        var h = active();
        var orphan = pair(CORE_1, ORDERS_V2);

        h.seed(new SliceTargetKey(ORDERS.base()),
               SliceTargetValue.sliceTargetValue(ORDERS.version(), 0, 0));
        h.drain();
        h.seed(new SliceDesiredKey(CORE_1, ORDERS_V2), desired(SliceGoal.ACTIVE, gen(1)));
        h.seed(new SliceObservedKey(CORE_1, ORDERS_V2), row(SliceObservedState.ACTIVE, 1L, gen(1), process(11)));
        h.drain();
        for (var tick = 1; tick <= 9; tick++) {
            h.advance(30_000L);
            assertThat(h.model().desired(orphan).map(d -> d.goal())).as("tick " + tick)
                      .isEqualTo(Option.some(SliceGoal.ACTIVE));
        }

        h.advance(30_000L);
        assertThat(h.model().desired(orphan).map(d -> d.goal())).as("tick 10: the audit")
                  .isEqualTo(Option.some(SliceGoal.UNLOADED));
    }
}
