// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// B13 / W-16 (a), MT-15, at the one place no booted-node test can see: `AetherNode.stop()`. The router is quiesced only after the node's `SUSPEND`s
/// have run, and that order lives in [DeploymentWiring#leaveQuorum], which the unit tests drive. What they cannot see is whether `stop()` goes
/// through it, so this is a text-level pin on the node's source: the router's quiesce appears exactly once in `AetherNode`, as the argument of
/// `deploymentWiring.leaveQuorum(`, and nowhere as a call of its own. It reports the length of the source it scanned, and a planted bare
/// `router.quiesce()` is its positive control.
///
/// #2688: the same sanctioned form requires the wait between PASSIVE and the quiesce to be the leader deactivation's delivery on the executor the
/// leader manager was given (the literal `"LeaderManager"` at both sites, so a one-sided rename that yields a fresh, empty executor is caught), the
/// deactivation to be recorded on the toggles' route, and the components' stops to run only in the continuation after `quorumLeft` (rc5's order:
/// quiesce, FSM2 close, then the components), never in the synchronous part of `stop()` before it.
class StopSequencePinTest {
    private static final Path NODE_SOURCE = Path.of("src", "main", "java", "org", "pragmatica", "aether", "node", "AetherNode.java");
    private static final Pattern QUIESCE = Pattern.compile("(?:::|\\.)quiesce\\b");
    private static final Pattern THROUGH_THE_WIRING = Pattern.compile("deploymentWiring\\s*\\.leaveQuorum\\(\\s*\\(\\)\\s*->\\s*router\\.route\\(ClusterStateNotification\\.passive\\(\\)\\)\\s*,"
                                                                      + "\\s*\\(\\)\\s*->\\s*leaderDeactivation\\s*\\.delivered\\(\\s*reactors\\.executor\\(\"LeaderManager\"\\)\\s*,"
                                                                      + "\\s*LEADER_DEACTIVATION_BOUND\\s*\\)\\s*,\\s*router::quiesce\\s*\\)");
    private static final Pattern INSTALLED = Pattern.compile("leaderManager\\(\\)\\s*\\.installReactExecutor\\(reactors\\.executor\\(\"LeaderManager\"\\)\\)");
    private static final Pattern LEADER_EXECUTOR = Pattern.compile("reactors\\.executor\\(\"LeaderManager\"\\)");
    private static final Pattern RECORDED = Pattern.compile("MessageRouter\\.Entry\\.route\\(LeaderNotification\\.LeaderChange\\.class,\\s*leaderDeactivation::onLeaderChange\\)");
    private static final Pattern CHAIN_HEAD = Pattern.compile("return quorumLeft\\.map\\(this::stopDeploymentWiring\\)\\s*\\.flatMap\\(\\(\\) -> stopNodeComponents\\(backupFlushed\\)\\)");
    private static final String STOP = "public Promise<Unit> stop() {";
    private static final String FSM2_CLOSE = "private Unit stopDeploymentWiring(Unit unit) {";
    private static final String COMPONENTS = "private Promise<Unit> stopNodeComponents(Promise<Unit> backupFlushed) {";
    private static final String COMPONENTS_END = "return backupFlushed.flatMap(() -> streamsStopped);";
    /// The whole stop, in the order the ruling fixes (route(passive) -> drained -> quiesce -> FSM2 close -> the remaining teardown ->
    /// backupFlushed -> streamsStopped -> NodeStopSequence), as the landmarks `stop()` must show, in this order, with nothing of the
    /// teardown among them. The FSM2 close and the components' statements are checked in their own methods below.
    private static final List<String> STOP_LANDMARKS = List.of("reactors.markStopping();",
                                                               "periodicTasks.cancel();",
                                                               "backupCoordinatorStop.run();",
                                                               "var backupFlushed = ",
                                                               "deploymentWiring.leaveQuorum(() -> router.route(ClusterStateNotification.passive()),",
                                                               "() -> leaderDeactivation.delivered(",
                                                               "router::quiesce);",
                                                               "return quorumLeft.map(this::stopDeploymentWiring)",
                                                               ".flatMap(() -> stopNodeComponents(backupFlushed))",
                                                               ".flatMap(() -> NodeStopSequence.run(NodeStopSequence.Steps.steps(this::releaseWatchesThenDeactivate,",
                                                               "managementServer.map(ManagementServer::stop)",
                                                               "appHttpServer::stop,",
                                                               "sliceInvoker::stop,",
                                                               "this::closeSharedResources,",
                                                               "this::shutdownStorage,",
                                                               "clusterNode::stop)))");

