// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.forge;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherKey.LogLevelKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.LogLevelValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Exercises durable consensus through production node assembly and real transport. Protocol
/// crash-boundary tests cover interrupted writes separately; this test covers complete restart.
@Execution(ExecutionMode.SAME_THREAD)
class HierarchicalCoreRestartTest {
    private static final TimeSpan CONVERGENCE = TimeSpan.timeSpan(90).seconds();
    private static final LogLevelKey KEY = LogLevelKey.forLogger("hierarchy.restart.probe");
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 28650, 28750, 28850, "hcr");

    @AfterEach
    void stopCluster() {
        LifecycleAwait.settled("stop durable hierarchy cluster", cluster, cluster.stop());
    }

    @Test
    void allCoresRestartWithCommittedStateAndContinueCommitting() {
        LifecycleAwait.settled("form durable hierarchy cluster", cluster, cluster.start());
        awaitLeader();
        commit("DEBUG");
        awaitValue("DEBUG");
        LifecycleAwait.settled("stop all cores before recovery", cluster, cluster.stop());
        LifecycleAwait.settled("restart cores with their original journals", cluster, cluster.start());
        awaitLeader();
        awaitValue("DEBUG");
        commit("INFO");
        awaitValue("INFO");
        assertThat(cluster.nodeCount()).isEqualTo(3);
    }

    private void awaitLeader() {
        await().atMost(CONVERGENCE.duration()).until(() -> cluster.currentLeader().isPresent());
    }

    private void commit(String level) {
        KVCommand<AetherKey> command = new KVCommand.Put<AetherKey, AetherValue>(KEY,
            LogLevelValue.logLevelValue(KEY.loggerName(), level));
        LifecycleAwait.settled("commit restart probe " + level, cluster, CONVERGENCE,
            leader().<Object>apply(List.of(command)));
    }

    private AetherNode leader() {
        return cluster.getNode(cluster.currentLeader().unwrap()).unwrap();
    }

    private void awaitValue(String level) {
        await().atMost(CONVERGENCE.duration()).until(() -> cluster.getNodeInfos().size() == 3
            && cluster.getNodeInfos().stream().allMatch(info -> cluster.getNode(info.id().id())
                .flatMap(node -> node.kvStore().getTyped(KEY, LogLevelValue.class))
                .filter(value -> value.level().equals(level)).isPresent()));
    }
}
