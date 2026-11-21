// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.testlogging.LogCapture;

import static org.assertj.core.api.Assertions.assertThat;

/// #3565 (generalising #1612): once a node's stop begins, every step runs exactly once, in order, whatever any
/// earlier step returned, and the stop's result is the first failure. Before, a failed `leaveQuorum` (and, before
/// #1612, a failed HTTP listener stop) ended the `flatMap` chain, so the FSM2 close, the components' stops, the
/// listeners and the cluster node never ran.
class NodeStopSequenceTest {
    private static final String LOGGER_NAME = NodeStopSequence.class.getName();
    private static final Cause STOP_TIMED_OUT = Causes.cause("listener termination timed out");
    private static final Cause QUORUM_NOT_LEFT = Causes.cause("leader deactivation not delivered");
    private static final Cause LATER_FAILURE = Causes.cause("later step failed too");
    private static final List<String> EVERY_STEP = List.of("leaveQuorum",
                                                           "deploymentWiring",
                                                           "nodeComponents",
                                                           "deactivate",
                                                           "management",
                                                           "appHttp",
                                                           "sliceInvoker",
                                                           "sharedResources",
                                                           "storage",
                                                           "clusterNode");

    private final List<String> ran = new CopyOnWriteArrayList<>();
    private LogCapture capture;

    @BeforeEach
    void captureLog() {
        capture = LogCapture.capture(LOGGER_NAME, Level.TRACE, false);
    }

    @AfterEach
    void releaseLog() {
        capture.close();
    }

    @Test
    void run_everyStepSucceeds_runsEachOnceInOrder_andSucceeds() {
        var outcome = NodeStopSequence.run(steps(Map.of())).await();

        assertThat(outcome.isSuccess()).isTrue();
        assertThat(ran).containsExactlyElementsOf(EVERY_STEP);
        assertThat(capture.messages()).isEmpty();
    }

    /// The ticket: the head of the chain failing used to skip the FSM2 close, the components, the listeners and the cluster node.
    @Test
    void run_leaveQuorumFails_runsEveryLaterStepInOrder_andReportsTheFailure() {
        var outcome = NodeStopSequence.run(steps(Map.of("leaveQuorum", QUORUM_NOT_LEFT))).await();

        assertThat(ran).containsExactlyElementsOf(EVERY_STEP);
        assertThat(failureOf(outcome)).isEqualTo(QUORUM_NOT_LEFT.message());
        assertThat(capture.messages()).containsExactly("Leave quorum did not stop cleanly; continuing node shutdown: " + QUORUM_NOT_LEFT.message());
    }

    /// Whichever step fails, no other step is skipped or repeated, and the failure is the stop's outcome.
    @Test
    void run_anyOneStepFails_everyStepStillRunsExactlyOnce_andTheStopReportsThatFailure() {
        for (var failing : EVERY_STEP) {
            ran.clear();

            var outcome = NodeStopSequence.run(steps(Map.of(failing, STOP_TIMED_OUT)))
                                          .await();

            // storage and deploymentWiring cannot fail (Supplier<Unit>): their failure entry is never consulted
            var infallible = failing.equals("storage") || failing.equals("deploymentWiring");

            assertThat(ran).as("steps run with %s failing", failing).containsExactlyElementsOf(EVERY_STEP);
            assertThat(outcome.isFailure()).as("outcome with %s failing", failing).isEqualTo(!infallible);
        }
    }

    @Test
    void run_aMiddleStepFails_theStepsAfterItRun_andTheStopResultCarriesTheFailure() {
        var outcome = NodeStopSequence.run(steps(Map.of("nodeComponents", STOP_TIMED_OUT))).await();

        assertThat(ran.subList(ran.indexOf("nodeComponents") + 1, ran.size())).containsExactly("deactivate",
                                                                                                "management",
                                                                                                "appHttp",
                                                                                                "sliceInvoker",
                                                                                                "sharedResources",
                                                                                                "storage",
                                                                                                "clusterNode");
        assertThat(failureOf(outcome)).isEqualTo(STOP_TIMED_OUT.message());
    }

    /// #1612: a listener that fails to stop is logged, does not skip what follows, and (since #3565) is the outcome.
    @Test
    void run_managementServerStopFails_runsEveryLaterStep_logsTheFailure_andReportsIt() {
        var outcome = NodeStopSequence.run(steps(Map.of("management", STOP_TIMED_OUT))).await();

        assertThat(ran).containsExactlyElementsOf(EVERY_STEP);
        assertThat(failureOf(outcome)).isEqualTo(STOP_TIMED_OUT.message());
        assertThat(capture.entries()).anyMatch(entry -> entry.level() == Level.WARN
                                                       && entry.message()
                                                               .equals("Management server did not stop cleanly; continuing node shutdown: "
                                                                       + STOP_TIMED_OUT.message()));
    }