    /// The occurrences of `quiesce` in `source` and of the sanctioned form of the stop.
    private record Scan(long length, long quiesces, long sanctioned) {}

    private static Scan scan(String source) {
        return new Scan(source.length(), QUIESCE.matcher(source).results().count(), THROUGH_THE_WIRING.matcher(source).results().count());
    }

    private static long count(Pattern pattern, String source) {
        return pattern.matcher(source).results().count();
    }

    private static String code(String text) {
        return text.lines()
                   .filter(line -> !line.strip().startsWith("//"))
                   .collect(Collectors.joining(" "))
                   .replaceAll("\\s+", " ")
                   .strip();
    }

    private static String between(String source, String from, String to) {
        var start = source.indexOf(from);

        return code(source.substring(start, source.indexOf(to, start)));
    }

    /// `stop()` as code: from its head to the FSM2 close that follows it.
    private static String stopMethod(String source) {
        return between(source, STOP, FSM2_CLOSE);
    }

    private static String fsm2Close(String source) {
        return between(source, FSM2_CLOSE, COMPONENTS);
    }

    private static String componentStops(String source) {
        return between(source, COMPONENTS, COMPONENTS_END);
    }

    /// Every statement of the components' stops, derived from the method body, so a stop added there is covered without editing this test.
    private static List<String> teardownStatements(String source) {
        var body = componentStops(source);

        return Stream.of(body.substring(body.indexOf('{') + 1).split(";"))
                     .map(String::strip)
                     .filter(statement -> !statement.isEmpty())
                     .map(statement -> statement + ";")
                     .toList();
    }

    /// The teardown statements found in `stop()` itself, i.e. planted before (or anywhere around) the quiesce.
    private static List<String> misplaced(String source) {
        var stop = stopMethod(source);

        return teardownStatements(source).stream().filter(stop::contains).toList();
    }

