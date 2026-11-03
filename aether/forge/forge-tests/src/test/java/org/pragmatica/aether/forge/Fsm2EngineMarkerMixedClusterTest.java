// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.InstanceId;
import org.pragmatica.aether.environment.InstanceInfo;
import org.pragmatica.aether.environment.ProviderDefaults;
import org.pragmatica.aether.environment.ProvisionRequest;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.node.deployment.EngineMarkerGuard;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// W-29's cross-process arms (phase-3 design 8.2 and 9.2; MT-24, MT-29, MT-30): the engine marker keeps a node of one engine out of a cluster of
/// the other, and **every refusal is a node refusing itself**.
///
/// - (i) a slice-free 5-node LEGACY cluster and one FSM2 observer newcomer;
/// - (ii) a 5-node FSM2 cluster running a slice and one LEGACY observer newcomer;
/// - late genesis: FSM2 members that are up before the lineage mint commits (both keys absent).
///
/// Each mixed arm runs at seven cores: the five, the newcomer, and a correctly configured filler, because a core count must be odd (and a sixth
/// core alone is drained as surplus before its window ends). The newcomer sorts below every member, and the leader is stopped inside the
/// newcomer's WARN window, so an election takes place while the newcomer is closed.
///
/// **A newcomer that LEADS is not reached here** (design 9.2 asks for one that wins an election). CTO ruling 3 (know `c0b4ae539`) accepts it at
/// unit level for P3-F (`EngineMarkerGuardTest`, `Fsm2SystemTest`: a mismatching node activates nothing before it exits), because Ember
/// cannot restart an admitted voter with another engine; a restart-with-engine seam is a P3-H item. No joining core was seen admitted as a voter in
/// this harness: the correctly configured filler stayed an observer for the four minutes a probe waited. So the newcomer cannot be elected, and
/// refuses itself as an observer (window 15 s at seven cores). The arms assert that the closed newcomer is never admitted: with the activation
/// gate removed (MT-30) it IS admitted, and the arms go red `[unverified: why a closed node is not admitted while an open one is, and why the
/// filler is not; read from the probes, not traced in the source]`.
///
/// A sampler reads every live node's committed mirror and the newcomer's guard every 25 ms for the whole arm.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class Fsm2EngineMarkerMixedClusterTest {
    private static final int MAJORITY = 5;
    private static final Duration WAIT = Duration.ofSeconds(240);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(90);
    private static final Duration REFUSAL_BOUND = Duration.ofSeconds(90);
    private static final String BLUEPRINT_ID = "forge.test:w29-deploy:1.0.0";
    private static final Pattern CONFIG_VERSION = Pattern.compile("\"configVersion\"\\s*:\\s*(\\d+)");

    private final HttpOperations http = jdkHttpOperations();
    private EmberCluster cluster;
    private Sampler sampler;
    private String stoppedLeader = "";

    @AfterEach
    void tearDown() {
        if (sampler != null) {
            sampler.stop();
        }
        if (cluster != null) {
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    // W-29 arm (i), with the newcomer an observer (ruling 3: the leading case is unit-covered).
    // Red when: a LEGACY member exits; the FSM2 newcomer writes an FSM2 record (`NodeDepartureKey`) or a `SliceDesiredKey`, or opens its guard;
    // the marker changes; or the newcomer does not refuse itself.
    @Test
    void anFsm2ObserverNewcomer_inASliceFreeLegacyCluster_writesNothing_andOnlyItRefuses() {
        var prefix = "w29i";

        startCluster(prefix, DeploymentEngine.LEGACY, 26400);
        sampler = Sampler.start(cluster, newcomerOf(prefix), Fsm2EngineMarkerMixedClusterTest::isFsm2Write);
        takeOver(prefix, DeploymentEngine.FSM2);

        assertArm(prefix, DeploymentEngine.LEGACY);
    }

    // W-29 arm (ii); MT-30 (the mismatch activation gate removed lets the LEGACY newcomer run CDS during its window).
    // Red when: an FSM2 member exits; the LEGACY newcomer writes a `NodeArtifactKey` goal, or opens its guard; the marker changes; or the newcomer
    // does not refuse itself.
    @Test
    void aLegacyObserverNewcomer_inAnFsm2ClusterThatRunsASlice_writesNoGoal_andOnlyItRefuses() {
        var prefix = "w29ii";

        startCluster(prefix, DeploymentEngine.FSM2, 26700);
        deployEchoSlice();
        sampler = Sampler.start(cluster, newcomerOf(prefix), key -> key instanceof AetherKey.NodeArtifactKey);
        takeOver(prefix, DeploymentEngine.LEGACY);

        assertArm(prefix, DeploymentEngine.FSM2);
    }

    // W-29 late-genesis arm; MT-29 (both keys absent treated as LEGACY).
    // Red when: a member that is up while both keys are absent is ever in a mismatch state or refuses, or any member exits.
    @Test
    void fsm2Members_upBeforeTheMintCommits_waitAndNeverRefuse() {
        cluster = emberCluster(3, 27000, 27100, 27200, "w29g");
        cluster.withDeploymentEngine(DeploymentEngine.FSM2);
        sampler = Sampler.start(cluster, "", _ -> false);
        LifecycleAwait.settled("cluster start", cluster, cluster.start());
        awaitHealthyLeaderWithMarker(DeploymentEngine.FSM2);
        await().atMost(WAIT).until(() -> cluster.allNodes().stream().allMatch(node -> guardOf(node) == EngineMarkerGuard.State.OPEN));
        sampler.stop();

        System.out.println("W29 late-genesis guard states seen: " + sampler.states());
        assertThat(sampler.states()).as("the arm saw a member waiting on both keys absent").contains("WAITING");
        assertThat(sampler.states()).as("no member was ever in a mismatch state or refused").containsOnly("WAITING", "OPEN");
        assertThat(cluster.allNodes().stream().map(node -> node.self().id()).filter(id -> cluster.exitCodeOf(id).isPresent()).toList())
                  .as("no member exited").isEmpty();
    }

    private void startCluster(String prefix, DeploymentEngine engine, int basePort) {
        cluster = emberCluster(MAJORITY, basePort, basePort + 100, basePort + 200, prefix);
        cluster.withDeploymentEngine(engine);
        cluster.withComputeProviderDecorator(BlockedProvisioning::new);
        LifecycleAwait.settled("cluster start", cluster, cluster.start());
        awaitHealthyLeaderWithMarker(engine);
    }

    private void awaitHealthyLeaderWithMarker(DeploymentEngine engine) {
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).until(() -> cluster.currentLeader().isPresent());
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).until(this::allNodesHealthy);
        await().atMost(WAIT).pollInterval(Duration.ofMillis(250)).until(() -> markerOf(leader()).equals(Option.some(engine)));
    }

    /// The misconfigured newcomer joins under the lowest NodeId, becomes a voter, and the leader stops, so the newcomer ranks first in the
    /// election that follows. A core count must be odd (`/api/v1/cluster/scale` refuses 6), so the count is raised to seven, with provisioning
    /// blocked, and a correctly configured filler (sorting last) takes the seventh place; neither is then drained as surplus.
    private void takeOver(String prefix, DeploymentEngine newcomerEngine) {
        var newcomer = newcomerOf(prefix);
        var oldLeader = cluster.currentLeader().unwrap();

        stoppedLeader = oldLeader;

        scaleCoresTo(MAJORITY + 2);
        cluster.withDeploymentEngine(newcomer, newcomerEngine);
        var joined = cluster.addCoreNode(newcomer);
        var filled = cluster.addCoreNode(fillerOf(prefix));

        await().atMost(WAIT).pollInterval(Duration.ofMillis(100)).until(() -> sampler.sequence().contains("MISMATCH_WINDOW") || cluster.exitCodeOf(newcomer).isPresent());
        sampler.note("in its window; voter=" + isVoter(newcomer));
        LifecycleAwait.nodeSettled("stop the leader " + oldLeader, cluster, cluster.killNode(oldLeader));
        sampler.note("leader " + oldLeader + " stopped");
        await().atMost(REFUSAL_BOUND).pollInterval(Duration.ofMillis(100)).until(() -> sampler.voter(isVoter(newcomer)) && cluster.exitCodeOf(newcomer).isPresent());
        sampler.note("newcomer exited with " + cluster.exitCodeOf(newcomer));
        LifecycleAwait.bestEffort("the newcomer's start", cluster, joined);
        LifecycleAwait.nodeSettled("the filler's start", cluster, filled);
        await().atMost(WAIT).pollInterval(Duration.ofMillis(500)).until(() -> cluster.currentLeader().filter(id -> !id.equals(newcomer)).isPresent());
        sampler.stop();
    }

    private void assertArm(String prefix, DeploymentEngine clusterEngine) {
        var newcomer = newcomerOf(prefix);

        System.out.println("W29 " + prefix + " timeline: " + sampler.timeline());
        assertThat(sampler.wasVoter()).as("the closed newcomer was never admitted as a voter, so it could not be elected").isFalse();
        assertThat(sampler.leadersSeen()).as("an observer is never elected").doesNotContain(newcomer);
        assertThat(sampler.writes()).as("the misconfigured newcomer wrote nothing of its engine").isEmpty();
        assertThat(sampler.afterFirstMismatch()).as("the newcomer's guard never opened once it saw the marker; sequence " + sampler.sequence())
                                                .isNotEmpty()
                                                .doesNotContain("OPEN");
        assertThat(cluster.exitCodeOf(newcomer)).as("the newcomer refused itself").isEqualTo(Option.some(AetherNode.EXIT_IDENTITY_REFUSED));
        assertThat(majority(prefix).stream().filter(id -> !id.equals(stoppedLeader)).filter(id -> cluster.exitCodeOf(id).isPresent()).toList())
                  .as("no member of the majority exited (the leader the test stopped aside)").isEmpty();
        assertThat(markerOf(leader())).as("the committed marker is the majority's").isEqualTo(Option.some(clusterEngine));
    }

    private static String newcomerOf(String prefix) {
        return prefix + "-0";
    }

    private static String fillerOf(String prefix) {
        return prefix + "-9";
    }

    /// The five initial members and the filler.
    private static List<String> majority(String prefix) {
        return IntStream.concat(IntStream.rangeClosed(1, MAJORITY), IntStream.of(9)).mapToObj(i -> prefix + "-" + i).toList();
    }

    private static boolean isFsm2Write(Object key) {
        return key instanceof AetherKey.NodeDepartureKey || key instanceof AetherKey.SliceDesiredKey;
    }

    /// Every other live node has installed the newcomer as a voter: only then is it eligible in an election.
    private boolean isVoter(String nodeId) {
        return cluster.allNodes()
                      .stream()
                      .filter(node -> !node.self().id().equals(nodeId))
                      .allMatch(node -> node.coreNodeIds().stream().anyMatch(voter -> voter.id().equals(nodeId)));
    }

    private static Option<DeploymentEngine> markerOf(AetherNode node) {
        return node.kvStore()
                   .getTyped(AetherKey.DeploymentEngineKey.deploymentEngineKey(), AetherValue.DeploymentEngineValue.class)
                   .map(AetherValue.DeploymentEngineValue::engine);
    }

    private static EngineMarkerGuard.State guardOf(AetherNode node) {
        return node.deploymentWiring().guard().state();
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).or(() -> cluster.allNodes().getFirst());
    }

    private boolean allNodesHealthy() {
        return cluster.status().nodes().stream().allMatch(node -> httpGet(node.mgmtPort(), "/api/v1/health").contains("\"quorum\":true"));
    }

    private void deployEchoSlice() {
        var id = BlueprintId.blueprintId(BLUEPRINT_ID).unwrap();
        var artifact = Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap();
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, TestArtifacts.ECHO_SLICE, MAJORITY);
        var response = send(HttpRequest.newBuilder()
                                       .uri(URI.create("http://localhost:" + cluster.getLeaderManagementPort().unwrap() + "/api/v1/blueprints"))
                                       .header("Content-Type", "application/toml")
                                       .POST(HttpRequest.BodyPublishers.ofString(blueprint))
                                       .timeout(Duration.ofSeconds(10))
                                       .build());

        assertThat(response).describedAs("SETUP: blueprint deployment").doesNotContain("\"error\"").contains("\"status\":\"applied\"");
        await().atMost(DEPLOY_TIMEOUT).pollInterval(Duration.ofMillis(250)).until(() -> leader().blueprintService()
                                                                                                  .outcome(id)
                                                                                                  .map(outcome -> outcome.status() == DeploymentOutcomeStatus.SUCCEEDED)
                                                                                                  .or(false));
        await().atMost(DEPLOY_TIMEOUT).pollInterval(Duration.ofMillis(250)).until(() -> cluster.allNodes().stream().allMatch(node -> node.invocationHandler().localSlice(artifact).isPresent()));
    }

    private void scaleCoresTo(int count) {
        var port = cluster.getLeaderManagementPort().unwrap();
        var matcher = CONFIG_VERSION.matcher(httpGet(port, "/api/v1/cluster/config"));

        assertThat(matcher.find()).as("SETUP: the cluster config version is readable").isTrue();
        var body = "{\"source\":\"\",\"role\":\"core\",\"count\":" + count + ",\"expectedVersion\":" + matcher.group(1) + "}";
        var response = send(HttpRequest.newBuilder()
                                       .uri(URI.create("http://localhost:" + port + "/api/v1/cluster/scale"))
                                       .header("Content-Type", "application/json")
                                       .POST(HttpRequest.BodyPublishers.ofString(body))
                                       .timeout(Duration.ofSeconds(10))
                                       .build());

        assertThat(response).as("SETUP: the scale to " + count + " cores was accepted").contains("\"newCount\":" + count);
    }

    private String httpGet(int port, String path) {
        return send(HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().timeout(Duration.ofSeconds(10)).build());
    }

    private String send(HttpRequest request) {
        return http.sendString(request).await().map(HttpResult::body).or("{\"error\":\"request failed\"}");
    }

    /// The newcomer is added by the test, never by auto-heal, so the cluster's raised core count does not provision a node of its own.
    private static final class BlockedProvisioning implements ComputeProvider {
        private final ComputeProvider delegate;

        private BlockedProvisioning(ComputeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public ProviderDefaults providerDefaults() {
            return delegate.providerDefaults();
        }

        @Override
        public Promise<InstanceInfo> createFrom(ProvisionRequest request) {
            return EnvironmentError.capacityUnavailable("", new RuntimeException("provisioning blocked by the test (W-29)")).promise();
        }

        @Override
        public Promise<Unit> terminate(InstanceId instanceId) {
            return delegate.terminate(instanceId);
        }

        @Override
        public Promise<List<InstanceInfo>> listInstances() {
            return delegate.listInstances();
        }

        @Override
        public Promise<InstanceInfo> instanceStatus(InstanceId instanceId) {
            return delegate.instanceStatus(instanceId);
        }
    }

    /// Reads, every 25 ms: the keys matching `forbidden` in any live node's committed mirror, the guard state of `watched` (every node when
    /// `watched` is blank), and the leader. A key already present at the first read is not a write of the arm.
    private static final class Sampler {
        private final EmberCluster cluster;
        private final String watched;
        private final Predicate<Object> forbidden;
        private final Set<Object> baseline;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicBoolean wasVoter = new AtomicBoolean(false);
        private final Set<String> states = ConcurrentHashMap.newKeySet();
        private final Set<String> leaders = ConcurrentHashMap.newKeySet();
        private final Set<Object> writes = ConcurrentHashMap.newKeySet();
        private final ConcurrentLinkedQueue<String> timeline = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<String> sequence = new ConcurrentLinkedQueue<>();
        private final long started = System.nanoTime();
        private Thread thread;
        private String lastState = "";

        private Sampler(EmberCluster cluster, String watched, Predicate<Object> forbidden) {
            this.cluster = cluster;
            this.watched = watched;
            this.forbidden = forbidden;
            this.baseline = matching();
        }

        static Sampler start(EmberCluster cluster, String watched, Predicate<Object> forbidden) {
            var sampler = new Sampler(cluster, watched, forbidden);

            sampler.thread = Thread.ofVirtual().name("w29-sampler").start(sampler::loop);

            return sampler;
        }

        private void loop() {
            while (running.get()) {
                sample();
                try {
                    Thread.sleep(25);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }

        private void sample() {
            matching().stream().filter(key -> !baseline.contains(key)).forEach(this::wrote);
            for (var node : cluster.allNodes()) {
                if (watched.isBlank() || node.self().id().equals(watched)) {
                    guard(node.self().id(), node.deploymentWiring().guard().state().name());
                }
            }
            cluster.currentLeader().onPresent(leader -> seen(leaders, "leader", leader));
        }

        private Set<Object> matching() {
            var found = new HashSet<>();

            for (var node : cluster.allNodes()) {
                Set<?> keys = node.kvStore().snapshot().keySet();

                // the committed mirror holds the consensus's own keys (LeaderKey) beside the AetherKeys its type names
                keys.stream().map(Object.class::cast).filter(forbidden).forEach(found::add);
            }

            return found;
        }

        private void wrote(Object key) {
            if (writes.add(key)) {
                note("write " + key);
            }
        }

        private void guard(String node, String state) {
            states.add(state);
            if (!state.equals(lastState)) {
                lastState = state;
                sequence.add(state);
                note(node + " guard " + state);
            }
        }

        private void seen(Set<String> set, String what, String value) {
            if (set.add(value)) {
                note(what + " " + value);
            }
        }

        /// Records whether `voter` was ever true; always true, so it can sit in an await condition.
        boolean voter(boolean voter) {
            if (voter) {
                wasVoter.set(true);
            }

            return true;
        }

        boolean wasVoter() {
            return wasVoter.get();
        }

        void note(String event) {
            timeline.add((System.nanoTime() - started) / 1_000_000L + "ms " + event);
        }

        void stop() {
            running.set(false);
            if (thread != null) {
                try {
                    thread.join(5_000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        Set<String> states() {
            return Set.copyOf(states);
        }

        List<String> sequence() {
            return List.copyOf(sequence);
        }

        /// The guard states from the first mismatch on: the WARN window and after.
        List<String> afterFirstMismatch() {
            var all = sequence();

            return all.stream().filter(state -> state.startsWith("MISMATCH")).findFirst().map(first -> all.subList(all.indexOf(first), all.size())).orElse(List.of());
        }

        Set<String> leadersSeen() {
            return Set.copyOf(leaders);
        }

        Set<Object> writes() {
            return Set.copyOf(writes);
        }

        List<String> timeline() {
            return List.copyOf(timeline);
        }
    }
}
