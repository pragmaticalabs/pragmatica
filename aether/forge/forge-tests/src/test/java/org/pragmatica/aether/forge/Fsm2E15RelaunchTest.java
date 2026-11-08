// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// E-15 (phase-3 design 9.3; FSM replacement plan 3.3, F4; #2763), FSM2 only: a core halted on quorum loss, then relaunched. Default harness,
/// self-fence active: the precondition is the core's own QUORUM_LOSS drain (exit 2, `AetherNode.EXIT_DRAINED`, design 5.3.5 arm A) after it is
/// silenced. Five FSM2 cores, one slice x 3; auto-heal provisioning is blocked, so every newcomer is the test's.
///
/// - (a) relaunch as a REPLACEMENT (fresh NodeId): C's departure record stays DEPARTED with its (epoch, process) for the whole arm (never
///   ADMITTED); C17b removes C's slice rows; 3 ACTIVE again.
/// - (b) relaunch under C's OLD NodeId with a new boot token: never admitted, so C's record stays DEPARTED(e, p1) for the whole arm; no slice row of
///   C's is present after C17b removed them; 3 ACTIVE again on the others.
///
/// What KV cannot show, and so is NOT asserted: whether a `NodeJoined(C)` reached the leader and which join rule (J3/J5/J8) took it. The red
/// condition kept is the design's own: any ADMITTED record for C, or any row of C's counted after the relaunch.
@Tag("Heavy")
@EnabledIfSystemProperty(named = "f4.fsm2only", matches = "E15")
class Fsm2E15RelaunchTest {
    private static final Duration WAIT = Duration.ofSeconds(240);
    private static final Duration FENCE_BOUND = Duration.ofSeconds(Long.getLong("f4.fence.bound.s", 180L));
    private static final Duration OBSERVE = Duration.ofSeconds(Long.getLong("f4.observe.s", 90L));
    private static final String BLUEPRINT_ID = "forge.test:f4-e15:1.0.0";

    private final HttpOperations http = jdkHttpOperations();
    private EmberCluster cluster;

    @AfterEach
    void tearDown() {
        Option.option(cluster).onPresent(running -> LifecycleAwait.bestEffort("E15 cluster stop", running, running.stop()));
    }

    @Test
    void haltedCore_relaunchedAsAReplacement_staysDeparted_andPlacementIsRestored() {
        var victim = haltOnQuorumLoss("f4ra", 34000);
        var departed = departure(victim).unwrap();
        var violations = new CopyOnWriteArrayList<String>();

        log("replacement -> " + cluster.addCoreNode("f4ra-9").await());
        observe(victim, departed, violations);
        assertThat(violations).as("C's record stayed DEPARTED(e, p1) and none of C's rows was counted").isEmpty();
        awaitTrue("C17b removed C's slice rows", WAIT, () -> rowsOf(victim).isEmpty());
        awaitTrue("3 ACTIVE after the replacement", WAIT, () -> active() == 3);
    }

    @Test
    void haltedCore_relaunchedUnderItsOldNodeId_isNeverAdmitted_andItsRowsStayGone() {
        var victim = haltOnQuorumLoss("f4rb", 34400);
        var departed = departure(victim).unwrap();
        var violations = new CopyOnWriteArrayList<String>();

        awaitTrue("C17b removed C's slice rows before the relaunch", WAIT, () -> rowsOf(victim).isEmpty());
        var drained = cluster.exitCodeOf(victim.id());
        // The start promise of a refused process never resolves (the node stops during its start; EmberSameIdentityRelaunchTest), so awaiting it
        // only times out (dev11: the test's 10 min bound). The arm observes from the launch; the refusal's evidence is the relaunched process's
        // own exit (a code other than the drain's) and a start that never completed.
        var launch = cluster.relaunchNode(victim.id(), true);

        observe(victim, departed, violations);
        assertThat(violations).as("C's record stayed DEPARTED(e, p1) and none of C's rows was counted after the relaunch").isEmpty();
        awaitTrue("the relaunched old-NodeId process exits (refused)", WAIT, () -> cluster.exitCodeOf(victim.id()).filter(code -> drained.filter(code::equals).isEmpty()).isPresent());
        log("relaunched " + victim.id() + " exit code=" + cluster.exitCodeOf(victim.id()) + " (drain exit " + drained + ")");
        assertThat(launch.await(TimeSpan.timeSpan(1).seconds()).fold(Cause::message, _ -> "launched"))
            .as("the relaunched old-NodeId process never completes a start")
            .isNotEqualTo("launched");
        awaitTrue("3 ACTIVE on the others", WAIT, () -> active() == 3);
    }

    /// Five FSM2 cores, echo x 3 placed, then a non-leader core hosting an instance silenced until it drains itself (exit 2).
    private NodeId haltOnQuorumLoss(String prefix, int basePort) {
        cluster = emberCluster(5, basePort, basePort + 100, basePort + 200, prefix);
        cluster.withDeploymentEngine(DeploymentEngine.FSM2);
        cluster.withComputeProviderDecorator(Fsm2EngineMarkerMixedClusterTest.BlockedProvisioning::new);
        LifecycleAwait.settled("E15 cluster start", cluster, cluster.start());
        awaitTrue("a leader", WAIT, () -> cluster.currentLeader().isPresent());
        publish();
        awaitTrue("3 ACTIVE", WAIT, () -> active() == 3);
        var victim = hostOfAnInstance();

        log("silencing " + victim.id());
        cluster.getNode(victim.id()).onPresent(node -> node.blackhole(true));
        awaitTrue("the silenced core drains itself on quorum loss", FENCE_BOUND, () -> cluster.exitCodeOf(victim.id()).isPresent());
        assertThat(cluster.exitCodeOf(victim.id())).as("PRECONDITION (design 5.3.5 arm A): exit 2 on the QUORUM_LOSS self-fence")
                                                    .isEqualTo(Option.some(AetherNode.EXIT_DRAINED));
        awaitTrue("C's departure is committed", WAIT, () -> departure(victim).isPresent());
        log("C departed: " + departure(victim));

        return victim;
    }

