// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.forge;

import java.util.List;
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

/// Live authenticated replay complements the unit tests' exact notification-count assertions.
/// The runtime proof checks visible state ordering and replay after actual phase-cache eviction.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
class HierarchicalDecisionReplayTest {
    private static final TimeSpan BUDGET = TimeSpan.timeSpan(120).seconds();
    private static final TimeSpan REQUEST = TimeSpan.timeSpan(15).seconds();
    private static final AetherKey.LogLevelKey KEY = AetherKey.LogLevelKey.forLogger("hierarchy.replay.probe");
    private final EmberCluster cluster = EmberCluster.emberCluster(3, 35400, 35500, 35600, "replay");

    @AfterEach void stop() {
        cluster.allNodes().forEach(node -> node.setInboundFaultFilter((_, _) -> true));
        LifecycleAwait.bestEffort("stop decision replay", cluster, cluster.stop());
    }

    @Test void reorderedAndEvictedDecisionsCannotReapplyAnOlderCommittedValue() {
        LifecycleAwait.settled("start replay voters", cluster, cluster.start());
        await().atMost(BUDGET.duration()).until(() -> cluster.currentLeader().isPresent());
        write(0);
        awaitValue(0);
        var receiver = cluster.allNodes().stream().filter(node -> !node.self().equals(leader().self())).findFirst().orElseThrow();
        var captured = new ConcurrentSkipListMap<Long, Decision<?>>();
        var isolated = new AtomicBoolean(true);
        var permitted = new AtomicReference<Decision<?>>();
        var delivered = new AtomicInteger();
        receiver.setInboundFaultFilter((_, message) -> {
            if (message instanceof Decision<?> decision) {
                if (decision == permitted.get() || decision.equals(permitted.get())) {
                    delivered.incrementAndGet();
                    return true;
                }
                if (isolated.get()) captured.putIfAbsent(decision.phase().value(), decision);
            }
            // Prevent local ballot completion and snapshot repair from masking the withheld Decision.
            return !isolated.get() || !(message instanceof Synchronous);
        });
        write(1);
        write(2);
        await().atMost(REQUEST.duration()).until(() -> captured.size() >= 2);
        var older = captured.firstEntry().getValue();
        var newer = captured.lastEntry().getValue();
        assertThat(newer.phase().value()).isGreaterThan(older.phase().value());
        permitted.set(newer);
        replay(receiver, newer);
        await().atMost(REQUEST.duration()).until(() -> delivered.get() > 0);
        await().during(1, TimeUnit.SECONDS).atMost(3, TimeUnit.SECONDS).untilAsserted(() -> assertValue(receiver, 0));
        isolated.set(false);
        replay(receiver, older);
        awaitValue(2);

        var evictionOwner = cluster.getNode(older.sender().id()).unwrap();
        assertThat(retained(evictionOwner, older)).as("the captured phase exists before normal cleanup").isTrue();
        // Ember retains 100 completed phases. These are sequential committed application batches,
        // not synthetic protocol messages; the normal periodic cleanup must actually remove them.
        for (int revision = 3; revision <= 112; revision++) write(revision);
        awaitValue(112);
        await().atMost(BUDGET.duration()).until(() -> !retained(evictionOwner, older));
        permitted.set(null);
        replay(receiver, newer);
        replay(receiver, older);
        replay(receiver, newer);
        replay(receiver, older);
        await().during(1, TimeUnit.SECONDS).atMost(3, TimeUnit.SECONDS).untilAsserted(() -> assertValue(receiver, 112));
        write(113);
        awaitValue(113);
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
