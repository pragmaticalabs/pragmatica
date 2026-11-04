// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.deployment.cluster.NodeReplacementIndex;
import org.pragmatica.aether.deployment.cluster.UpgradeRunIndex;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeReplacementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.UpgradeRunKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeStop;
import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.NotificationDelivery;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// D1 item 9 (KVStore dispatcher design §3.5 R4, D0 finding 9, rows 95 and 97): a put delivered in a replay burst is
/// committed history, not a transition, so the node-replacement and upgrade-run announcers must raise nothing for it.
/// Each arm installs a record silently, replays it through a dispatched store (the burst runs on the dispatcher thread
/// with `isReplaying()` true), then commits a LIVE transition whose own announcement is the control: the hand-off is
/// one FIFO thread, so once the live announcement arrived, anything the replay raised has arrived before it. The owner
/// gate is open in every arm.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ReplayedAnnouncementTest {
    private static final NodeId SELF = new NodeId("replay-self");
    private static final NodeId REPLAYED = new NodeId("replay-original");
    private static final NodeId LIVE = new NodeId("live-original");
    private static final LeaderValue LEADER = new LeaderValue(SELF, 1);

    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final InstallingDeserializer deserializer = new InstallingDeserializer();
    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(router,
                                                                        new Serializer() {
                                                                            @Override
                                                                            public <T> void write(ByteBuf buffer, T value) {}
                                                                        },
                                                                        deserializer,
                                                                        NotificationDelivery.dispatched("replay-announce",
                                                                                                        DispatcherFailureSink.loggingOnly()));
    private final List<OperatorWarning> raised = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(raised::add);

    /// Installs whatever map the test sets, so a silent install needs no codec.
    static final class InstallingDeserializer implements Deserializer {
        volatile Map<AetherKey, AetherValue> next = Map.of();

        @Override
        @SuppressWarnings("unchecked")
        public <T> T read(ByteBuf buffer) {
            return (T) next;
        }
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    /// Mutation: the announcer ignores the replay bit, or the absorber passes `false`: the replayed record announces
    /// `NODE_REPLACEMENT_STARTED`, red.
    @Test
    void replayedNodeReplacementPut_announcesNothing() {
        var index = nodeReplacementsRouted();

        assertThat(startedSubjects(replayThenLiveReplacement(index))).containsExactly(LIVE.id());
    }

    /// Mutation: as above, for the upgrade run: the replayed run announces `UPGRADE_STARTED`, red.
    @Test
    void replayedUpgradeRunPut_announcesNothing() {
        var index = upgradeRunsRouted();

        assertThat(replayThenLiveUpgrade(index)).containsExactly(OperatorWarningCode.UPGRADE_PAUSED);
    }

    /// The WorkerHierarchyAssembly route (the production absorber) and the TopologyAssembly listener.
    private NodeReplacementIndex nodeReplacementsRouted() {
        var index = NodeReplacementIndex.nodeReplacementIndex();

        index.onTransition(NodeReplacementWiring.announcer(() -> true, sink));
        routeOn(NodeReplacementKey.class,
                (ValuePut<NodeReplacementKey, NodeReplacementValue> put) -> NodeReplacementWiring.absorbPut(index,
                                                                                                            store::isReplaying,
                                                                                                            put));

        return index;
    }

    private UpgradeRunIndex upgradeRunsRouted() {
        var index = UpgradeRunIndex.upgradeRunIndex();

        index.onTransition(UpgradeRunWiring.announcer(() -> true, sink));
        routeOn(UpgradeRunKey.class,
                (ValuePut<UpgradeRunKey, UpgradeRunValue> put) -> UpgradeRunWiring.absorbPut(index, store::isReplaying, put));

        return index;
    }

    /// Replays a replacement of REPLAYED, then commits a live one of LIVE and waits for its announcement.
    private List<OperatorWarning> replayThenLiveReplacement(NodeReplacementIndex index) {
        replay(Map.of(new NodeReplacementKey(REPLAYED), replacement("replay-new")));
        assertThat(index.recordFor(REPLAYED)
                        .isPresent()).as("the replay burst reached the index")
                                     .isTrue();
        commitLeader();
        settle(commit(leaderTransaction(new NodeReplacementKey(LIVE), Option.none(), replacement("live-new"))));

        return awaitRaised(OperatorWarningCode.NODE_REPLACEMENT_STARTED, LIVE.id());
    }

    /// Replays a RUNNING run, then commits it PAUSED live and waits for `UPGRADE_PAUSED`; returns the codes raised.
    private List<OperatorWarningCode> replayThenLiveUpgrade(UpgradeRunIndex index) {
        replay(Map.of(UpgradeRunKey.INSTANCE, run(UpgradeRunState.RUNNING)));
        assertThat(index.run()
                        .isPresent()).as("the replay burst reached the index")
                                     .isTrue();
        commitLeader();
        settle(commit(leaderTransaction(UpgradeRunKey.INSTANCE,
                                         Option.some(run(UpgradeRunState.RUNNING)),
                                         run(UpgradeRunState.PAUSED))));

        return awaitRaised(OperatorWarningCode.UPGRADE_PAUSED, "upgrade").stream()
                                                                         .map(OperatorWarning::code)
                                                                         .toList();
    }

    private static List<String> startedSubjects(List<OperatorWarning> warnings) {
        return warnings.stream()
                       .filter(warning -> warning.code() == OperatorWarningCode.NODE_REPLACEMENT_STARTED)
                       .map(OperatorWarning::subject)
                       .toList();
    }

    private static NodeReplacementValue replacement(String replacement) {
        return new NodeReplacementValue(new NodeId(replacement), "core", NodeReplacementPhase.PROVISIONING, 0L);
    }

    private static UpgradeRunValue run(UpgradeRunState state) {
        return new UpgradeRunValue("2.0.0", List.of(SELF), 0, "", state, UpgradeStop.NONE, "", 1L, 1L, 1L);
    }

    /// Installs `content` silently, then replays it: the call returns once its burst is delivered (design §3.7).
    private void replay(Map<AetherKey, AetherValue> content) {
        deserializer.next = content;
        assertThat(store.restoreSnapshot(new byte[0])
                        .isSuccess()).isTrue();
        store.replayNotifications();
    }

    /// Waits until the hand-off delivered the warning `code` for `subject`, and returns everything raised so far.
    private List<OperatorWarning> awaitRaised(OperatorWarningCode code, String subject) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (raised.stream()
                     .noneMatch(warning -> warning.code() == code && warning.subject()
                                                                           .equals(subject)) && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertThat(raised).as("the live control announced " + code + " for " + subject)
                          .anyMatch(warning -> warning.code() == code && warning.subject()
                                                                               .equals(subject));

        return List.copyOf(raised);
    }

    private static void settle(Promise<?> committed) {
        assertThat(committed.await(timeSpan(10).seconds())
                            .isSuccess()).isTrue();
    }

    private void commitLeader() {
        settle(commit(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static KVCommand leaderTransaction(AetherKey key, Option<AetherValue> expected, AetherValue value) {
        return new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                       "replay-" + key,
                                                                       LEADER,
                                                                       List.of(),
                                                                       List.of(new KVCommand.Mutation<AetherKey, AetherValue>(key,
                                                                                                                              expected,
                                                                                                                              Option.some(value))));
    }

    /// Routes every `ValuePut` whose key is a `keyType` to `handler`, as the node's KV notification router does.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private <K> void routeOn(Class<K> keyType, Consumer<? extends ValuePut> handler) {
        router.addRoute(ValuePut.class, (ValuePut put) -> routeIf(keyType, (Consumer) handler, put));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void routeIf(Class<?> keyType, Consumer handler, ValuePut put) {
        if (keyType.isInstance(put.cause().key())) {
            handler.accept(put);
        }
    }

    /// The engine's commit path: apply the batch through the store, then park the promise until it is delivered.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Promise<?> commit(KVCommand command) {
        var promise = Promise.<List<Object>> promise();
        List<Object> results = store.process(store.createBatch(List.of(command)));

        store.completeAfterDelivery(promise, results);

        return promise;
    }
}