    @Test
    void run_appHttpServerStopFails_runsEveryLaterStep_andLogsTheFailure() {
        var outcome = NodeStopSequence.run(steps(Map.of("appHttp", STOP_TIMED_OUT))).await();

        assertThat(outcome.isFailure()).isTrue();
        assertThat(ran).containsExactlyElementsOf(EVERY_STEP);
        assertThat(capture.messages()).anyMatch(message -> message.startsWith("App HTTP server did not stop cleanly"));
    }

    /// #903: the shared resource scope closes after the slice invoker and before storage and the cluster node, and
    /// its failure does not skip them.
    @Test
    void run_sharedResourceCloseFails_runsEveryLaterStep_andLogsTheFailure() {
        var outcome = NodeStopSequence.run(steps(Map.of("sharedResources", STOP_TIMED_OUT))).await();

        assertThat(outcome.isFailure()).isTrue();
        assertThat(ran).containsExactlyElementsOf(EVERY_STEP);
        assertThat(capture.messages()).anyMatch(message -> message.startsWith("Shared resource scope did not stop cleanly"));
    }

    @Test
    void run_clusterNodeStopFails_reportsTheFailure() {
        assertThat(failureOf(NodeStopSequence.run(steps(Map.of("clusterNode", STOP_TIMED_OUT))).await())).isEqualTo(STOP_TIMED_OUT.message());
    }

    /// The first failure is the stop's outcome; a later one is not lost, it is logged.
    @Test
    void run_severalStepsFail_theResultIsTheFirst_andEveryFailureIsLogged() {
        var outcome = NodeStopSequence.run(steps(Map.of("leaveQuorum", QUORUM_NOT_LEFT, "clusterNode", LATER_FAILURE))).await();

        assertThat(ran).containsExactlyElementsOf(EVERY_STEP);
        assertThat(failureOf(outcome)).isEqualTo(QUORUM_NOT_LEFT.message());
        assertThat(capture.messages()).containsExactly("Leave quorum did not stop cleanly; continuing node shutdown: " + QUORUM_NOT_LEFT.message(),
                                                       "Cluster node did not stop cleanly; continuing node shutdown: " + LATER_FAILURE.message());
    }

    /// No step starts before the previous one has settled, a failed one included.
    @Test
    void run_aStepSettlesLater_theNextOneWaitsForIt_thenTheRestRun() {
        var quorum = Promise.<Unit> promise();
        var steps = NodeStopSequence.Steps.steps(record("leaveQuorum", quorum),
                                                 storage("deploymentWiring"),
                                                 record("nodeComponents", Promise.unitPromise()),
                                                 record("deactivate", Promise.unitPromise()),
                                                 record("management", Promise.unitPromise()),
                                                 record("appHttp", Promise.unitPromise()),
                                                 record("sliceInvoker", Promise.unitPromise()),
                                                 record("sharedResources", Promise.unitPromise()),
                                                 storage("storage"),
                                                 record("clusterNode", Promise.unitPromise()));
        var stop = NodeStopSequence.run(steps);

        assertThat(ran).containsExactly("leaveQuorum");

        quorum.fail(QUORUM_NOT_LEFT);

        assertThat(ran).containsExactlyElementsOf(EVERY_STEP);
        assertThat(failureOf(stop.await())).isEqualTo(QUORUM_NOT_LEFT.message());
    }

    /// The failure's message, or `success` when the stop succeeded.
    private static String failureOf(Result<Unit> outcome) {
        return outcome.fold(Cause::message, _ -> "success");
    }

    private NodeStopSequence.Steps steps(Map<String, Cause> failing) {
        return NodeStopSequence.Steps.steps(record("leaveQuorum", outcome(failing, "leaveQuorum")),
                                            storage("deploymentWiring"),
                                            record("nodeComponents", outcome(failing, "nodeComponents")),
                                            record("deactivate", outcome(failing, "deactivate")),
                                            record("management", outcome(failing, "management")),
                                            record("appHttp", outcome(failing, "appHttp")),
                                            record("sliceInvoker", outcome(failing, "sliceInvoker")),
                                            record("sharedResources", outcome(failing, "sharedResources")),
                                            storage("storage"),
                                            record("clusterNode", outcome(failing, "clusterNode")));
    }

    private static Promise<Unit> outcome(Map<String, Cause> failing, String step) {
        return failing.containsKey(step)
               ? failing.get(step).promise()
               : Promise.unitPromise();
    }

    private Supplier<Promise<Unit>> record(String step, Promise<Unit> outcome) {
        return () -> {
            ran.add(step);
            return outcome;
        };
    }

    private Supplier<Unit> storage(String step) {
        return () -> {
            ran.add(step);
            return Unit.unit();
        };
    }
}
