// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceLoadingFailure.Intermittent.SliceNotInStore;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;

import static org.pragmatica.aether.deployment.node.machine.Chain.ChainKind;
import static org.pragmatica.aether.deployment.node.machine.Chain.local;
import static org.pragmatica.aether.deployment.node.machine.Chain.write;


/// The node transition table (spec R-3.4.3) as a pure function: from the goal, the committed observation and what the
/// local store holds to the chain that closes the gap. A committed row that is already partway along the chain (LOADING,
/// ACTIVATING, DEACTIVATING, UNLOADING) is resumed from where it is: the write the row already shows is not repeated.
sealed interface Plans {
    static Plan decide(NodeFsm fsm,
                       NodeFsmModel model,
                       Artifact artifact,
                       InstanceRecord rec,
                       SliceDesiredValue desired) {
        var row = Rows.current(fsm.config(), rec);
        var state = Rows.stateOf(row);

        if (isUnknown(state) || desired.goal() == SliceGoal.UNKNOWN) {
            return Plan.unknown();
        }

        return failedForGeneration(rec, row, desired)
               ? Plan.adopted()
               : forGoal(model, artifact, desired.goal(), state);
    }

    private static boolean isUnknown(Option<SliceObservedState> state) {
        return state.filter(s -> s == SliceObservedState.UNKNOWN)
                    .isPresent();
    }

    /// A FAILED row for the generation already executed starts no new chain: the leader's failure handling re-issues at a new
    /// generation (spec R-3.4.2, N15).
    private static boolean failedForGeneration(InstanceRecord rec,
                                               Option<SliceObservedValue> row,
                                               SliceDesiredValue desired) {
        return row.filter(value -> value.state() == SliceObservedState.FAILED)
                  .filter(value -> isExecuted(rec,
                                              value,
                                              desired.generation()))
                  .isPresent();
    }

    private static boolean isExecuted(InstanceRecord rec, SliceObservedValue row, Generation generation) {
        return row.forGeneration()
                  .equals(generation) || rec.executed(generation);
    }

    private static Plan forGoal(NodeFsmModel model,
                                Artifact artifact,
                                SliceGoal goal,
                                Option<SliceObservedState> state) {
        return switch (goal) {
            case LOADED -> state.fold(Plans::plannedLoad, s -> forLoaded(model, artifact, s));
            case ACTIVE -> state.fold(Plans::plannedRedeploy, s -> forActive(model, artifact, s));
            case UNLOADED, UNKNOWN -> state.fold(() -> forAbsentUnloaded(model, artifact), s -> forUnloaded(s));
        };
    }

    private static Plan forLoaded(NodeFsmModel model, Artifact artifact, SliceObservedState state) {
        return switch (state) {
            case UNLOADED, FAILED, UNLOADING -> plannedLoad();
            case LOADED -> Plan.satisfied();
            case LOADING -> Plan.run(ChainKind.LOAD, resume(loadSteps(), state), true);
            case ACTIVE -> backToLoaded(model, artifact, false);
            case ACTIVATING -> model.isLoaded(artifact)
                               ? backToLoaded(model, artifact, false)
                               : plannedLoad();
            case DEACTIVATING -> backToLoaded(model, artifact, true);
            case UNKNOWN -> Plan.unknown();
        };
    }

    /// N12 (slice gone: just the row) or N13 (the teardown chain).
    private static Plan backToLoaded(NodeFsmModel model, Artifact artifact, boolean resumed) {
        return model.isLoaded(artifact)
               ? Plan.run(ChainKind.DEACTIVATE, deactivateSteps(resumed), false)
               : Plan.run(ChainKind.SINGLE_WRITE, List.of(write(SliceObservedState.LOADED)), false);
    }

    private static Plan forActive(NodeFsmModel model, Artifact artifact, SliceObservedState state) {
        var loaded = model.isLoaded(artifact);

        return switch (state) {
            case UNLOADED, FAILED, UNLOADING -> plannedRedeploy();
            case LOADING -> Plan.run(ChainKind.REDEPLOY, concat(resume(loadSteps(), state), activateSteps()), true);
            case LOADED -> loaded
                           ? plannedActivation(activateSteps())
                           : notInStore(artifact);
            case ACTIVATING -> loaded
                               ? plannedActivation(resume(activateSteps(), state))
                               : notInStore(artifact);
            case ACTIVE -> loaded
                           ? Plan.satisfied()
                           : plannedRedeploy();
            case DEACTIVATING -> loaded
                                 ? plannedActivation(activateSteps())
                                 : plannedRedeploy();
            case UNKNOWN -> Plan.unknown();
        };
    }

