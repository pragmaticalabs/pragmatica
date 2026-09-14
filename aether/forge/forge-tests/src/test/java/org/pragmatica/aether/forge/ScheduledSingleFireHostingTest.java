// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// #272 R12 — a SINGLE-mode scheduled task fires on the leader, and the leader need not host the
/// slice. The fire is `SliceInvoker.invoke(artifact, method, Unit.unit())`; for a remote endpoint the
/// invoker needed a LOCAL slice bridge to encode the `Unit` request and failed with
/// SENDER_BRIDGE_NOT_FOUND on a leader hosting nothing that knows `Unit`, writing a failure state
/// every interval while the hosting node never saw a call.
///
/// Placement is made deterministic rather than hoped for: the allocation engine places a new
/// single-instance blueprint on a "truly empty" node first, so a filler blueprint (`echo`) is deployed
/// to occupy nodes until the scheduled slice (`test-full`, `[scheduling.heartbeat]` SINGLE, 10s) lands
/// on a NON-leader. Both observables are read in-JVM: the leader's committed
/// `ScheduledTaskStateValue` for the task, and the host's per-method invocation count (recorded by the
/// callee's `InvocationHandler.onInvokeRequest`), so "fired" means the host executed it, not merely that
/// the leader believes it sent something.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduledSingleFireHostingTest {
    private static final Logger log = LoggerFactory.getLogger(ScheduledSingleFireHostingTest.class);
    private static final int NODES = 3;
    private static final int BASE_PORT = 25500;
    private static final int BASE_MGMT_PORT = 25600;
    private static final int BASE_APP_HTTP_PORT = 25700;
    private static final String SCHEDULED_SLICE = "org.pragmatica.aether.test:test-full-full-slice:1.0.0";
    private static final String SCHEDULED_BLUEPRINT = "forge.test:scheduled-single-fire:1.0.0";
    private static final String FILLER_SLICE = TestArtifacts.ECHO_SLICE;
    private static final String FILLER_BLUEPRINT = "forge.test:scheduled-single-fire-filler:1.0.0";
    private static final String SECTION = "scheduling.heartbeat";
    private static final MethodName METHOD = MethodName.methodName("heartbeat").unwrap();
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration PLACEMENT_TIMEOUT = Duration.ofSeconds(120);
    /// Three 10s intervals plus slack: two fires (or two failure states) must have landed.
    private static final Duration FIRE_WINDOW = Duration.ofSeconds(45);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final int EXPECTED_FIRES = 2;

    private final HttpOperations http = jdkHttpOperations();
    private final Artifact scheduledArtifact = Artifact.artifact(SCHEDULED_SLICE).unwrap();
    private EmberCluster cluster;

    @BeforeAll
    void setUp(@TempDir Path baseDir) {
        // A node config provider is what lets `SliceStore` layer the slice jar's `resources.toml`
        // (`[scheduling.heartbeat]`) into a slice composite; without one the activation publishes no
        // scheduled task at all, silently, and this test would measure nothing.
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();

        cluster = emberCluster(NODES,
                               BASE_PORT,
                               BASE_MGMT_PORT,
                               BASE_APP_HTTP_PORT,
                               "sched",
                               Option.some(configProvider));
        cluster.withDataBaseDir(baseDir);
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader()
                                                                           .isPresent());
        await().atMost(FORM_TIMEOUT)
             .pollInterval(POLL)
             .until(() -> leader().membershipFsm()
                                .coreCountedMembers()
                                .size() == NODES);
    }

    @AfterAll
    void tearDown() {
        Option.option(cluster).onPresent(c -> LifecycleAwait.bestEffort("cluster stop in tearDown()", c, c.stop()));
    }

    @Test
    void singleModeTask_firesOnTheHostingNode_whenTheLeaderDoesNotHostIt() {
        var host = placeScheduledSliceOffLeader();
        var leaderId = cluster.currentLeader().or("");

        log.info("SSF: leader={} host={} — watching {} fires over {}s",
                 leaderId,
                 host,
                 EXPECTED_FIRES,
                 FIRE_WINDOW.toSeconds());
        awaitFiresOrFailures(host);
        var state = taskState();
        var hostCalls = heartbeatCallsOn(host);
        // Logged before the assertions so a red run carries its own picture.
        log.info("SSF RESULT: leader={} host={} hostHeartbeatCalls={} state={} taskRegistered={}",
                 leaderId,
                 host,
                 hostCalls,
                 state,
                 taskRegisteredOnEveryNode());
        assertThat(hostCalls).as("#272 R12: the SINGLE-mode fire must reach the hosting node's slice (%d intervals elapsed)",
                                 EXPECTED_FIRES)
                  .isGreaterThanOrEqualTo(EXPECTED_FIRES);
        assertThat(state.map(ScheduledTaskStateValue::consecutiveFailures).or(-1)).as("#272 R12: the leader must not record failures for a task it does not host (last failure: %s)",
                                                                                      state.map(ScheduledTaskStateValue::lastFailureMessage)
                                                                                           .or("<none>"))
                  .isZero();
        assertThat(state.map(ScheduledTaskStateValue::totalExecutions).or(0)).as("#272 R12: the leader's task state must count the fires")
                  .isGreaterThanOrEqualTo(EXPECTED_FIRES);
    }

    /// Deploys the filler first so the scheduled slice lands on the next truly-empty node; if that is
    /// still the leader, redeploys with the filler widened to two instances so the only empty node left
    /// is a non-leader. The precondition is asserted, never assumed.
    private String placeScheduledSliceOffLeader() {
        deploy(FILLER_BLUEPRINT, FILLER_SLICE, 1);
        awaitHosts(fillerArtifact(), 1);
        deploy(SCHEDULED_BLUEPRINT, SCHEDULED_SLICE, 1);
        var host = awaitHosts(scheduledArtifact, 1).iterator().next();

        if (host.equals(cluster.currentLeader().or(""))) {
            log.info("SSF: first placement landed on the leader {} — widening the filler and re-placing", host);
            undeploy(SCHEDULED_BLUEPRINT);
            awaitHosts(scheduledArtifact, 0);
            undeploy(FILLER_BLUEPRINT);
            awaitHosts(fillerArtifact(), 0);
            deploy(FILLER_BLUEPRINT, FILLER_SLICE, 2);
            awaitHosts(fillerArtifact(), 2);
            deploy(SCHEDULED_BLUEPRINT, SCHEDULED_SLICE, 1);
            host = awaitHosts(scheduledArtifact, 1).iterator().next();
        }

        assertThat(host).as("precondition: the scheduled slice must be hosted on a NON-leader node (leader=%s)",
                            cluster.currentLeader().or(""))
                  .isNotEqualTo(cluster.currentLeader().or(""));

        return host;
    }

    private void awaitFiresOrFailures(String host) {
        try {
            await().atMost(FIRE_WINDOW)
                 .pollInterval(POLL)
                 .until(() -> heartbeatCallsOn(host) >= EXPECTED_FIRES || taskState().map(ScheduledTaskStateValue::consecutiveFailures)
                                                                                   .or(0) >= EXPECTED_FIRES);
        } catch (org.awaitility.core.ConditionTimeoutException timeout) {
            log.warn("SSF: neither {} fires nor {} failures within {}s",
                     EXPECTED_FIRES,
                     EXPECTED_FIRES,
                     FIRE_WINDOW.toSeconds());
        }
    }

    /// The cluster-scoped task key the hosting node publishes at activation, as seen by every node.
    private boolean taskRegisteredOnEveryNode() {
        var key = ScheduledTaskKey.scheduledTaskKey(SECTION, scheduledArtifact, METHOD);

        return cluster.allNodes()
                      .stream()
                      .allMatch(node -> node.kvStore()
                                            .get(key)
                                            .isPresent());
    }

    private Option<ScheduledTaskStateValue> taskState() {
        return leader().kvStore()
                     .get(ScheduledTaskStateKey.scheduledTaskStateKey(SECTION, scheduledArtifact, METHOD))
                     .filter(ScheduledTaskStateValue.class::isInstance)
                     .map(ScheduledTaskStateValue.class::cast);
    }

    private long heartbeatCallsOn(String nodeId) {
        return cluster.getNode(nodeId)
                      .map(node -> node.invocationMetrics()
                                       .collectPerSliceMetrics()
                                       .stream()
                                       .filter(slice -> slice.artifact()
                                                             .equals(scheduledArtifact))
                                       .flatMap(slice -> slice.methods()
                                                              .stream())
                                       .filter(method -> method.method()
                                                               .equals(METHOD.name()))
                                       .mapToLong(method -> method.totalCalls())
                                       .sum())
                      .or(0L);
    }

    private Set<String> awaitHosts(Artifact artifact, int expected) {
        await().atMost(PLACEMENT_TIMEOUT).pollInterval(POLL).until(() -> activeHosts(artifact).size() == expected);

        return activeHosts(artifact);
    }

    private Set<String> activeHosts(Artifact artifact) {
        return cluster.slicesStatus()
                      .stream()
                      .filter(slice -> slice.artifact()
                                            .equals(artifact.asString()))
                      .flatMap(slice -> slice.instances()
                                             .stream())
                      .filter(instance -> "ACTIVE".equals(instance.state()))
                      .map(EmberCluster.SliceInstanceStatus::nodeId)
                      .collect(Collectors.toSet());
    }

    private Artifact fillerArtifact() {
        return Artifact.artifact(FILLER_SLICE).unwrap();
    }

    private void deploy(String blueprintId, String artifact, int instances) {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(blueprintId, artifact, instances);
        var response = postToml(leaderMgmtPort(), "/api/v1/blueprints", blueprint);

        assertThat(response).as("deploy of %s", blueprintId)
                  .doesNotContain("\"error\"")
                  .contains("\"status\":\"applied\"");
    }

    private void undeploy(String blueprintId) {
        var response = delete(leaderMgmtPort(), "/api/v1/blueprints/" + blueprintId);

        assertThat(response).as("undeploy of %s", blueprintId).doesNotContain("\"error\"");
    }

    private int leaderMgmtPort() {
        return cluster.getLeaderManagementPort()
                      .or(() -> cluster.status()
                                       .nodes()
                                       .getFirst()
                                       .mgmtPort());
    }

    private AetherNode leader() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .or(() -> cluster.allNodes()
                                       .getFirst());
    }

    private String postToml(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String delete(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
