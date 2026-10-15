// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.NodeDeploymentManager;
import org.pragmatica.aether.deployment.node.fsm.ActivationWriteFault;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentContext;
import org.pragmatica.aether.deployment.node.fsm.NodeDeploymentState;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerAssignmentKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerAssignmentValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #1117 tracer T (deployment-FSM rewrite, phase 3, design §13.1). The scenario is the
/// [DeclarativeStreamConsumerTest] shape: a declarative stream-consumer blueprint on five nodes. The property is that
/// the blueprint's outcome reading "deployed", the cluster deployment map and every node's own view of its instance
/// AGREE, and that nothing papers over a disagreement late.
///
/// **What makes it red on the legacy engine.** A node's ACTIVE is one consensus write that nothing acknowledges except
/// its own commit. When that write's submission goes unanswered, the instance stays ROUTING in both the committed map
/// and the node's local view until either the write's own 30 s consensus timeout resubmits it or the 30 s ROUTING
/// remediation timer re-drives it, while the leader's outcome already reads SUCCEEDED (first instance per slice). The
/// late ACTIVE then moves the stream-consumer assignment of the partition that node owns. The natural trigger is rare
/// (about 1 in 7 build-host runs), so the tracer injects it: [ActivationWriteFault], a test-only fault inert unless
/// armed, leaves the FIRST submission of every ACTIVE write by ONE node unanswered; retries go through. The node is
/// the HRW owner of `consumer-events` partition 0, so its late ACTIVE has a partition to move.
///
/// **Control.** `-Dt1117.inject=false` runs the same test with the fault disarmed. A run that armed the fault but in
/// which it never fired fails as SETUP, never as a tracer verdict; so does a control run in which it fired.
///
/// **Assertions** (X = [#GRACE], a guess, 10 s):
///   - (a) no instance is non-ACTIVE, in the map or in its node's local view, more than X after the outcome reads
///     SUCCEEDED;
///   - (b) no stuck-ROUTING/ACTIVATING remediation fires (the legacy "forced" transition, which since #2148 adopts or
///     re-drives instead of writing a bare ACTIVE, and logs `still <STATE> locally after`);
///   - (c) no consumer assignment moves later than X after the outcome reads SUCCEEDED (a move inside X is the normal
///     activation skew between instances, which the control shows);
///   - (d) the map reaches all-ACTIVE and agrees with every node's local view, and no node's map and local states
///     disagree for longer than X.
///
/// **Both engines, one class** (phase-3 design 13.1; `-Dt1117.engine=legacy|fsm2`, default legacy). Under FSM2 the cluster is constructed with
/// `DeploymentEngine.FSM2` and the same fault is consulted at FSM2's submission point (the first ACTIVE submission of the target, `attempt == 1`,
/// `probe == 0`). What changes in the assertions:
///   - the map view is the committed `SliceObservedKey` row of each node; the local view is the node's SERVING SURFACE (the slice's bridge is
///     registered), per the spec's O1 ruling, falling back to the node FSM's own instance state while the bridge is absent;
///   - (b) reads the `write-probe-resubmit` / `write-confirm-timeout` sensor lines and the `submissions_total` arms instead of the legacy remediation
///     line, and every WARN of the deployment FSM loggers and every operator warning must be on `fsm2/expected-warns-t1117.txt` (design 9.5 O6);
///   - (c) is NOT DETERMINABLE under FSM2 in phase 3: the stream-consumer reader (`StreamConsumerManager`, fed by the legacy `DeploymentMap`) is not
///     migrated until phase 4a, so no assignment exists to move. It is reported, never faked.
///
/// The node-local view is read in-JVM from the node's deployment FSM. No production accessor exposes it, so the read
/// goes through two reflective hops ([#localStates]); that is test-only coupling to the legacy engine's shape, which
/// is what this tracer measures.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Issue1117ReadinessTracerTest {
    private static final int BASE_PORT = 26000;
    private static final int BASE_MGMT_PORT = 26100;
    private static final int BASE_APP_HTTP_PORT = 26200;
    private static final int NODES = 5;
    private static final int INSTANCES = 5;

    /// X in the tracer's assertions `[guess: 10 s]`.
    private static final Duration GRACE = Duration.ofSeconds(10);
    /// How long the run keeps sampling after the outcome reads SUCCEEDED: past the 30 s ROUTING timer and the 30 s
    /// consensus timeout of an unanswered write, plus time for the assignment writer to react to the late ACTIVE.
    private static final Duration OBSERVE_AFTER_SUCCEEDED = Duration.ofSeconds(75);
    /// Hard bound on the whole sampling run, from the deploy.
    private static final Duration SAMPLING_CAP = Duration.ofSeconds(240);
    private static final Duration SAMPLE_INTERVAL = Duration.ofMillis(200);
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);

    private static final boolean INJECT = Boolean.parseBoolean(System.getProperty("t1117.inject", "true"));
    /// `-Dt1117.engine=fsm2` runs the cluster on the FSM2 engine; the default keeps the LEGACY mode the tracer was written for.
    private static final boolean FSM2 = "fsm2".equalsIgnoreCase(System.getProperty("t1117.engine", "legacy"));
    private static final String EXPECTED_WARNS = "/fsm2/expected-warns-t1117.txt";

    private static final String CONSUMER_SLICE = TestArtifacts.STREAM_CONSUMER_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:t1117-tracer:1.0.0";
    private static final String TARGET_STREAM = TestArtifacts.streamEngineKey(BLUEPRINT_ID, "consumer-events");
    private static final List<String> STREAMS = List.of(TARGET_STREAM,
                                                        TestArtifacts.streamEngineKey(BLUEPRINT_ID, "order-events"),
                                                        TestArtifacts.streamEngineKey(BLUEPRINT_ID, "spread-events"));
    private static final String REMEDIATION_MARKER = " locally after ";
    private static final String CAPTURE_LOGGER = "org.pragmatica.aether.deployment.node.fsm";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private final HttpOperations http = jdkHttpOperations();
    private final List<String> remediationLines = new CopyOnWriteArrayList<>();
    private Fsm2Sensors sensors;
    private EmberCluster cluster;
    private CapturingAppender appender;
    private LoggerConfig captureConfig;

    @BeforeAll
    void setUp() {
        installCapture();
        sensors = FSM2 ? Fsm2Sensors.capture() : null;
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "t11", Option.some(configProvider));
        if (FSM2) {
            cluster.withDeploymentEngine(DeploymentEngine.FSM2);
        }
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().atMost(WAIT_TIMEOUT).pollInterval(Duration.ofMillis(500)).until(() -> cluster.currentLeader().isPresent());
        await().atMost(WAIT_TIMEOUT).pollInterval(Duration.ofMillis(500)).until(this::allNodesHealthy);
    }

    @AfterAll
    void tearDown() {
        ActivationWriteFault.disarm();
        if (sensors != null) {
            sensors.close();
        }
        if (cluster != null) {
            var leaderPort = cluster.getLeaderManagementPort().or(cluster.status().nodes().getFirst().mgmtPort());

            httpDelete(leaderPort, "/api/v1/blueprints/" + BLUEPRINT_ID);
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
        removeCapture();
    }

    @Test
    void declarativeConsumerDeploy_outcomeMapAndNodesAgree_noLateActiveAndNoAssignmentMove() {
        var artifact = Artifact.artifact(CONSUMER_SLICE).unwrap();
        var target = partitionOwner(TARGET_STREAM, 0);

        if (INJECT) {
            ActivationWriteFault.arm(target, artifact);
        } else {
            ActivationWriteFault.disarm();
        }
        System.out.printf("T1117 engine=%s inject=%s target=%s (HRW owner of %s[0])%n", FSM2 ? "FSM2" : "LEGACY", INJECT, target.id(), TARGET_STREAM);

        deployBlueprint();
        var samples = sample(artifact);
        var succeededAt = samples.stream().filter(Sample::succeeded).findFirst().map(Sample::atMs);

        printTimeline(samples);
        System.out.printf("T1117 unanswered submissions=%d remediation lines=%d%n",
                          ActivationWriteFault.unansweredSubmissions(),
                          remediationLines.size());
        remediationLines.forEach(line -> System.out.println("T1117 remediation: " + line));

        assertSetup(succeededAt, target);
        assertTracer(samples, succeededAt.orElseThrow(), artifact, target);
    }

    // --- SETUP preconditions: a failure here is never a tracer verdict --------------------------------------

    private void assertSetup(Optional<Long> succeededAt, NodeId target) {
        if (succeededAt.isEmpty()) {
            fail("SETUP: the blueprint's outcome never read SUCCEEDED within %s", SAMPLING_CAP);
        }
        var unanswered = ActivationWriteFault.unansweredSubmissions();

        if (INJECT && unanswered == 0) {
            fail("SETUP: the fault was armed for %s but never fired; this run measured nothing", target.id());
        }
        if (!INJECT && unanswered != 0) {
            fail("SETUP: the control run left %d submissions unanswered; the fault was not disarmed", unanswered);
        }
        if (FSM2) {
            return;
        }

        var reportedOwner = reportedOwner(TARGET_STREAM, 0);

        if (INJECT && !reportedOwner.equals(target.id())) {
            fail("SETUP: the injected node %s is not the reported owner of %s[0] (%s); (c) would measure nothing",
                 target.id(),
                 TARGET_STREAM,
                 reportedOwner);
        }
    }

    // --- the tracer's assertions ------------------------------------------------------------------------------

    private void assertTracer(List<Sample> samples, long succeededAt, Artifact artifact, NodeId target) {
        var violations = new ArrayList<String>();

        violations.addAll(lateNonActive(samples, succeededAt));
        violations.addAll(FSM2 ? fsm2Sensors(target) : forcedTransitions(artifact));
        violations.addAll(FSM2 ? notDeterminable() : lateAssignmentMoves(samples, succeededAt));
        violations.addAll(mapLocalDisagreement(samples, succeededAt));

        assertThat(violations).describedAs("T1117 tracer violations (engine=%s, inject=%s); timeline above", FSM2 ? "FSM2" : "LEGACY", INJECT)
                              .isEmpty();
    }

    /// (c) under FSM2: the stream-consumer reader is not FSM2-fed until phase 4a, so no assignment exists to move. Reported, not faked; not a
    /// violation, and not a pass of (c) either.
    private List<String> notDeterminable() {
        System.out.println("T1117 (c) NOT DETERMINABLE under FSM2 in phase-3 scope: the stream-consumer reader is fed by the legacy deployment map (phase 4a)");

        return List.of();
    }

    /// (a) The last sample at which an instance was non-ACTIVE, in the map or in its node's local view, must lie
    /// within X of the SUCCEEDED outcome.
    private List<String> lateNonActive(List<Sample> samples, long succeededAt) {
        var violations = new ArrayList<String>();

        for (var node : nodeIds()) {
            lastNonActive(samples, sample -> sample.map().get(node)).filter(at -> at - succeededAt > GRACE.toMillis())
                                                                    .onPresent(at -> violations.add(lateLine("map", node, at - succeededAt)));
            lastNonActive(samples, sample -> sample.local().get(node)).filter(at -> at - succeededAt > GRACE.toMillis())
                                                                      .onPresent(at -> violations.add(lateLine("local", node, at - succeededAt)));
        }

        return violations;
    }

    private static String lateLine(String view, String node, long afterMs) {
        return "T(a) %s: %s instance still non-ACTIVE %.1f s after the outcome read SUCCEEDED (X = %d s)".formatted(node,
                                                                                                                   view,
                                                                                                                   afterMs / 1000.0,
                                                                                                                   GRACE.toSeconds());
    }

    private static Option<Long> lastNonActive(List<Sample> samples, Function<Sample, SliceState> view) {
        var last = Option.<Long>none();

        for (var sample : samples) {
            if (view.apply(sample) != SliceState.ACTIVE) {
                last = Option.some(sample.atMs());
            }
        }

        return last;
    }

    /// (b) The legacy stuck-slice remediation logs `Node <id> slice <artifact> still <STATE> locally after <timer>`
    /// from both of its timers and from nowhere else.
    private List<String> forcedTransitions(Artifact artifact) {
        return remediationLines.stream()
                               .filter(line -> line.contains(artifact.asString()))
                               .map(line -> "T(b) remediation fired: " + line)
                               .toList();
    }

    /// (c) Any change of a partition's committed assignee later than X after the SUCCEEDED outcome.
    private List<String> lateAssignmentMoves(List<Sample> samples, long succeededAt) {
        var violations = new ArrayList<String>();
        var lastAssignee = new LinkedHashMap<String, String>();

        for (var sample : samples) {
            var afterMs = sample.atMs() - succeededAt;

            for (var entry : sample.assignments().entrySet()) {
                var previous = lastAssignee.put(entry.getKey(), entry.getValue());

                if (previous != null && !previous.equals(entry.getValue()) && afterMs > GRACE.toMillis()) {
                    violations.add("T(c) %s moved %s -> %s at %.1f s after the outcome read SUCCEEDED".formatted(entry.getKey(),
                                                                                                                  previous,
                                                                                                                  entry.getValue(),
                                                                                                                  afterMs / 1000.0));
                }
            }
        }

        return violations;
    }

    /// (d) The last sample must show all-ACTIVE in the map, agreeing with every local view; and no node's map and
    /// local states may disagree for longer than X at a stretch.
    private List<String> mapLocalDisagreement(List<Sample> samples, long succeededAt) {
        var violations = new ArrayList<String>();
        var last = samples.getLast();

        for (var node : nodeIds()) {
            if (last.map().get(node) != SliceState.ACTIVE || last.local().get(node) != SliceState.ACTIVE) {
                violations.add("T(d) %s at the end: map=%s local=%s".formatted(node, last.map().get(node), last.local().get(node)));
            }
            longestDisagreement(samples, node).filter(span -> span > GRACE.toMillis())
                                              .onPresent(span -> violations.add("T(d) %s map and local disagreed for %.1f s".formatted(node,
                                                                                                                                      span / 1000.0)));
        }

        return violations;
    }

    private static Option<Long> longestDisagreement(List<Sample> samples, String node) {
        var longest = 0L;
        var since = Option.<Long>none();

        for (var sample : samples) {
            var disagree = sample.map().get(node) != sample.local().get(node);

            if (disagree && since.isEmpty()) {
                since = Option.some(sample.atMs());
            }
            if (!disagree && since.isPresent()) {
                longest = Math.max(longest, sample.atMs() - since.unwrap());
                since = Option.none();
            }
        }
        if (since.isPresent()) {
            longest = Math.max(longest, samples.getLast().atMs() - since.unwrap());
        }

        return longest > 0
               ? Option.some(longest)
               : Option.none();
    }

    // --- sampling ---------------------------------------------------------------------------------------------

    /// One observation: ms since the deploy, whether the outcome reads SUCCEEDED, the leader's deployment map, every
    /// node's local FSM view, and the committed consumer assignments of this blueprint's streams.
    private record Sample(long atMs,
                          boolean succeeded,
                          Map<String, SliceState> map,
                          Map<String, SliceState> local,
                          Map<String, String> assignments) {
        String render() {
            return "+%6.1fs %s map=%s local=%s asg=%s".formatted(atMs / 1000.0,
                                                                 succeeded
                                                                 ? "SUCCEEDED"
                                                                 : "-        ",
                                                                 abbreviate(map),
                                                                 abbreviate(local),
                                                                 assignments);
        }

        boolean sameObservation(Sample other) {
            return succeeded == other.succeeded && map.equals(other.map) && local.equals(other.local)
                   && assignments.equals(other.assignments);
        }

        private static String abbreviate(Map<String, SliceState> states) {
            return states.entrySet()
                         .stream()
                         .map(entry -> entry.getKey() + ":" + (entry.getValue() == null
                                                               ? "-"
                                                               : entry.getValue().name()))
                         .collect(Collectors.joining(",", "{", "}"));
        }
    }

    private List<Sample> sample(Artifact artifact) {
        var samples = new ArrayList<Sample>();
        var start = System.nanoTime();
        var stopAt = Option.<Long>none();

        while (true) {
            var atMs = (System.nanoTime() - start) / 1_000_000;
            var sample = new Sample(atMs, succeeded(), mapStates(artifact), localStates(artifact), assignments());

            samples.add(sample);
            if (sample.succeeded() && stopAt.isEmpty()) {
                stopAt = Option.some(atMs + OBSERVE_AFTER_SUCCEEDED.toMillis());
            }
            var observedEnough = stopAt.map(bound -> atMs >= bound).or(false) && allActiveAndAgreeing(sample);

            if (observedEnough || atMs >= SAMPLING_CAP.toMillis()) {
                return samples;
            }
            LockSupport.parkNanos(SAMPLE_INTERVAL.toNanos());
        }
    }

    private boolean allActiveAndAgreeing(Sample sample) {
        return nodeIds().stream()
                        .allMatch(node -> sample.map().get(node) == SliceState.ACTIVE
                                          && sample.local().get(node) == SliceState.ACTIVE);
    }

    private boolean succeeded() {
        var id = BlueprintId.blueprintId(BLUEPRINT_ID).unwrap();

        return anyNode().blueprintService()
                        .outcome(id)
                        .map(outcome -> outcome.status() == DeploymentOutcomeStatus.SUCCEEDED)
                        .or(false);
    }

    /// The cluster deployment map as the current leader mirrors it.
    private Map<String, SliceState> mapStates(Artifact artifact) {
        if (FSM2) {
            return committedRows(artifact);
        }

        var states = new TreeMap<String, SliceState>();

        nodeIds().forEach(node -> states.put(node, null));
        leaderNode().deploymentMap()
                    .byArtifact(artifact)
                    .forEach((node, state) -> states.put(node.id(), state));

        return states;
    }

    /// Every node's OWN view of its instance: the `deployments` map of its node-deployment FSM's Active state.
    private Map<String, SliceState> localStates(Artifact artifact) {
        if (FSM2) {
            return servingSurfaces(artifact);
        }

        var states = new TreeMap<String, SliceState>();

        for (var node : cluster.allNodes()) {
            states.put(node.self().id(), localState(node, artifact));
        }

        return states;
    }

    // --- FSM2: the committed rows, the serving surface, the sensors -----------------------------------------------------

    /// The leader's committed `SliceObservedKey` row of each node for the artifact.
    private Map<String, SliceState> committedRows(Artifact artifact) {
        var states = new TreeMap<String, SliceState>();
        var store = leaderNode().kvStore();

        for (var node : cluster.allNodes()) {
            states.put(node.self().id(),
                       store.getTyped(SliceObservedKey.sliceObservedKey(node.self(), artifact), SliceObservedValue.class)
                            .map(row -> SliceState.valueOf(row.state() == AetherValue.SliceObservedState.UNLOADED
                                                           ? "UNLOADING"
                                                           : row.state().name()))
                            .or((SliceState) null));
        }

        return states;
    }

    /// The node's SERVING SURFACE (spec O1 ruling, the s36 `know:` ruling): ACTIVE while the slice's bridge is registered with the node's invocation
    /// handler AND its routes are published on the node's route publisher (the scenario slice declares routes, so a missing publication is a missing
    /// surface); otherwise the state of the node FSM's own record of the instance (or none).
    private Map<String, SliceState> servingSurfaces(Artifact artifact) {
        var states = new TreeMap<String, SliceState>();

        for (var node : cluster.allNodes()) {
            states.put(node.self().id(), servingSurface(node, artifact));
        }

        return states;
    }

    private static boolean routesPublished(AetherNode node, Artifact artifact) {
        return node.appHttpServer()
                   .httpRoutePublisher()
                   .map(publisher -> publisher.getSliceRouter(artifact).isPresent())
                   .or(false);
    }

    private static SliceState servingSurface(AetherNode node, Artifact artifact) {
        if (node.invocationHandler().localSlice(artifact).isPresent() && routesPublished(node, artifact)) {
            return SliceState.ACTIVE;
        }

        return node.deploymentWiring()
                   .system()
                   .flatMap(system -> system.nodeInterpreter().model().instance(artifact).observed())
                   .map(row -> SliceState.valueOf(row.state() == AetherValue.SliceObservedState.UNLOADED
                                                  ? "UNLOADING"
                                                  : row.state().name()))
                   .or((SliceState) null);
    }

    /// (b) under FSM2 and the expected-WARN lists (design 9.5 O6, 12 item 6): the sensors read what the fault should have caused and nothing else;
    /// no WARN of the deployment FSM loggers and no operator warning is outside the list.
    private List<String> fsm2Sensors(NodeId target) {
        var violations = new ArrayList<String>();
        var expectations = Fsm2Sensors.expectations(EXPECTED_WARNS);
        var warnLines = sensors.warnLines();
        var targetNode = cluster.getNode(target.id()).unwrap();

        System.out.printf("T1117 FSM2 WARN/ERROR lines=%d, sensor lines=%d%n", warnLines.size(), sensors.sensorLines(target.id(), "write-").size());
        warnLines.forEach(line -> System.out.println("T1117 warn: " + line));
        warnLines.stream()
                 .filter(line -> expectations.stream().filter(e -> e.kind().equals("warn")).noneMatch(e -> e.matches(line)))
                 .forEach(line -> violations.add("T(b) WARN not on the expected list: " + line));
        for (var entry : expectations) {
            var count = switch (entry.kind()) {
                case "warn" -> warnLines.stream().filter(entry::matches).count();
                case "line" -> sensors.sensorLines(target.id(), entry.name()).size();
                default -> (long) Fsm2Sensors.armCount(targetNode, entry.name());
            };
            var admits = !entry.kind().equals("warn") && !INJECT
                         ? count == 0
                         : entry.admits(count);

            if (!admits) {
                violations.add("T(b) %s %s: %d, expected %d..%d%s".formatted(entry.kind(), entry.name(), count, entry.min(), entry.max(), INJECT ? "" : " (control: silent)"));
            }
        }

        return violations;
    }

    private static SliceState localState(AetherNode node, Artifact artifact) {
        var current = nodeDeploymentContext(node).fsm().current();

        if (!(current instanceof NodeDeploymentState.Active active)) {
            return null;
        }

        return active.deployments()
                     .values()
                     .stream()
                     .filter(deployment -> deployment.key().artifact().equals(artifact))
                     .map(NodeDeploymentManager.SliceDeployment::state)
                     .findFirst()
                     .orElse(null);
    }

    /// Two reflective hops, test-only: the node record's `nodeDeploymentManager` component, then the adapter's `ctx`.
    private static NodeDeploymentContext nodeDeploymentContext(AetherNode node) {
        try {
            var accessor = node.getClass().getDeclaredMethod("nodeDeploymentManager");

            accessor.setAccessible(true);
            var manager = accessor.invoke(node);
            var ctxField = manager.getClass().getDeclaredField("ctx");

            ctxField.setAccessible(true);

            return (NodeDeploymentContext) ctxField.get(manager);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("SETUP: cannot reach the node-deployment FSM of " + node.self().id(), e);
        }
    }

    private Map<String, String> assignments() {
        var assigned = new TreeMap<String, String>();

        leaderNode().kvStore()
                    .forEach(ConsumerAssignmentKey.class,
                             ConsumerAssignmentValue.class,
                             (key, value) -> recordAssignment(assigned, key, value));

        return assigned;
    }

    private static void recordAssignment(Map<String, String> assigned, ConsumerAssignmentKey key, ConsumerAssignmentValue value) {
        var alias = STREAMS.indexOf(key.streamName());

        if (alias >= 0) {
            assigned.put(key.streamName().split(":")[1] + "[" + key.partitionIndex() + "]", value.assignee().id());
        }
    }

    private void printTimeline(List<Sample> samples) {
        var previous = Option.<Sample>none();

        for (var sample : samples) {
            if (previous.map(prior -> !prior.sameObservation(sample)).or(true)) {
                System.out.println("T1117 " + sample.render());
            }
            previous = Option.some(sample);
        }
        System.out.println("T1117 last " + samples.getLast().render());
    }

    // --- target selection and topology -------------------------------------------------------------------------

    /// The HRW owner of `(stream, partition)` over the five members, as a fresh cluster with no committed owner
    /// computes it ([ReplicaPlacement#place]). [#assertSetup] checks it against the owner the node reports.
    private NodeId partitionOwner(String stream, int partition) {
        var members = cluster.allNodes().stream().map(AetherNode::self).toList();

        return ReplicaPlacement.place(stream, partition, members, 1)
                               .map(ReplicaPlacement.Placement::owner)
                               .unwrap();
    }

    private String reportedOwner(String stream, int partition) {
        var row = Pattern.compile("\\{\"partition\":" + partition
                                                  + ",(?:\"consumerNode\":\"[^\"]*\",)?\"ownerNode\":\"([^\"]*)\"\\}");

        return cluster.status()
                      .nodes()
                      .stream()
                      .map(node -> consumerFragment(httpGet(node.mgmtPort(), "/api/v1/streams/declarative-consumers"), stream))
                      .map(row::matcher)
                      .filter(Matcher::find)
                      .map(matcher -> matcher.group(1))
                      .findFirst()
                      .orElse("unreported");
    }

    private static String consumerFragment(String body, String stream) {
        return Arrays.stream(body.split("\\{\"stream\":\""))
                               .filter(fragment -> fragment.startsWith(stream + "\""))
                               .findFirst()
                               .orElse("");
    }

    private List<String> nodeIds() {
        return cluster.allNodes().stream().map(node -> node.self().id()).sorted().toList();
    }

    private AetherNode anyNode() {
        return cluster.allNodes().getFirst();
    }

    private AetherNode leaderNode() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .or(this::anyNode);
    }

    private boolean allNodesHealthy() {
        return cluster.status()
                      .nodes()
                      .stream()
                      .allMatch(node -> httpGet(node.mgmtPort(), "/api/v1/health").contains("\"quorum\":true"));
    }

    private void deployBlueprint() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, CONSUMER_SLICE, INSTANCES);
        var leaderPort = cluster.getLeaderManagementPort().or(cluster.status().nodes().getFirst().mgmtPort());
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + leaderPort + "/api/v1/blueprints"))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(blueprint))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        var response = send(request);

        assertThat(response).describedAs("SETUP: declarative-consumer blueprint deployment")
                            .doesNotContain("\"error\"")
                            .contains("\"status\":\"applied\"");
    }

    // --- log capture for (b) -------------------------------------------------------------------------------------

    /// Captures WARN lines of the node-deployment FSM package without changing what the run logs: the logger config is
    /// additive and keeps the level it inherits.
    private void installCapture() {
        var ctx = (LoggerContext) LogManager.getContext(false);
        var configuration = ctx.getConfiguration();
        var existing = configuration.getLoggerConfig(CAPTURE_LOGGER);

        appender = new CapturingAppender(remediationLines);
        appender.start();
        if (CAPTURE_LOGGER.equals(existing.getName())) {
            captureConfig = existing;
        } else {
            captureConfig = new LoggerConfig(CAPTURE_LOGGER, existing.getLevel(), true);
            configuration.addLogger(CAPTURE_LOGGER, captureConfig);
        }
        captureConfig.addAppender(appender, Level.WARN, null);
        ctx.updateLoggers();
    }

    private void removeCapture() {
        if (captureConfig == null) {
            return;
        }
        var ctx = (LoggerContext) LogManager.getContext(false);

        captureConfig.removeAppender(appender.getName());
        ctx.updateLoggers();
        appender.stop();
    }

    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> lines;

        private CapturingAppender(List<String> lines) {
            super("T1117RemediationCapture", (Filter) null, null, true, Property.EMPTY_ARRAY);
            this.lines = lines;
        }

        @Override
        public void append(LogEvent event) {
            var message = event.getMessage().getFormattedMessage();

            if (message.contains(REMEDIATION_MARKER) && message.contains(" still ")) {
                lines.add(message);
            }
        }
    }

    // --- HTTP ----------------------------------------------------------------------------------------------------

    private String httpGet(int port, String path) {
        return send(HttpRequest.newBuilder()
                               .uri(URI.create("http://localhost:" + port + path))
                               .GET()
                               .timeout(Duration.ofSeconds(10))
                               .build());
    }

    private String httpDelete(int port, String path) {
        return send(HttpRequest.newBuilder()
                               .uri(URI.create("http://localhost:" + port + path))
                               .DELETE()
                               .timeout(Duration.ofSeconds(10))
                               .build());
    }

    private String send(HttpRequest request) {
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
