// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.forge;

import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Capacity intent must become a committed electorate before retired providers disappear.
/// The three-core starting point is the local development topology, not a production sizing claim.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class HierarchicalCoreResizeTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(180).seconds();
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 31400, 31500, 31600, "resize");

    @AfterEach
    void stop() {
        LifecycleAwait.bestEffort("stop core resize cluster", cluster, cluster.stop());
    }

    @Test
    void growAndShrinkThroughCapacityApi_installElectoratesAndContinueCommitting() {
        LifecycleAwait.settled("start core resize cluster", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().flatMap(cluster::getNode)
            .filter(node -> node.kvStore().get(AetherKey.ClusterConfigKey.CURRENT).isPresent()).isPresent());
        configureSource();
        resize(5);
        awaitElectorate(5);
        commitAndVerify("DEBUG");
        assertThat(leader().coreNodeIds()).allSatisfy(node ->
            assertThat(leader().kvStore().get(new AetherKey.CapacityReservationKey(node)).isPresent())
                .as("provider capacity reservation for %s", node).isTrue());
        resize(3);
        awaitElectorate(3);
        commitAndVerify("INFO");
    }

    private void configureSource() {
        var node = leader();
        var before = node.kvStore().getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class).unwrap();
        var configuration = """
            config_version = "1.0.0"
            [cluster]
            name = "resize"
            version = "1.0.0"
            [source.default]
            type = "forge"
            [source.default.core]
            count = 3
            """;
        var after = new AetherValue.ClusterConfigValue(configuration, "resize", "1.0.0",
            List.of(new AetherValue.TopologyEntry("default", "core", 3)), 3, 11,
            "forge", before.configVersion() + 1, System.currentTimeMillis());
        var id = UUID.randomUUID().toString();
        var authority = node.kvStore().getTyped(LeaderKey.INSTANCE, LeaderValue.class).unwrap();
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(AetherKey.ClusterConfigKey.CURRENT,
            id, authority, List.of(), List.of(new KVCommand.Mutation<>(AetherKey.ClusterConfigKey.CURRENT,
                Option.some(before), Option.some(after))));
        var result = node.<Object>apply(List.of(transaction)).await(BUDGET).unwrap();
        assertThat(result).anyMatch(outcome -> outcome instanceof KVCommand.TransactionResult accepted
            && accepted.transactionId().equals(id) && accepted.accepted());
    }

    private void resize(int count) {
        var node = leader();
        var version = node.kvStore().getTyped(AetherKey.ClusterConfigKey.CURRENT, AetherValue.ClusterConfigValue.class)
            .unwrap().configVersion();
        var body = "{\"source\":\"\",\"role\":\"core\",\"count\":" + count + ",\"expectedVersion\":" + version + "}";
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + node.managementPort() + "/api/v1/cluster/scale"))
            .header("Content-Type", "application/json")
            .timeout(TimeSpan.timeSpan(10).seconds().duration())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = jdkHttpOperations().sendString(request).await(BUDGET).unwrap();
        assertThat(response.statusCode()).as("scale response: %s", response.body()).isEqualTo(200);
    }

    private void awaitElectorate(int count) {
        await().atMost(BUDGET.duration()).untilAsserted(() -> {
            var elected = cluster.currentLeader().flatMap(cluster::getNode);
            assertThat(elected.isPresent()).as("leader elected during electorate handoff").isTrue();
            var node = elected.unwrap();
            var voters = node.coreNodeIds();
            assertThat(voters).as("committed electorate on current leader").hasSize(count);
            assertThat(cluster.allNodes()).as("provider inventory after handoff").hasSize(count);
            assertThat(cluster.allNodes()).allSatisfy(peer -> {
                assertThat(peer.coreNodeIds()).isEqualTo(voters);
                assertThat(peer.isReady()).isTrue();
            });
        });
    }

    private void commitAndVerify(String level) {
        var key = AetherKey.LogLevelKey.forLogger("hierarchy.resize.probe");
        KVCommand<AetherKey> command = new KVCommand.Put<AetherKey, AetherValue>(key,
            AetherValue.LogLevelValue.logLevelValue(key.loggerName(), level));
        LifecycleAwait.settled("commit after electorate resize", cluster, BUDGET, leader().<Object>apply(List.of(command)));
        await().atMost(BUDGET.duration()).until(() -> cluster.allNodes().stream().allMatch(node -> node.kvStore()
            .getTyped(key, AetherValue.LogLevelValue.class).filter(value -> value.level().equals(level)).isPresent()));
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).unwrap();
    }
}
