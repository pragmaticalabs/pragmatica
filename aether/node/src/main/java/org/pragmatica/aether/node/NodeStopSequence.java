// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// The whole of `AetherNode.stop()` after its synchronous prologue, as one ordered list of steps (#1612, #3565).
///
/// One invariant: once the stop begins, every step runs exactly once, in order, whatever any earlier step returned.
/// A failure never skips a later step; it is logged where it happens and the stop's own result is the FIRST failure
/// (the caller learns the stop did not complete cleanly, and the later failures are in the log). So a failed leave of
/// the quorum, a failed HTTP listener stop and a failed shared-resource close are one case, not three.
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

    /// Runs every step in order and resolves with the first failure, or with success when none failed.
    static Promise<Unit> run(Steps steps) {
        return runFrom(steps.ordered(), Option.empty()).flatMap(NodeStopSequence::outcome);
    }

    /// Runs `remaining` in order, carrying the first failure so far. The next step starts when this one has settled,
    /// whether it succeeded or failed: this is where a failure stops being able to skip anything.
    private static Promise<Option<Cause>> runFrom(List<Step> remaining, Option<Cause> firstFailure) {
        return remaining.isEmpty()
               ? Promise.success(firstFailure)
               : settle(remaining.getFirst(),
                        remaining.subList(1, remaining.size()),
                        firstFailure);
    }

    private static Promise<Option<Cause>> settle(Step step, List<Step> rest, Option<Cause> firstFailure) {
        return step.action()
                   .get()
                   .fold(result -> runFrom(rest,
                                           firstFailure.orElse(failureOf(step.name(),
                                                                         result))));
    }

    /// The failure of a settled step, logged; empty when it succeeded.
    private static Option<Cause> failureOf(String name, Result<Unit> result) {
        return result.fold(cause -> logged(name, cause), _ -> Option.empty());
    }

    private static Option<Cause> logged(String name, Cause cause) {
        LOG.warn("{} did not stop cleanly; continuing node shutdown: {}", name, cause.message());

        return Option.present(cause);
    }

    private static Promise<Unit> outcome(Option<Cause> firstFailure) {
        return firstFailure.fold(Promise::unitPromise, Cause::promise);
    }

    record unused() implements NodeStopSequence {}
}