    /// The landmarks of `stop()` that are missing or out of order: each must come after the previous one.
    private static List<String> outOfOrder(String source) {
        var stop = stopMethod(source);
        var broken = new ArrayList<String>();
        var from = 0;

        for (var landmark : STOP_LANDMARKS) {
            var at = stop.indexOf(landmark, from);

            if (at < 0) {
                broken.add(landmark);
            } else {
                from = at + landmark.length();
            }
        }
        return broken;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void theRouterIsQuiescedOnlyThroughTheWiringsLeaveQuorum() {
        var scan = scan(read(NODE_SOURCE));

        assertThat(scan.length()).as("the scan read the node's source").isGreaterThan(100_000L);
        assertThat(scan.sanctioned()).as("stop() hands PASSIVE, the leader deactivation's delivery and the quiesce to DeploymentWiring.leaveQuorum")
                                     .isEqualTo(1L);
        assertThat(scan.quiesces()).as("and the quiesce is not called anywhere else").isEqualTo(scan.sanctioned());
    }

    @Test
    void theDeliveryWaitedFor_isOnTheExecutorTheLeaderManagerWasGiven_andIsRecordedOnTheTogglesRoute() {
        var source = read(NODE_SOURCE);

        assertThat(count(INSTALLED, source)).as("the leader manager is given the \"LeaderManager\" executor").isEqualTo(1L);
        assertThat(count(LEADER_EXECUTOR, source)).as("the install and the stop name the same executor, and nothing else does").isEqualTo(2L);
        assertThat(count(RECORDED, source)).as("the deactivation is recorded on the LeaderChange route").isEqualTo(1L);
        assertThat(count(CHAIN_HEAD, source)).as("the promise stop() returns starts from quorumLeft").isEqualTo(1L);
    }

    @Test
    void theWholeStop_runsInTheRuledOrder_andNoTeardownStatementIsAmongItsSynchronousPart() {
        var source = read(NODE_SOURCE);

        assertThat(stopMethod(source)).as("stop() was found").contains("reactors.markStopping();");
        assertThat(teardownStatements(source)).as("the components' statements were derived (positive control)").hasSizeGreaterThan(20);
        assertThat(outOfOrder(source)).as("route(passive) -> drained -> quiesce -> FSM2 close -> teardown -> flushes -> NodeStopSequence: missing or out of order").isEmpty();
        assertThat(misplaced(source)).as("no teardown statement in stop() itself: all of them run after the quiesce, in stopNodeComponents").isEmpty();
    }

    /// A6: the FSM2 interpreters close in `stop()`, between the quiesce and the components. `Fsm2SystemTest.Stop` pins what `DeploymentWiring.stop()` closes; this
    /// pins that the node calls it (an emptied body leaves the FSM2 timers and interpreters running on a stopped node).
    @Test
    void theFsm2Close_isTheNodesStepBetweenTheQuiesceAndTheComponents() {
        var source = read(NODE_SOURCE);

        assertThat(fsm2Close(source)).isEqualTo(FSM2_CLOSE + " deploymentWiring.stop(); return unit; }");
    }

    /// The joins at the end of the components' stop: the backup's flush, then the sealer's (rc5's order), and nothing after them.
    @Test
    void theFlushes_areJoinedBackupFirstThenStreams_asTheLastStepOfTheComponents() {
        var source = read(NODE_SOURCE);
        var tail = code(source.substring(source.indexOf(COMPONENTS_END)));

        assertThat(count(Pattern.compile(Pattern.quote(COMPONENTS_END)), source)).isEqualTo(1L);
        assertThat(tail).startsWith(COMPONENTS_END + " }");
    }

    @Test
    void aBareQuiesce_plantedInTheStop_isFound() {
        var planted = "router.route(ClusterStateNotification.passive());\n router.quiesce();\n";
        var scan = scan(planted);

        assertThat(scan.sanctioned()).isZero();
        assertThat(scan.quiesces()).isNotEqualTo(scan.sanctioned());
    }

    @Test
    void theSanctionedForm_isRecognised() {
        var scan = scan("deploymentWiring.leaveQuorum(() -> router.route(ClusterStateNotification.passive()),\n () -> leaderDeactivation.delivered(reactors.executor(\"LeaderManager\"),\n LEADER_DEACTIVATION_BOUND),\n router::quiesce);");

        assertThat(scan.sanctioned()).isEqualTo(1L);
        assertThat(scan.quiesces()).isEqualTo(1L);
    }

    @Test
    void anUnboundedOrPreMadeWait_isNotTheSanctionedForm() {
        assertThat(scan("deploymentWiring.leaveQuorum(() -> router.route(ClusterStateNotification.passive()),\n Promise::unitPromise,\n router::quiesce);").sanctioned()).isZero();
        assertThat(scan("deploymentWiring.leaveQuorum(() -> router.route(ClusterStateNotification.passive()),\n () -> reactors.executor(\"LeaderManager\").drained(),\n router::quiesce);").sanctioned()).isZero();
    }

    private static String skeleton(String planted) {
        return STOP + " reactors.markStopping(); " + planted + " var quorumLeft = x; return quorumLeft.map(y); } " + FSM2_CLOSE + " deploymentWiring.stop(); return unit; } "
               + COMPONENTS + " controlLoop.stop(); metricsScheduler.stop(); " + COMPONENTS_END + " }";
    }

    @Test
    void aComponentStop_plantedBeforeTheQuiesce_isFound_whicheverOneItIs() {
        assertThat(misplaced(skeleton(""))).isEmpty();
        assertThat(misplaced(skeleton("controlLoop.stop();"))).containsExactly("controlLoop.stop();");
        assertThat(misplaced(skeleton("metricsScheduler.stop();"))).containsExactly("metricsScheduler.stop();");
    }

    @Test
    void aMissingOrReorderedLandmark_isFound() {
        var swapped = STOP + " " + String.join(" ", STOP_LANDMARKS.reversed()) + " } " + FSM2_CLOSE;
        var inOrder = STOP + " " + String.join(" ", STOP_LANDMARKS) + " } " + FSM2_CLOSE;

        assertThat(outOfOrder(inOrder)).isEmpty();
        assertThat(outOfOrder(swapped)).isNotEmpty();
    }
}
