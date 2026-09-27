// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.api.ClusterEvent;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// #1573 end to end on a real three-node cluster: the echo slice deployed on every node, the real
/// admission-boundary recorder, the real cluster-sync pong, the real leader-side detector, the real
/// RollbackManager and a real KV commit.
///
/// **The failure stimulus.** Every node invokes a method the deployed version does not have through its own
/// local bridge — a [org.pragmatica.aether.slice.SliceDefect.MethodNotFound] on EVERY instance, the shape
/// of a version incompatible with its callers. The rollback target is a seeded rollback record naming an
/// earlier version; that version does not exist as an artifact, so the proof is the committed rollback
/// (SliceTarget and the rollback record), not a successful redeploy of the target.
///
/// Every probe loop is bounded: at most [#MAX_PROBE_ROUNDS] rounds of one call per node.
@Tag("Heavy")
class AutoRollbackOnAllInstancesFailedTest {
    private static final int NODES = 3;
    private static final int SLOTS = 2 * NODES;
    private static final int MGMT_OFFSET = 40;
    private static final int APP_OFFSET = 80;
    /// Disjoint from every other Ember/Forge range surveyed on 2026-09-28 (highest in use: 38400, 45100).
    private static final int FIRST_CANDIDATE_BASE = 42100;
    private static final int LAST_CANDIDATE_BASE = 43900;
    private static final int CANDIDATE_STEP = 200;
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private static final TimeSpan REQUEST = TimeSpan.timeSpan(10).seconds();
    private static final int MAX_PROBE_ROUNDS = 120;
    private static final long QUIET_MS = 20_000L;
    private static final Artifact ARTIFACT = Artifact.artifact(TestArtifacts.ECHO_SLICE).unwrap();
    private static final Version EARLIER = Version.version("0.9.0").unwrap();

