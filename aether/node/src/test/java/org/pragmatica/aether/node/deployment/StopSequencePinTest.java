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
import java.util.List;
import java.util.regex.Pattern;
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
    private static final Pattern CHAIN_HEAD = Pattern.compile("return quorumLeft\\.map\\(this::stopDeploymentWiring\\)\\s*\\.flatMap\\(this::stopNodeComponents\\)");
    private static final String STOP = "public Promise<Unit> stop() {";
    private static final String CONTINUATION = "return quorumLeft";
    private static final String COMPONENTS = "private Promise<Unit> stopNodeComponents(Unit unit) {";
    private static final String COMPONENTS_END = "return streamsStopped;";
    private static final List<String> TEARDOWN = List.of("controlLoop.stop();",
                                                         "streamConsumerManager.stop();",
                                                         "streamSegmentSealer.stopSealed(",
                                                         "discoveryProvider.onPresent(this::deregisterFromDiscovery);");

    /// The occurrences of `quiesce` in `source` and of the sanctioned form of the stop.
    private record Scan(long length, long quiesces, long sanctioned) {}

    private static Scan scan(String source) {
        return new Scan(source.length(), QUIESCE.matcher(source).results().count(), THROUGH_THE_WIRING.matcher(source).results().count());
    }

    private static long count(Pattern pattern, String source) {
        return pattern.matcher(source).results().count();
    }

    /// The synchronous part of `stop()`: from its head to the chain that starts at `quorumLeft`.
    private static String synchronousPart(String source) {
        var start = source.indexOf(STOP);

        return source.substring(start, source.indexOf(CONTINUATION, start));
    }

    private static String componentStops(String source) {
        var start = source.indexOf(COMPONENTS);

        return source.substring(start, source.indexOf(COMPONENTS_END, start));
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
    void theComponentsStop_onlyAfterTheQuiesce() {
        var source = read(NODE_SOURCE);
        var synchronous = synchronousPart(source);
        var components = componentStops(source);

        assertThat(synchronous).as("the synchronous part of stop() was found").contains("reactors.markStopping();");
        for (var stop : TEARDOWN) {
            assertThat(synchronous).as("not before the quiesce: %s", stop).doesNotContain(stop);
            assertThat(components).as("in the continuation after quorumLeft: %s", stop).contains(stop);
        }
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

    @Test
    void aComponentStop_plantedBeforeTheQuiesce_isFound() {
        var planted = STOP + " reactors.markStopping(); controlLoop.stop(); var quorumLeft = x; " + CONTINUATION + ".map(y); } " + COMPONENTS + " " + COMPONENTS_END;

        assertThat(synchronousPart(planted)).contains(TEARDOWN.getFirst());
        assertThat(componentStops(planted)).doesNotContain(TEARDOWN.getFirst());
    }
}
