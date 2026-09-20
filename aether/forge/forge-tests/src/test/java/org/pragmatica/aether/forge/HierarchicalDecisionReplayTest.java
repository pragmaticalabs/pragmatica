// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.forge;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Live authenticated replay checks state ordering and actual receiver phase-cache eviction.
/// A transparent router observer proves probe-key live apply notifications are not repeated;
/// legitimate snapshot replay notifications are excluded explicitly.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class HierarchicalDecisionReplayTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan REQUEST = TimeSpan.timeSpan(15).seconds();
    private static final AetherKey.LogLevelKey KEY = AetherKey.LogLevelKey.forLogger("hierarchy.replay.probe");
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 35400, 35500, 35600, "replay");

    private Runnable restoreObserver = () -> {};

    @AfterEach void stop() {
        restoreObserver.run();
        cluster.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
        LifecycleAwait.bestEffort("stop decision replay", cluster, cluster.stop());
    }

    @Test void reorderedAndEvictedDecisionsCannotReapplyAnOlderCommittedValue() {
        LifecycleAwait.settled("start replay voters", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().isPresent());
        var receiver = cluster.allNodes().stream().filter(node -> !node.self().equals(leader().self())).findFirst().orElseThrow();
        var notifications = new ConcurrentHashMap<Long, AtomicInteger>();
        restoreObserver = observeApplications(receiver, notifications);
        write(0);
        awaitValue(0);
        var captured = new ConcurrentSkipListMap<Long, Decision<?>>();
        var isolated = new AtomicBoolean(true);
        var permitted = new AtomicReference<Decision<?>>();
        var delivered = new AtomicInteger();
        var probeIngress = new AtomicInteger();
        receiver.setInboundFaultFilter((_, message) -> {
            if (message instanceof Decision<?> decision) {
                long revision = probeRevision(decision);
                if (revision >= 1 && revision <= 12) {
                    captured.putIfAbsent(revision, decision);
                    if (!isolated.get()) probeIngress.incrementAndGet();
                }
                if (decision == permitted.get() || decision.equals(permitted.get())) {
                    delivered.incrementAndGet();
                    return true;
                }
            }
            // Prevent local ballot completion and snapshot repair from masking the withheld Decision.
            return !isolated.get() || !(message instanceof Synchronous);
        });
        write(1);
        write(2);
        await().atMost(REQUEST.duration()).until(() -> captured.containsKey(1L) && captured.containsKey(2L));
        var older = captured.get(1L);
        var newer = captured.get(2L);
        assertThat(newer.phase().value()).isGreaterThan(older.phase().value());
        permitted.set(newer);
        replay(receiver, newer);
        await().atMost(REQUEST.duration()).until(() -> delivered.get() > 0);
        await().during(1, TimeUnit.SECONDS).atMost(3, TimeUnit.SECONDS).untilAsserted(() -> assertValue(receiver, 0));
        isolated.set(false);
        replay(receiver, older);
        awaitValue(2);

        await().atMost(REQUEST.duration()).until(() -> HierarchyAuthorityAcceptanceTest.runtime(receiver).isActive());
        for (int revision = 3; revision <= 12; revision++) write(revision);
        awaitValue(12);
        // Select an actual probe Decision whose phase exists on the replay RECEIVER, not its sender.
        await().atMost(REQUEST.duration()).until(() -> captured.entrySet().stream()
            .anyMatch(entry -> entry.getKey() >= 3 && retained(receiver, entry.getValue())
                              && notificationCount(notifications, entry.getKey()) == 1));
        var evicted = captured.entrySet().stream()
            .filter(entry -> entry.getKey() >= 3 && retained(receiver, entry.getValue())
                             && notificationCount(notifications, entry.getKey()) == 1)
            .findFirst().orElseThrow();
        // Ember retains100 completed phases. Real commits advance beyond this receiver's phase;
        // observe its scheduled cleanup rather than mutating a protocol cache or timer.
        for (int revision = 13; revision <= 125; revision++) write(revision);
        awaitValue(125);
        await().atMost(BUDGET.duration()).until(() -> !retained(receiver, evicted.getValue()));
        long countBefore = notificationCount(notifications, evicted.getKey());
        assertThat(countBefore).isEqualTo(1);
        permitted.set(null);
        int ingressBefore = probeIngress.get();
        var countsBefore = applicationCounts(notifications);
        replay(receiver, newer);
        replay(receiver, evicted.getValue());
        replay(receiver, older);
        replay(receiver, evicted.getValue());
        await().atMost(REQUEST.duration()).until(() -> probeIngress.get() >= ingressBefore + 4);
        await().during(1, TimeUnit.SECONDS).atMost(3, TimeUnit.SECONDS).untilAsserted(() -> {
            assertValue(receiver, 125);
            assertThat(applicationCounts(notifications)).isEqualTo(countsBefore);
            assertThat(notificationCount(notifications, evicted.getKey())).isEqualTo(countBefore);
        });
        write(126);
        awaitValue(126);
        await().atMost(REQUEST.duration()).untilAsserted(() -> assertThat(notificationCount(notifications, 126)).isEqualTo(1));
    }

    private static long probeRevision(Decision<?> decision) {
        return decision.value().commands().stream()
            .filter(command -> command instanceof KVCommand.Put<?, ?> put && KEY.equals(put.key()) && put.value() instanceof AetherValue.LogLevelValue)
            .map(command -> ((AetherValue.LogLevelValue) ((KVCommand.Put<?, ?>) command).value()).updatedAt())
            .findFirst().orElse(-1L);
    }

    private static Map<Long, Integer> applicationCounts(Map<Long, AtomicInteger> notifications) {
        return notifications.entrySet().stream().collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().get()));
    }

    private static int notificationCount(Map<Long, AtomicInteger> notifications, long revision) {
        var count = notifications.get(revision);
        return count == null ? 0 : count.get();
    }

    @SuppressWarnings("unchecked")
    private static Runnable observeApplications(AetherNode node, Map<Long, AtomicInteger> notifications) {
        return Result.lift(() -> {
            var field = KVStore.class.getDeclaredField("router");
            field.setAccessible(true);
            var delegate = (MessageRouter.DelegateRouter) field.get(node.kvStore());
            var current = delegate.getClass().getDeclaredField("delegate");
            current.setAccessible(true);
            var original = (MessageRouter.ImmutableRouter<Message>) current.get(delegate);
            // Transparent test observer: never replace a handler, manufacture a notification,
            // suppress a message, or count legitimate snapshot replay as live application.
            delegate.replaceDelegate(new MessageRouter.ImmutableRouter<Message>() {
                @Override public Map<Class<Message>, List<Consumer<Message>>> routingTable() { return original.routingTable(); }
                @Override public <T extends Message> void route(T message) {
                    if (!node.kvStore().isReplaying() && message instanceof ValuePut<?, ?> put
                        && KEY.equals(put.cause().key()) && put.cause().value() instanceof AetherValue.LogLevelValue value) {
                        notifications.computeIfAbsent(value.updatedAt(), _ -> new AtomicInteger()).incrementAndGet();
                    }
                    original.route(message);
                }
            });
            return (Runnable) () -> delegate.replaceDelegate(original);
        }).unwrap();
    }

    private void write(long revision) {
        leader().<Object>apply(List.of(new KVCommand.Put<>(KEY, value(revision)))).await(REQUEST).unwrap();
    }

    private void replay(AetherNode receiver, Decision<?> decision) {
        var sender = cluster.getNode(decision.sender().id()).unwrap();
        assertThat(HierarchyAuthorityAcceptanceTest.runtime(sender).network()
            .sendOutcome(receiver.self(), decision).await(REQUEST).unwrap().isSent()).isTrue();
    }

    private void awaitValue(long revision) {
        await().atMost(BUDGET.duration()).untilAsserted(() -> cluster.allNodes().forEach(node -> assertValue(node, revision)));
    }

    private static void assertValue(AetherNode node, long revision) {
        assertThat(node.kvStore().getTyped(KEY, AetherValue.LogLevelValue.class).unwrap()).isEqualTo(value(revision));
    }

    private static AetherValue.LogLevelValue value(long revision) {
        return new AetherValue.LogLevelValue(KEY.loggerName(), "INFO", revision);
    }

    private static boolean retained(AetherNode node, Decision<?> decision) {
        // Read-only instrumentation: observe real production cleanup, never alter retention/timers.
        return Result.lift(() -> {
            var runtime = HierarchyAuthorityAcceptanceTest.runtime(node);
            var accessor = runtime.getClass().getDeclaredMethod("consensus");
            accessor.setAccessible(true);
            var engine = accessor.invoke(runtime);
            var phases = engine.getClass().getDeclaredField("phases");
            phases.setAccessible(true);
            return ((Map<?, ?>) phases.get(engine)).containsKey(decision.phase());
        }).unwrap();
    }

    private AetherNode leader() {
        return cluster.currentLeader().flatMap(cluster::getNode).unwrap();
    }
}
