package org.pragmatica.aether.invoke;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.ScheduledTaskRegistry.ScheduledTask;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledTaskRegistryTest {
    private ScheduledTaskRegistry registry;
    private Artifact artifact;
    private Artifact artifact2;
    private MethodName method;
    private MethodName method2;
    private NodeId nodeA;
    private NodeId nodeB;

    @BeforeEach
    void setUp() {
        registry = ScheduledTaskRegistry.scheduledTaskRegistry();
        artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
        artifact2 = Artifact.artifact("org.example:other-slice:1.0.0").unwrap();
        method = MethodName.methodName("cleanup").unwrap();
        method2 = MethodName.methodName("refresh").unwrap();
        nodeA = new NodeId("node-a");
        nodeB = new NodeId("node-b");
    }

    private void putTask(String configSection, Artifact artifact, MethodName method,
                         NodeId node, String interval, String cron, boolean leaderOnly) {
        var key = ScheduledTaskKey.scheduledTaskKey(configSection, artifact, method);
        var value = new ScheduledTaskValue(node, interval, cron, leaderOnly);
        var put = new KVCommand.Put<>(key, value);
        registry.onScheduledTaskPut(new ValuePut<>(put, Option.none()));
    }

    private void removeTask(String configSection, Artifact artifact, MethodName method) {
        var key = ScheduledTaskKey.scheduledTaskKey(configSection, artifact, method);
        var remove = new KVCommand.Remove<ScheduledTaskKey>(key);
        registry.onScheduledTaskRemove(new ValueRemove<>(remove, Option.none()));
    }

    @Nested
    class TaskPut {
        @Test
        void onScheduledTaskPut_addsTask() {
            putTask("cache", artifact, method, nodeA, "30s", "", false);

            var tasks = registry.allTasks();

            assertThat(tasks).hasSize(1);
            var task = tasks.getFirst();
            assertThat(task.configSection()).isEqualTo("cache");
            assertThat(task.artifact()).isEqualTo(artifact);
            assertThat(task.methodName()).isEqualTo(method);
            assertThat(task.registeredBy()).isEqualTo(nodeA);
            assertThat(task.interval()).isEqualTo("30s");
            assertThat(task.isInterval()).isTrue();
            assertThat(task.isCron()).isFalse();
            assertThat(task.leaderOnly()).isFalse();
        }
    }

    @Nested
    class TaskRemove {
        @Test
        void onScheduledTaskRemove_deletesTask() {
            putTask("cache", artifact, method, nodeA, "30s", "", false);

            removeTask("cache", artifact, method);

            assertThat(registry.allTasks()).isEmpty();
        }
    }

    @Nested
    class AllTasks {
        @Test
        void allTasks_returnsAllRegistered() {
            putTask("cache", artifact, method, nodeA, "30s", "", false);
            putTask("metrics", artifact, method2, nodeA, "1m", "", true);
            putTask("billing", artifact2, method, nodeB, "", "0 */5 * * *", true);

            assertThat(registry.allTasks()).hasSize(3);
        }
    }

    @Nested
    class LeaderOnlyTasks {
        @Test
        void leaderOnlyTasks_filtersCorrectly() {
            putTask("cache", artifact, method, nodeA, "30s", "", false);
            putTask("metrics", artifact, method2, nodeA, "1m", "", true);
            putTask("billing", artifact2, method, nodeB, "5m", "", true);

            var leaderOnly = registry.leaderOnlyTasks();

            assertThat(leaderOnly).hasSize(2);
            assertThat(leaderOnly).allMatch(ScheduledTask::leaderOnly);
        }
    }

    @Nested
    class LocalTasks {
        @Test
        void localTasks_filtersByNodeId() {
            putTask("cache", artifact, method, nodeA, "30s", "", false);
            putTask("metrics", artifact, method2, nodeB, "1m", "", true);
            putTask("billing", artifact2, method, nodeA, "5m", "", false);

            var localA = registry.localTasks(nodeA);
            var localB = registry.localTasks(nodeB);

            assertThat(localA).hasSize(2);
            assertThat(localA).allMatch(task -> task.registeredBy().equals(nodeA));
            assertThat(localB).hasSize(1);
            assertThat(localB.getFirst().registeredBy()).isEqualTo(nodeB);
        }
    }

    @Nested
    class ChangeListener {
        @Test
        void setChangeListener_notifiesOnPut() {
            var notifications = new CopyOnWriteArrayList<Option<ScheduledTask>>();
            registry.setChangeListener((_, taskOpt) -> notifications.add(taskOpt));

            putTask("cache", artifact, method, nodeA, "30s", "", false);

            assertThat(notifications).hasSize(1);
            assertThat(notifications.getFirst().isPresent()).isTrue();
        }

        @Test
        void setChangeListener_notifiesOnRemove() {
            putTask("cache", artifact, method, nodeA, "30s", "", false);

            var notifications = new CopyOnWriteArrayList<Option<ScheduledTask>>();
            registry.setChangeListener((_, taskOpt) -> notifications.add(taskOpt));

            removeTask("cache", artifact, method);

            assertThat(notifications).hasSize(1);
            assertThat(notifications.getFirst().isEmpty()).isTrue();
        }
    }

    @Nested
    class ConcurrentAccess {
        @Test
        void concurrentAccess_noExceptions() throws InterruptedException {
            var threadCount = 10;
            var latch = new CountDownLatch(threadCount);

            try (var executor = Executors.newFixedThreadPool(threadCount)) {
                for (int i = 0; i < threadCount; i++) {
                    var index = i;
                    executor.submit(() -> {
                        try {
                            var art = Artifact.artifact("org.example:slice-" + index + ":1.0.0").unwrap();
                            putTask("section-" + index, art, method, nodeA, index + "s", "", false);
                        } finally {
                            latch.countDown();
                        }
                    });
                }
                latch.await();
            }

            assertThat(registry.allTasks()).hasSize(threadCount);
        }
    }
}
