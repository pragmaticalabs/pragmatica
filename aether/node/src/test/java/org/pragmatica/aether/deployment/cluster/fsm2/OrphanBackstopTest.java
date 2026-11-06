// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ClusterController.ControlDecisions;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.ControlLoop.ControlLoopAdapter;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/// #2713 R3g: the fsm2 orphan-target removal (#2771) removes ONLY the `SliceTargetKey`. The requirements of that base are removed by the
/// `RequirementCombiner` backstop, reached by the store's `ValueRemove` notification through `ControlLoop.onSliceTargetRemove`.
///
/// The seam is the real chain, nothing stubbed between its links: the fsm2 reconcile derives the removal, the harness applies the
/// submitted mutation to the real `KVStore`, the store notifies a REAL `ControlLoop` (routed as `AetherNode` routes the remove of a
/// `SliceTargetKey`), and the loop's own combiner removes the requirements through a cluster node that applies into the same store.
/// Stubbed: the metrics collector and the autoscaler (never reached), the consensus (the node applies at once).
class OrphanBackstopTest {
    private static final String APP = "org.example:app:1.0.0";
    private static final SliceTargetKey TARGET = new SliceTargetKey(ORDERS.base());
    private static final SliceRequirementKey REACTIVE = SliceRequirementKey.sliceRequirementKey(ORDERS.base(), RequirementKind.REACTIVE);
    private static final SliceRequirementKey OPERATOR = SliceRequirementKey.sliceRequirementKey(ORDERS.base(), RequirementKind.OPERATOR);

    private ControlLoopAdapter loop;

    @AfterEach
    void stopLoop() {
        if (loop != null) {
            loop.combiner().deactivate();
            loop.deactivate();
        }
    }

    // The loop is wired and its combiner activated BEFORE the requirements are seeded: activation re-evaluates every committed requirement
    // base on the scheduler thread, and an evaluation that ran late would remove the requirements without the routed remove event.
    // Red when: the combiner does not remove the requirements of a base whose target is gone (the backstop evaluation drops its orphan
    // removal), or the loop does not hand a removed target to its combiner (onSliceTargetRemove stops routing).
    @Test
    void fsm2OrphanTargetRemoval_leavesNoRequirementOfTheBase() {
        var h = orphanAtRebuild();

        wireLoop(h);
        h.seed(REACTIVE, requirement());
        h.seed(OPERATOR, requirement());
        h.drain();
        assertThat(requirementsOfBase(h)).as("control: both requirements are committed before the removal")
                                         .containsExactlyInAnyOrder(REACTIVE, OPERATOR);

        h.advance(h.settings.timing().tick());
        assertThat(h.store.get(TARGET).isPresent()).as("control: tick 1 observes the orphan, the target is kept").isTrue();
        assertThat(requirementsOfBase(h)).as("control: nothing removed the requirements while the target stands")
                                         .hasSize(2);
        h.advance(h.settings.timing().tick());

        assertThat(h.submits(SubmitKind.ORPHAN_TARGET)).as("the fsm2 step submitted exactly one removal").hasSize(1);
        assertThat(h.submits(SubmitKind.ORPHAN_TARGET).getFirst().transaction().mutations()).as("and that removal names only the target")
                                                                                   .extracting(mutation -> mutation.key())
                                                                                   .containsExactly(TARGET);
        assertThat(h.store.get(TARGET)).as("the orphan target is removed").isEqualTo(Option.none());
        assertThat(await(() -> requirementsOfBase(h).isEmpty())).as("the backstop removed every requirement of the base: left %s",
                                                                    requirementsOfBase(h))
                                                                .isTrue();
    }

    /// A committed target owned by an absent blueprint, observed once by the new leader's rebuild (the removal batch was lost).
    private static FsmHarness orphanAtRebuild() {
        var h = active();

        publish(h, blueprint(APP, false, ATTEMPT, slice(ORDERS, 1, 1, Set.of())));
        h.policy = FsmHarness.SettlePolicy.HOLD;
        h.seedRemove(new AppBlueprintKey(blueprintId(APP)));
        h.drain();
        h.held.clear();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.deliver(new ClusterInput.Deactivate());
        h.commitLeader(FsmHarness.SELF, 3L);
        h.drain();
        h.activate();
        assertThat(h.store.get(TARGET).isPresent()).as("control: the orphan is committed at the rebuild").isTrue();

        return h;
    }

    /// A real control loop over the harness's store, its combiner active (this node leads), a `SliceTargetKey` remove routed to it.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void wireLoop(FsmHarness h) {
        ClusterController controller = _ -> Promise.success(ControlDecisions.controlDecisions());

        loop = (ControlLoopAdapter) ControlLoop.controlLoop(FsmHarness.SELF,
                                                            controller,
                                                            collector(),
                                                            Option.none(),
                                                            applyingNode(h.store),
                                                            h.store,
                                                            TimeSpan.timeSpan(5_000).millis(),
                                                            ControllerConfig.DEFAULT,
                                                            _ -> {});
        h.router.addRoute(ValueRemove.class, (Consumer<ValueRemove>) this::routeTargetRemove);
        loop.combiner().activate();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void routeTargetRemove(ValueRemove remove) {
        if (remove.cause().key() instanceof SliceTargetKey) {
            loop.onSliceTargetRemove(remove);
        }
    }

    private static List<AetherKey> requirementsOfBase(FsmHarness h) {
        return List.of(REACTIVE, OPERATOR).stream()
                   .filter(key -> h.store.get(key).isPresent())
                   .map(key -> (AetherKey) key)
                   .toList();
    }

    /// Instances equal to the target's own (1), so the combiner finds the target settled and writes nothing while it stands.
    private static SliceRequirementValue requirement() {
        return SliceRequirementValue.sliceRequirementValue(1, "r3g", Option.none(), 0L);
    }

    private static boolean await(BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + 5_000;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            pause();
        }

        return condition.getAsBoolean();
    }

    private static void pause() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// The loop never reads its metrics collector on this path.
    private static ClusterSyncCollector collector() {
        return (ClusterSyncCollector) Proxy.newProxyInstance(OrphanBackstopTest.class.getClassLoader(),
                                                             new Class<?>[]{ClusterSyncCollector.class},
                                                             (_, _, _) -> null);
    }

    /// A cluster node whose every submitted batch commits at once into the real store.
    private static ClusterNode<KVCommand<AetherKey>> applyingNode(KVStore<AetherKey, AetherValue> store) {
        return new ClusterNode<>() {
            @Override
            @SuppressWarnings("unchecked")
            public synchronized <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
                return Promise.success((List<R>) store.<Object> process(store.createBatch(batch)));
            }

            @Override public NodeId self() {return FsmHarness.SELF;}

            @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException("unused");}

            @Override public Promise<Unit> start() {return Promise.unitPromise();}

            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        };
    }
}
