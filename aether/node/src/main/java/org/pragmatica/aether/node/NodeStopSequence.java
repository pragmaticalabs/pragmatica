// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.pragmatica.aether.node.StopReport.StepFailure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The whole of `AetherNode.stop()` after its synchronous prologue, as one ordered list of steps (#1612, #3565).
///
/// One invariant: once the stop begins, every step runs exactly once, in order, whatever any earlier step returned.
/// A failure never skips a later step; it is logged where it happens and recorded, and the stop's result is a [StopReport]
/// carrying EVERY failure in step order. The stop SUCCEEDS with it once every step settled: a failed step means "stopped, with
/// failures", never "did not stop". So a failed leave of the quorum, a failed HTTP listener stop and a failed shared-resource
/// close are one case, not three. The result fails only when the sequence cannot complete; no returned step failure causes that.
///
/// Each step is a `Supplier`, so no step starts before the previous one has settled. The sequence can be tested
/// without assembling a node.
///
/// Not covered: a step that THROWS (a synchronous step is total by contract; an exception is not a returned
/// failure and ends the run at that step).
sealed interface NodeStopSequence {
    Logger LOG = LoggerFactory.getLogger(NodeStopSequence.class);

    /// One step of the stop: the label its failure is logged under, and the action.
    record Step(String name, Supplier<Promise<Unit>> action) {
        static Step step(String name, Supplier<Promise<Unit>> action) {
            return new Step(name, action);
        }

        /// A step that cannot fail (the FSM2 close, the storage drain), as an action of the list.
        static Step completing(String name, Supplier<Unit> action) {
            return step(name,
                        () -> Promise.success(action.get()));
        }
    }

    /// The steps, in order. `leaveQuorum` is the node leaving the quorum (PASSIVE, the leader-deactivation wait, the
    /// router's quiesce); `deploymentWiring` the FSM2 close after it; `nodeComponents` the components' stops and the
    /// backup and sealer flushes (rc5's order, #2688); then the deployment deactivation, the listeners, the slice
    /// invoker, `sharedResources` (#903, closed once the slice invoker has stopped), `storage` and the cluster node.
    /// `deploymentWiring` and `storage` are infallible by design (see `AetherNode.stopDeploymentWiring`,
    /// `AetherNode.shutdownStorage`).
    record Steps(Supplier<Promise<Unit>> leaveQuorum,
                 Supplier<Unit> deploymentWiring,
                 Supplier<Promise<Unit>> nodeComponents,
                 Supplier<Promise<Unit>> deactivateDeployment,
                 Supplier<Promise<Unit>> managementServer,
                 Supplier<Promise<Unit>> appHttpServer,
                 Supplier<Promise<Unit>> sliceInvoker,
                 Supplier<Promise<Unit>> sharedResources,
                 Supplier<Unit> storage,
                 Supplier<Promise<Unit>> clusterNode) {
        static Steps steps(Supplier<Promise<Unit>> leaveQuorum,
                           Supplier<Unit> deploymentWiring,
                           Supplier<Promise<Unit>> nodeComponents,
                           Supplier<Promise<Unit>> deactivateDeployment,
                           Supplier<Promise<Unit>> managementServer,
                           Supplier<Promise<Unit>> appHttpServer,
                           Supplier<Promise<Unit>> sliceInvoker,
                           Supplier<Promise<Unit>> sharedResources,
                           Supplier<Unit> storage,
                           Supplier<Promise<Unit>> clusterNode) {
            return new Steps(leaveQuorum,
                             deploymentWiring,
                             nodeComponents,
                             deactivateDeployment,
                             managementServer,
                             appHttpServer,
                             sliceInvoker,
                             sharedResources,
                             storage,
                             clusterNode);
        }

        /// The one ordering of the stop.
        List<Step> ordered() {
            return List.of(Step.step("Leave quorum", leaveQuorum),
                           Step.completing("Deployment wiring", deploymentWiring),
                           Step.step("Node components", nodeComponents),
                           Step.step("Deployment deactivation", deactivateDeployment),
                           Step.step("Management server", managementServer),
                           Step.step("App HTTP server", appHttpServer),
                           Step.step("Slice invoker", sliceInvoker),
                           Step.step("Shared resource scope", sharedResources),
                           Step.completing("Storage", storage),
                           Step.step("Cluster node", clusterNode));
        }
    }

        /// Runs every step in order and resolves with the report of what failed: [StopReport#clean] when nothing did,
    /// otherwise every failure, in step order. It never fails for a step's returned failure.
    static Promise<StopReport> run(Steps steps) {
        return runFrom(steps.ordered(), List.of()).map(StopReport::of);
    }

    /// Runs `remaining` in order, carrying the failures so far. The next step starts when this one has settled,
    /// whether it succeeded or failed: this is where a failure stops being able to skip anything.
    private static Promise<List<StepFailure>> runFrom(List<Step> remaining, List<StepFailure> failures) {
        return remaining.isEmpty()
               ? Promise.success(failures)
               : settle(remaining.getFirst(),
                        remaining.subList(1, remaining.size()),
                        failures);
    }

    private static Promise<List<StepFailure>> settle(Step step, List<Step> rest, List<StepFailure> failures) {
        return step.action()
                   .get()
                   .fold(result -> runFrom(rest,
                                           withOutcomeOf(step.name(), result, failures)));
    }

    /// `failures` plus the failure of a settled step, logged; `failures` itself when it succeeded.
    private static List<StepFailure> withOutcomeOf(String name, Result<Unit> result, List<StepFailure> failures) {
        return result.fold(cause -> appended(failures, logged(name, cause)),
                           _ -> failures);
    }

    private static List<StepFailure> appended(List<StepFailure> failures, StepFailure failure) {
        return Stream.concat(failures.stream(), Stream.of(failure))
                     .toList();
    }

    private static StepFailure logged(String name, Cause cause) {
        LOG.warn("{} did not stop cleanly; continuing node shutdown: {}", name, cause.message());

        return StepFailure.stepFailure(name, cause);
    }

    record unused() implements NodeStopSequence {}
}