    /// Samples every 250 ms for OBSERVE: C's record must keep its first DEPARTED (epoch, process); a slice row of C's present after the first
    /// sample without one is a counted row of the departed process.
    private void observe(NodeId victim, AetherValue.NodeDepartureValue departed, List<String> violations) {
        var deadline = System.currentTimeMillis() + OBSERVE.toMillis();
        var cleared = rowsOf(victim).isEmpty();

        while (System.currentTimeMillis() < deadline) {
            var now = departureRecord(victim);

            if (now.isEmpty() || !sameDeparture(now.unwrap(), departed)) {
                violations.add(Instant.now() + " C's record is " + now + ", expected DEPARTED " + departed);
            }
            var rows = rowsOf(victim);

            if (cleared && !rows.isEmpty()) {
                violations.add(Instant.now() + " a row of C's after C17b: " + rows);
            }
            cleared = cleared || rows.isEmpty();
            sleep(250L);
        }
    }

    private static boolean sameDeparture(AetherValue.NodeDepartureValue now, AetherValue.NodeDepartureValue departed) {
        return now.state() == AetherValue.DepartureState.DEPARTED && now.epoch() == departed.epoch() && now.process().equals(departed.process());
    }

    private Option<AetherValue.NodeDepartureValue> departure(NodeId node) {
        return departureRecord(node).filter(value -> value.state() == AetherValue.DepartureState.DEPARTED);
    }

    private Option<AetherValue.NodeDepartureValue> departureRecord(NodeId node) {
        return Option.option(snapshot().get(new AetherKey.NodeDepartureKey(node)))
                     .filter(AetherValue.NodeDepartureValue.class::isInstance)
                     .map(AetherValue.NodeDepartureValue.class::cast);
    }

    /// C's slice rows that count: observed rows, and desired rows whose goal is not the UNLOADED tombstone.
    private List<String> rowsOf(NodeId node) {
        var rows = new ArrayList<String>();

        snapshot().forEach((key, value) -> {
            switch (key) {
                case AetherKey.SliceObservedKey row when row.nodeId().equals(node) -> rows.add(row.toString());
                case AetherKey.SliceDesiredKey row when row.nodeId().equals(node)
                                                       && value instanceof AetherValue.SliceDesiredValue desired
                                                       && desired.goal() != AetherValue.SliceGoal.UNLOADED -> rows.add(row.toString());
                default -> {}
            }
        });

        return rows;
    }

    private NodeId hostOfAnInstance() {
        var leader = cluster.currentLeader().or("");

        return new EquivalenceKv(snapshot()).instances()
                                            .stream()
                                            .filter(instance -> instance.base().equals(echo().base()) && instance.state().equals("ACTIVE"))
                                            .map(EquivalenceKv.Instance::node)
                                            .filter(id -> !id.id().equals(leader))
                                            .sorted((left, right) -> left.id().compareTo(right.id()))
                                            .findFirst()
                                            .orElseThrow();
    }

    private long active() {
        return new EquivalenceKv(snapshot()).active().getOrDefault(echo().base(), 0L);
    }

    private static Artifact echo() {
        return Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap();
    }

    private void publish() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = 3
            """.formatted(BLUEPRINT_ID, TestArtifacts.ECHO_SLICE);
        var response = http.sendString(HttpRequest.newBuilder()
                                                  .uri(mgmt("/api/v1/blueprints"))
                                                  .header("Content-Type", "application/toml")
                                                  .POST(HttpRequest.BodyPublishers.ofString(blueprint))
                                                  .timeout(Duration.ofSeconds(10))
                                                  .build())
                           .await()
                           .map(HttpResult::body)
                           .or("{\"error\":\"request failed\"}");

        log("publish -> " + response);
        assertThat(response).as("SETUP: publish accepted").doesNotContain("\"error\"");
    }

    private URI mgmt(String path) {
        var port = cluster.getLeaderManagementPort().or(cluster.status().nodes().getFirst().mgmtPort());

        return URI.create("http://localhost:" + port + path);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<AetherKey, AetherValue> snapshot() {
        var aether = new HashMap<AetherKey, AetherValue>();
        var reader = cluster.currentLeader().flatMap(cluster::getNode).or(() -> cluster.allNodes().getFirst());

        ((Map<Object, Object>) (Map) reader.kvStore().snapshot()).forEach((key, value) -> {
            if (key instanceof AetherKey aetherKey && value instanceof AetherValue aetherValue) {
                aether.put(aetherKey, aetherValue);
            }
        });

        return aether;
    }

    private static void log(String line) {
        System.out.println("F4-E15 " + Instant.now() + " " + line);
    }

    private static void awaitTrue(String what, Duration bound, BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + bound.toMillis();

        while (!condition.getAsBoolean()) {
            assertThat(System.currentTimeMillis()).as("timed out waiting for " + what).isLessThan(deadline);
            sleep(250L);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