    /// N5: the slice is not in the store. Typed intermittent, so an unload/activate crossing is not condemned (#916).
    private static Plan notInStore(Artifact artifact) {
        return Plan.fail(SliceNotInStore.sliceNotInStore(artifact.asString(), "activation"));
    }

    private static Plan forUnloaded(SliceObservedState state) {
        return switch (state) {
            case UNLOADED -> Plan.satisfied();
            case UNLOADING -> Plan.run(ChainKind.UNLOAD, unloadSteps(true), false);
            case UNKNOWN -> Plan.unknown();
            default -> Plan.run(ChainKind.UNLOAD, unloadSteps(false), false);
        };
    }

    private static Plan forAbsentUnloaded(NodeFsmModel model, Artifact artifact) {
        return model.isLoaded(artifact)
               ? Plan.run(ChainKind.UNLOAD, unloadSteps(false), false)
               : Plan.satisfied();
    }

    private static Plan plannedLoad() {
        return Plan.run(ChainKind.LOAD, loadSteps(), true);
    }

    private static Plan plannedRedeploy() {
        return Plan.run(ChainKind.REDEPLOY, concat(loadSteps(), activateSteps()), true);
    }

    private static Plan plannedActivation(List<Chain.Step> steps) {
        return Plan.run(ChainKind.ACTIVATE, steps, true);
    }

    /// N1: W LOADING, load, W LOADED.
    static List<Chain.Step> loadSteps() {
        return List.of(write(SliceObservedState.LOADING), local(LocalStep.LOAD), write(SliceObservedState.LOADED));
    }

    /// N4: W ACTIVATING, activate, bridge, legs, config, routes, then ONE batch of W ACTIVE with the endpoints, the legs and
    /// the ACQUIRE (G-D.11).
    static List<Chain.Step> activateSteps() {
        return List.of(write(SliceObservedState.ACTIVATING),
                       local(LocalStep.ACTIVATE),
                       local(LocalStep.REGISTER_BRIDGE),
                       local(LocalStep.BUILD_LEGS),
                       local(LocalStep.REGISTER_CONFIG),
                       local(LocalStep.PUBLISH_ROUTES),
                       local(LocalStep.DESCRIBE_ACTIVE),
                       write(SliceObservedState.ACTIVE));
    }

    /// R-3.4.7: the same registrations as N4, with no ACTIVATING row (the row still says ACTIVE) and no ACQUIRE.
    static List<Chain.Step> reactivateSteps() {
        return List.of(local(LocalStep.REGISTER_BRIDGE),
                       local(LocalStep.BUILD_LEGS),
                       local(LocalStep.REGISTER_CONFIG),
                       local(LocalStep.PUBLISH_ROUTES),
                       local(LocalStep.DESCRIBE_ACTIVE),
                       write(SliceObservedState.ACTIVE));
    }

    /// R-6.0 step 5: the lost ACTIVE batch rebuilt from the current mirror.
    static List<Chain.Step> redriveActiveSteps() {
        return List.of(local(LocalStep.DESCRIBE_ACTIVE), write(SliceObservedState.ACTIVE));
    }

    /// N13: one batch of W DEACTIVATING with the removes and the RELEASE, unpublish, deactivate, W LOADED.
    static List<Chain.Step> deactivateSteps(boolean resumed) {
        return resumed
               ? List.of(local(LocalStep.UNPUBLISH), local(LocalStep.DEACTIVATE), write(SliceObservedState.LOADED))
               : List.of(local(LocalStep.PREPARE_TEARDOWN),
                         write(SliceObservedState.DEACTIVATING),
                         local(LocalStep.UNPUBLISH),
                         local(LocalStep.DEACTIVATE),
                         write(SliceObservedState.LOADED));
    }

    /// N16: one batch of W UNLOADING with the removes, unpublish, unload (failure still writes UNLOADED, B-147), W UNLOADED.
    static List<Chain.Step> unloadSteps(boolean resumed) {
        return resumed
               ? List.of(local(LocalStep.UNPUBLISH), local(LocalStep.UNLOAD), write(SliceObservedState.UNLOADED))
               : List.of(local(LocalStep.PREPARE_TEARDOWN),
                         write(SliceObservedState.UNLOADING),
                         local(LocalStep.UNPUBLISH),
                         local(LocalStep.UNLOAD),
                         write(SliceObservedState.UNLOADED));
    }

    /// Drops the leading write when the committed row already shows its state.
    static List<Chain.Step> resume(List<Chain.Step> steps, SliceObservedState committed) {
        return steps.getFirst() instanceof Chain.Step.Write first && first.state() == committed
               ? steps.subList(1, steps.size())
               : steps;
    }

    private static List<Chain.Step> concat(List<Chain.Step> head, List<Chain.Step> tail) {
        var all = new ArrayList<>(head);

        all.addAll(tail);

        return all;
    }

    record unused() implements Plans {}
}
