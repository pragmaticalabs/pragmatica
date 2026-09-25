// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeArtifactKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskStateKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeArtifactValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskStateValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.aether.worker.metrics.PerMethodMetrics;
import org.pragmatica.lang.Option;

import org.awaitility.core.ConditionTimeoutException;
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
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


/// #272 R12 — a SINGLE-mode scheduled task fires on the leader, and the leader need not host the
/// slice. The fire is `SliceInvoker.invoke(artifact, method, Unit.unit())`; for a remote endpoint the
/// invoker needed a LOCAL slice bridge to encode the `Unit` request and failed with
/// SENDER_BRIDGE_NOT_FOUND on a leader hosting nothing that knows `Unit`, writing a failure state
/// every interval while the hosting node never saw a call.
///
/// Placement is made deterministic by placement POLICY, not by occupancy tricks (#1495 raised every
/// blueprint slice to at least three instances, so on a three-core cluster the leader always hosted
/// one): three cores plus three workers, the scheduled slice deployed at three instances (CORE_ONLY by
/// default) and then scaled to `WORKERS_ONLY` through the operator's scale route. #1390's
/// `SliceAllocationEngine.reconcilePlacement` migrates the existing instances onto the workers and
/// retires the core ones. The leader is always a core, so once no core hosts the slice the leader
/// provably does not — asserted as a precondition, never assumed.
///
/// Both observables are read in-JVM: the leader's committed `ScheduledTaskStateValue` for the task, and
/// the hosts' per-method invocation counts (recorded by the callee's `InvocationHandler.onInvokeRequest`),
/// so "fired" means a worker host executed it, not merely that the leader believes it sent something.
/// Both are baselined AFTER the migration so fires taken while the cores still hosted cannot count.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScheduledSingleFireHostingTest {
    private static final Logger log = LoggerFactory.getLogger(ScheduledSingleFireHostingTest.class);
    private static final int CORES = 3;
    private static final int WORKERS = 3;
    private static final int INSTANCES = 3;
    private static final int BASE_PORT = 25500;
    private static final int BASE_MGMT_PORT = 25600;
    private static final int BASE_APP_HTTP_PORT = 25700;
    private static final String SCHEDULED_SLICE = "org.pragmatica.aether.test:test-full-full-slice:1.0.0";
    private static final String SCHEDULED_BLUEPRINT = "forge.test:scheduled-single-fire:1.0.0";
    private static final String WORKERS_ONLY = "WORKERS_ONLY";
    private static final String SECTION = "scheduling.heartbeat";
    private static final MethodName METHOD = MethodName.methodName("heartbeat").unwrap();
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final Duration FORM_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration PLACEMENT_TIMEOUT = Duration.ofSeconds(180);
    /// Three 10s intervals plus slack: two fires (or two failure states) must have landed.
    private static final Duration FIRE_WINDOW = Duration.ofSeconds(45);
    private static final Duration POLL = Duration.ofMillis(500);
    private static final int EXPECTED_FIRES = 2;

    private final HttpOperations http = jdkHttpOperations();
    private final Artifact scheduledArtifact = Artifact.artifact(SCHEDULED_SLICE).unwrap();
    private EmberCluster cluster;
    private List<NodeId> workers;

    @BeforeAll
    void setUp(@TempDir Path baseDir) {
        // A node config provider is what lets `SliceStore` layer the slice jar's `resources.toml`
        // (`[scheduling.heartbeat]`) into a slice composite; without one the activation publishes no
        // scheduled task at all, silently, and this test would measure nothing.
        var configProvider = ConfigurationProvider.builder()
                                                  .withSystemProperties("aether.")
                                                  .withEnvironment("AETHER_")
                                                  .build();

        cluster = emberCluster(CORES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "sched", some(configProvider));
        cluster.withDataBaseDir(baseDir);
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().atMost(FORM_TIMEOUT).pollInterval(POLL).until(() -> cluster.currentLeader()
                                                                           .isPresent());
        await().atMost(FORM_TIMEOUT)
             .pollInterval(POLL)
             .until(() -> leader().membershipFsm()
                                .coreCountedMembers()
                                .size() == CORES);
        workers = IntStream.range(0, WORKERS)
                           .mapToObj(_ -> LifecycleAwait.nodeSettled("admit worker in setUp()", cluster, cluster.addWorkerNode()))
                           .toList();
        await().atMost(FORM_TIMEOUT)
             .pollInterval(POLL)
             .until(() -> workers.stream()
                                 .allMatch(id -> cluster.getNode(id.id())
                                                        .filter(AetherNode::isReady)
                                                        .isPresent()));
    }

    @AfterAll
    void tearDown() {
        option(cluster).onPresent(c -> LifecycleAwait.bestEffort("cluster stop in tearDown()", c, c.stop()));
    }

    @Test
    void singleModeTask_firesOnTheHostingNode_whenTheLeaderDoesNotHostIt() {
        var hosts = placeScheduledSliceOnWorkersOnly();
        var leaderId = cluster.currentLeader().or("");
        var executionsBefore = taskState().map(ScheduledTaskStateValue::totalExecutions).or(0);
        var callsBefore = heartbeatCallsOn(hosts);

        log.info("SSF: leader={} workerHosts={} — watching {} fires over {}s (baseline executions={} calls={})",
                 leaderId,
                 hosts,
                 EXPECTED_FIRES,
                 FIRE_WINDOW.toSeconds(),
                 executionsBefore,
                 callsBefore);
        awaitFiresOrFailures(hosts, callsBefore, executionsBefore);
        var state = taskState();
        var hostCalls = heartbeatCallsOn(hosts) - callsBefore;
        // Logged before the assertions so a red run carries its own picture.
        log.info("SSF RESULT: leader={} workerHosts={} hostHeartbeatCalls={} state={} taskRegistered={}",
                 leaderId,
                 hosts,
                 hostCalls,
                 state,
                 taskRegisteredOnEveryNode());
        assertThat(hostCalls).as("#272 R12: the SINGLE-mode fire must reach a hosting worker's slice (%d intervals elapsed)",
                                 EXPECTED_FIRES)
                  .isGreaterThanOrEqualTo(EXPECTED_FIRES);
        assertThat(state.map(ScheduledTaskStateValue::consecutiveFailures).or(-1)).as("#272 R12: the leader must not record failures for a task it does not host (last failure: %s)",
                                                                                      state.map(ScheduledTaskStateValue::lastFailureMessage)
                                                                                           .or("<none>"))
                  .isZero();
        assertThat(state.map(ScheduledTaskStateValue::totalExecutions).or(0) - executionsBefore).as("#272 R12: the leader's task state must count the fires taken after the migration")
                  .isGreaterThanOrEqualTo(EXPECTED_FIRES);
    }

    /// Deploys the scheduled slice at [#INSTANCES] (CORE_ONLY by default), then scales it to
    /// `WORKERS_ONLY` through the scale route and waits for the migration. The preconditions are
    /// asserted, never assumed: exactly [#INSTANCES] ACTIVE hosts, every one a worker, and NO core —
    /// the leader included — hosting the slice. Without the placement change the cores keep their
    /// instances and the precondition fails loudly rather than letting the R12 assertions run on a
    /// leader that hosts the slice.
    private Set<NodeId> placeScheduledSliceOnWorkersOnly() {
        deploy(SCHEDULED_BLUEPRINT, SCHEDULED_SLICE, INSTANCES);
        await().atMost(PLACEMENT_TIMEOUT).pollInterval(POLL).until(() -> activeHosts().size() == INSTANCES);
        log.info("SSF: deployed on {} (cores={})", activeHosts(), coreMembers());

        var scaled = scaleToWorkersOnly();

        assertThat(scaled).as("scale of %s to %s", SCHEDULED_SLICE, WORKERS_ONLY)
                  .doesNotContain("\"error\"");
        awaitNoCoreHosts();

        var hosts = activeHosts();
        var cores = coreMembers();
        var leaderId = cluster.currentLeader().or("");

        assertThat(hosts).as("precondition: exactly %d ACTIVE hosts after the WORKERS_ONLY migration", INSTANCES)
                  .hasSize(INSTANCES);
        assertThat(hosts).as("precondition: every host must be a worker (workers=%s)", workers)
                  .allMatch(workers::contains);
        assertThat(hosts).as("precondition: no core node may host the slice (cores=%s)", cores)
                  .doesNotContainAnyElementsOf(cores);
        assertThat(hosts.stream()
                        .map(NodeId::id)
                        .toList()).as("precondition: the leader must not host the slice (leader=%s)", leaderId)
                  .doesNotContain(leaderId);

        return hosts;
    }

    private void awaitNoCoreHosts() {
        try {
            await().atMost(PLACEMENT_TIMEOUT)
                 .pollInterval(POLL)
                 .until(() -> {
                     var hosts = activeHosts();

                     return hosts.size() == INSTANCES && hosts.stream()
                                                             .noneMatch(coreMembers()::contains);
                 });
        } catch (ConditionTimeoutException timeout) {
            // Fall through to the precondition assertions, which name the offending hosts.
            log.warn("SSF: placement did not converge to workers-only within {}s: hosts={} cores={}",
                     PLACEMENT_TIMEOUT.toSeconds(),
                     activeHosts(),
                     coreMembers());
        }
    }

    /// Waits for BOTH observables to reach the expected fires (or for the failure count to), because the
    /// host records a call the moment it executes while the leader's `ScheduledTaskStateValue` lands only
    /// after its consensus commit: gating on the host count alone let a run read the state one fire
    /// behind (host +2, executions +1) and fail on commit latency rather than on R12.
    private void awaitFiresOrFailures(Set<NodeId> hosts, long callsBefore, int executionsBefore) {
        try {
            await().atMost(FIRE_WINDOW)
                 .pollInterval(POLL)
                 .until(() -> firesObserved(hosts, callsBefore, executionsBefore) || taskState().map(ScheduledTaskStateValue::consecutiveFailures)
                                                                                                .or(0) >= EXPECTED_FIRES);
        } catch (ConditionTimeoutException timeout) {
            log.warn("SSF: neither {} fires nor {} failures within {}s",
                     EXPECTED_FIRES,
                     EXPECTED_FIRES,
                     FIRE_WINDOW.toSeconds());
        }
    }

    private boolean firesObserved(Set<NodeId> hosts, long callsBefore, int executionsBefore) {
        var executions = taskState().map(ScheduledTaskStateValue::totalExecutions)
                                    .or(0) - executionsBefore;

        return heartbeatCallsOn(hosts) - callsBefore >= EXPECTED_FIRES && executions >= EXPECTED_FIRES;
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

    private long heartbeatCallsOn(Set<NodeId> hosts) {
        return hosts.stream()
                    .mapToLong(this::heartbeatCallsOn)
                    .sum();
    }

    private long heartbeatCallsOn(NodeId nodeId) {
        return cluster.getNode(nodeId.id())
                      .map(node -> node.invocationMetrics()
                                       .collectPerSliceMetrics()
                                       .stream()
                                       .filter(slice -> slice.artifact()
                                                             .equals(scheduledArtifact))
                                       .flatMap(slice -> slice.methods()
                                                              .stream())
                                       .filter(method -> method.method()
                                                               .equals(METHOD.name()))
                                       .mapToLong(PerMethodMetrics::totalCalls)
                                       .sum())
                      .or(0L);
    }

    /// Nodes with an ACTIVE instance of the scheduled slice, from the leader's committed KV.
    private Set<NodeId> activeHosts() {
        return cluster.allNodes()
                      .stream()
                      .map(AetherNode::self)
                      .filter(node -> leader().kvStore()
                                            .getTyped(new NodeArtifactKey(node, scheduledArtifact), NodeArtifactValue.class)
                                            .filter(value -> value.state() == SliceState.ACTIVE)
                                            .isPresent())
                      .collect(Collectors.toSet());
    }

    private Set<NodeId> coreMembers() {
        return Set.copyOf(leader().membershipFsm()
                                  .coreCountedMembers());
    }

    private String scaleToWorkersOnly() {
        var body = "{\"artifact\":\"%s\",\"instances\":%d,\"placement\":\"%s\"}".formatted(SCHEDULED_SLICE,
                                                                                            INSTANCES,
                                                                                            WORKERS_ONLY);

        return post(leaderMgmtPort(), "/api/v1/scale", body, "application/json");
    }

    private void deploy(String blueprintId, String artifact, int instances) {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(blueprintId, artifact, instances);
        var response = post(leaderMgmtPort(), "/api/v1/blueprints", blueprint, "application/toml");

        assertThat(response).as("deploy of %s", blueprintId)
                  .doesNotContain("\"error\"")
                  .contains("\"status\":\"applied\"");
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

    private String post(int port, String path, String body, String contentType) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", contentType)
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