    private EmberCluster cluster;
    private int basePort;

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            LifecycleAwait.bestEffort("stop auto-rollback cluster", cluster, cluster.stop());
        }
    }

    @Test
    @Timeout(600)
    void everyInstanceDefective_insideBakeWindow_rollsBackOnce_andRaisesEventAndAlert() {
        startDeployed(RollbackConfig.rollbackConfig());
        seedRollbackRecord(System.currentTimeMillis());

        probeUntil(this::rolledBack);

        assertRolledBackOnce();
        assertEventAndAlert();
    }

    @Test
    @Timeout(600)
    void everyInstanceDefective_rollbackDisabled_raisesEventAndAlert_butNeverRollsBack() {
        startDeployed(RollbackConfig.rollbackConfig(false));
        seedRollbackRecord(System.currentTimeMillis());

        probeUntil(this::alertRaised);
        probeFor(QUIET_MS);

        assertEventAndAlert();
        assertNotRolledBack();
    }

    @Test
    @Timeout(600)
    void everyInstanceDefective_outsideBakeWindow_raisesEventAndAlert_butNeverRollsBack() {
        startDeployed(RollbackConfig.rollbackConfig());
        seedRollbackRecord(System.currentTimeMillis() - TimeSpan.timeSpan(16).minutes().millis());

        probeUntil(this::alertRaised);
        probeFor(QUIET_MS);

        assertEventAndAlert();
        assertNotRolledBack();
    }

    @Test
    @Timeout(600)
    void everyInstanceReturnsBusinessFailures_neverRaisesOrRollsBack() {
        startDeployed(RollbackConfig.rollbackConfig());
        seedRollbackRecord(System.currentTimeMillis());

        var deadline = System.currentTimeMillis() + QUIET_MS;

        while (System.currentTimeMillis() < deadline) {
            cluster.allNodes()
                   .forEach(this::requestBusinessFailure);
            sleep(250);
        }

        assertThat(cluster.allNodes()).as("arming: every instance executed and returned the business failure")
                                      .allMatch(AutoRollbackOnAllInstancesFailedTest::recordedFailures);
        assertThat(alertRaised()).as("a failure the slice returns deliberately is never a defect").isFalse();
        assertNotRolledBack();
    }

    @Test
    @Timeout(600)
    void leaderChangeMidDetection_newLeaderRollsBackExactlyOnce() {
        startDeployed(RollbackConfig.rollbackConfig());
        seedRollbackRecord(System.currentTimeMillis());
        probeRounds(2);
        var oldLeader = cluster.currentLeader().unwrap();

        assertThat(LifecycleAwait.bestEffort("kill leader", cluster, cluster.killNode(oldLeader, false)).isSuccess()).isTrue();
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS)
               .until(() -> cluster.currentLeader().filter(leader -> !leader.equals(oldLeader)).isPresent());

        probeUntil(this::rolledBack);
        probeFor(QUIET_MS);

        assertRolledBackOnce();
    }

    private void startDeployed(RollbackConfig config) {
        basePort = freeBasePort();
        cluster = EmberCluster.emberCluster(NODES, basePort, basePort + MGMT_OFFSET, basePort + APP_OFFSET, "arb");
        cluster.withRollbackConfig(config);
        LifecycleAwait.settled("start auto-rollback cluster", cluster, cluster.start());
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS)
               .until(() -> cluster.currentLeader().isPresent());
        applyBlueprint();
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS)
               .until(() -> cluster.allNodes()
                                   .stream()
                                   .allMatch(this::hostsActiveInstance));
    }

    private void applyBlueprint() {
        var blueprint = """
            id = "forge.test:auto-rollback:1.0.0"
            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(TestArtifacts.ECHO_SLICE, NODES);
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + leaderMgmtPort() + "/api/v1/blueprints"))
                                 .header("Content-Type", "application/toml")
                                 .timeout(REQUEST.duration())
                                 .POST(HttpRequest.BodyPublishers.ofString(blueprint))
                                 .build();

        assertThat(jdkHttpOperations().sendString(request).await(REQUEST).unwrap().body()).contains("\"status\":\"applied\"");
    }

    private boolean hostsActiveInstance(AetherNode node) {
        return leader().kvStore()
                       .getTyped(new AetherKey.NodeArtifactKey(node.self(), ARTIFACT), AetherValue.NodeArtifactValue.class)
                       .filter(value -> value.state() == SliceState.ACTIVE)
                       .isPresent() && node.invocationHandler()
                                           .localSlice(ARTIFACT)
                                           .isPresent();
    }

    /// The rollback record a deploy from [#EARLIER] to the current version would have committed, anchored at
    /// `sinceMs` — inside or outside the bake window.
    private void seedRollbackRecord(long sinceMs) {
        var record = new PreviousVersionValue(ARTIFACT.base(), EARLIER, ARTIFACT.version(), sinceMs, 0, 0, List.of());
        var put = new KVCommand.Put<AetherKey, AetherValue>(PreviousVersionKey.previousVersionKey(ARTIFACT.base()), record);

        assertThat(leader().<Object>apply(List.of(put)).await(REQUEST).isSuccess()).isTrue();
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS)
               .until(() -> leader().kvStore()
                                    .getTyped(PreviousVersionKey.previousVersionKey(ARTIFACT.base()), PreviousVersionValue.class)
                                    .filter(record::equals)
                                    .isPresent());
    }

    private void probeUntil(BooleanSupplier done) {
        for (int round = 0; round < MAX_PROBE_ROUNDS && !done.getAsBoolean(); round++) {
            probeRounds(1);
            sleep(500);
        }

        assertThat(done.getAsBoolean()).as("condition reached within %d probe rounds", MAX_PROBE_ROUNDS).isTrue();
    }

    private void probeFor(long millis) {
        var deadline = System.currentTimeMillis() + millis;

        while (System.currentTimeMillis() < deadline) {
            probeRounds(1);
            sleep(500);
        }
    }

    /// One call per live node, through that node's own local bridge, to a method the deployed version does
    /// not have.
    private void probeRounds(int rounds) {
        for (int round = 0; round < rounds; round++) {
            cluster.allNodes()
                   .forEach(node -> node.invocationHandler()
                                        .localSlice(ARTIFACT)
                                        .onPresent(bridge -> bridge.invoke("methodMissingFromThisVersion", new byte[]{1})
                                                                   .await(REQUEST)));
        }
    }

    private void requestBusinessFailure(AetherNode node) {
        var status = cluster.status()
                            .nodes()
                            .stream()
                            .filter(candidate -> candidate.id().equals(node.self().id()))
                            .findFirst();

        status.ifPresent(candidate -> jdkHttpOperations().sendString(HttpRequest.newBuilder(URI.create("http://localhost:"
                                                                                                        + (candidate.port() + APP_OFFSET)
                                                                                                        + "/fail/500"))
                                                                                .timeout(REQUEST.duration())
                                                                                .GET()
                                                                                .build())
                                                         .await(REQUEST));
    }

    private static boolean recordedFailures(AetherNode node) {
        return node.invocationMetrics()
                   .snapshot()
                   .stream()
                   .filter(snapshot -> snapshot.artifact().equals(ARTIFACT))
                   .anyMatch(snapshot -> snapshot.metrics().failureCount() > 0);
    }

    private boolean rolledBack() {
        return sliceTarget().filter(target -> target.currentVersion().equals(EARLIER)).isPresent();
    }

    private boolean alertRaised() {
        return leader().alertManager()
                       .getActiveSliceFailureAlerts()
                       .stream()
                       .anyMatch(alert -> alert.artifact().equals(ARTIFACT));
    }

    private void assertRolledBackOnce() {
        assertThat(rolledBack()).isTrue();
        var record = leader().kvStore()
                             .getTyped(PreviousVersionKey.previousVersionKey(ARTIFACT.base()), PreviousVersionValue.class)
                             .unwrap();

        assertThat(record.currentVersion()).isEqualTo(EARLIER);
        assertThat(record.rollbackCount()).as("exactly one rollback committed").isEqualTo(1);
        assertThat(record.failedVersions()).containsExactly(ARTIFACT.version());
    }

    private void assertNotRolledBack() {
        assertThat(sliceTarget().map(SliceTargetValue::currentVersion)).as("the target never moved")
                                                                       .contains(ARTIFACT.version());
        assertThat(leader().kvStore()
                           .getTyped(PreviousVersionKey.previousVersionKey(ARTIFACT.base()), PreviousVersionValue.class)
                           .map(PreviousVersionValue::rollbackCount)).contains(0);
    }

    private void assertEventAndAlert() {
        assertThat(alertRaised()).as("the leader's AlertManager holds the slice-failure alert").isTrue();
        await().atMost(BUDGET.millis(), TimeUnit.MILLISECONDS)
               .until(this::sliceFailureEventEmitted);
    }

    private boolean sliceFailureEventEmitted() {
        return leader().eventAggregator()
                       .events()
                       .await(REQUEST)
                       .map(events -> events.stream()
                                            .anyMatch(event -> event instanceof ClusterEvent.SliceFailure failure
                                                               && failure.details()
                                                                         .getOrDefault("artifact", "")
                                                                         .equals(ARTIFACT.asString())))
                       .or(false);
    }

    private Option<SliceTargetValue> sliceTarget() {
        return leader().kvStore()
                       .getTyped(SliceTargetKey.sliceTargetKey(ARTIFACT.base()), SliceTargetValue.class);
    }

    private AetherNode leader() {
        return cluster.currentLeader()
                      .flatMap(cluster::getNode)
                      .unwrap();
    }

    private int leaderMgmtPort() {
        var leaderId = cluster.currentLeader().unwrap();

        return cluster.status()
                      .nodes()
                      .stream()
                      .filter(status -> status.id().equals(leaderId))
                      .findFirst()
                      .orElseThrow()
                      .mgmtPort();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int freeBasePort() {
        for (int base = FIRST_CANDIDATE_BASE; base <= LAST_CANDIDATE_BASE; base += CANDIDATE_STEP) {
            if (blockIsFree(base)) {
                return base;
            }
        }
        throw new AssertionError("no free port block between " + FIRST_CANDIDATE_BASE + " and " + LAST_CANDIDATE_BASE);
    }

    private static boolean blockIsFree(int base) {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (!(udpFree(base + slot) && tcpFree(base + slot) && tcpFree(base + MGMT_OFFSET + slot)
                  && tcpFree(base + APP_OFFSET + slot))) {
                return false;
            }
        }
        return true;
    }

    private static boolean tcpFree(int port) {
        try (var socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean udpFree(int port) {
        try (var socket = new DatagramSocket(null)) {
            socket.setReuseAddress(false);
            socket.bind(loopback(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static InetSocketAddress loopback(int port) {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), port);
    }
}
