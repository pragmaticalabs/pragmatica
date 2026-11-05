// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.config.RollbackConfig;
import org.pragmatica.aether.controller.RollbackManagerGuardedRecordTest.HeldClusterNode;
import org.pragmatica.aether.controller.RollbackManagerOverridePreservationTest.AlwaysLeaderManager;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.PreviousVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.PreviousVersionValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.DispatcherFailureSink;
import org.pragmatica.cluster.state.kvstore.HeldDelivery;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.NotificationDelivery;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// D1 exit (v), KVStore dispatcher design §3.4.1: RollbackManager over a DISPATCHED store, where its handler reads the
/// SliceTarget at HEAD while the delivering notification may be older (a read-ahead). Delivery is held by the
/// dispatcher, not by hand, and the manager's writes are captured and applied by the test.
/// - read-ahead arm: hold at N1 (A -> B), commit the rollback (record and target in one leader transaction), release.
///   N1 reads ahead (the fence counts the read under the `SliceTargetKey` tag), writes nothing, and the record keeps
///   the rollback.
/// - version-change arm (R5.1): hold at N1 (A -> B), commit B -> C, release. N1 reads ahead and writes nothing; N2
///   records (B, C) from its own `oldValue`.
/// Mutations: a read-ahead notification derives from the record (`notificationPrevious` takes the recorded version
/// when the notification is not HEAD): both arms capture an extra write, red.
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class RollbackManagerDispatchedReadAheadTest {
    private static final NodeId SELF = NodeId.nodeId("node-1").unwrap();
    private static final ArtifactBase BASE = Artifact.artifact("org.test:my-slice:1.0.0").unwrap().base();
    private static final PreviousVersionKey RECORD_KEY = PreviousVersionKey.previousVersionKey(BASE);
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(BASE);
    private static final LeaderValue LEADER = new LeaderValue(SELF, 1);
    private static final Version X = Version.version("0.9.0").unwrap();
    private static final Version A = Version.version("1.0.0").unwrap();
    private static final Version B = Version.version("2.0.0").unwrap();
    private static final Version C = Version.version("3.0.0").unwrap();

    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final HeldDelivery hold = HeldDelivery.heldDelivery();
    private KVStore<AetherKey, AetherValue> store;
    private HeldClusterNode held;
    private SliceTargetValue targetA;
    private PreviousVersionValue recordZero;

    @BeforeEach
    void setUp() {
        store = new KVStore<>(router,
                              new Serializer() {
                                  @Override
                                  public <T> void write(ByteBuf byteBuf, T object) {}
                              },
                              new Deserializer() {
                                  @Override
                                  public <T> T read(ByteBuf byteBuf) {
                                      return null;
                                  }
                              },
                              NotificationDelivery.dispatched("rollback-ryw", DispatcherFailureSink.loggingOnly(), hold));
        held = new HeldClusterNode(SELF, store);
        targetA = SliceTargetValue.sliceTargetValue(A, 3);
        recordZero = PreviousVersionValue.previousVersionValue(BASE, X, A);

        RollbackManagerOverridePreservationTest.seedCommittedLeader(store, SELF, 1);
        commit(TARGET_KEY, targetA);
        commit(RECORD_KEY, recordZero);
        awaitDelivered();
        var manager = RollbackManager.rollbackManager(SELF,
                                                      RollbackConfig.rollbackConfig(),
                                                      held,
                                                      store,
                                                      new AlwaysLeaderManager(SELF));
        manager.activate();
        router.addRoute(ValuePut.class, (ValuePut<?, ?> put) -> route(manager, put));
    }

    @AfterEach
    void closeStore() {
        hold.release();
        store.close();
    }

    @Test
    void readAhead_keepsTheRollback_whenTheRollbackCommitsWhileTheVersionChangeIsQueued() {
        holdNext();
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));
        assertThat(hold.awaitHeld(10)).as("the dispatcher is held at N1").isTrue();
        var rolledBack = recordZero.withRollback(B, A, System.currentTimeMillis());

        assertThat(rollback(recordZero, rolledBack, targetB)).as("arming: the rollback commits while N1 is queued")
                                                             .isTrue();
        hold.release();
        awaitDelivered();
        held.settle();

        assertThat(held.captured()).as("N1 reads ahead and writes nothing").isEmpty();
        assertThat(record()).isEqualTo(rolledBack);
        assertThat(store.readAheadReads("live", "SliceTargetKey")).as("the fence counts the HEAD read")
                                                                  .isGreaterThanOrEqualTo(1);
    }

    @Test
    void readAhead_recordsTheLaterChangeFromItsOwnOldValue_whenAVersionChangeIsQueuedBehindAnother() {
        holdNext();
        var targetB = commit(TARGET_KEY, targetA.withVersion(B));
        assertThat(hold.awaitHeld(10)).as("the dispatcher is held at N1").isTrue();
        commit(TARGET_KEY, targetB.withVersion(C));
        hold.release();
        awaitDelivered();
        held.settle();

        assertThat(held.captured()).as("only N2 writes: N1 reads ahead").hasSize(1);
        held.applyAllPending();

        assertThat(record().previousVersion()).as("previous version").isEqualTo(B);
        assertThat(record().currentVersion()).as("current version").isEqualTo(C);
    }

    private void holdNext() {
        hold.holdFrom(store.enqueuedSeq() + 1);
    }

    private void awaitDelivered() {
        assertThat(store.awaitDelivered(store.enqueuedSeq())
                        .await(timeSpan(10).seconds())
                        .isSuccess()).isTrue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void route(RollbackManager manager, ValuePut put) {
        if (put.cause().key() instanceof SliceTargetKey) {
            manager.onSliceTargetPut(put);
        }
    }

    private PreviousVersionValue record() {
        return store.getTyped(RECORD_KEY, PreviousVersionValue.class).unwrap();
    }

    private <V extends AetherValue> V commit(AetherKey key, V value) {
        store.process(store.createBatch(List.<KVCommand<AetherKey>>of(new KVCommand.Put<AetherKey, AetherValue>(key, value))));

        return value;
    }

    /// A rollback exactly as `RollbackManager.rollbackTransaction` builds it: record and target in one leader transaction.
    private boolean rollback(PreviousVersionValue before, PreviousVersionValue after, SliceTargetValue target) {
        var mutations = List.of(new KVCommand.Mutation<AetherKey, AetherValue>(RECORD_KEY,
                                                                               Option.<AetherValue>some(before),
                                                                               Option.<AetherValue>some(after)),
                                new KVCommand.Mutation<AetherKey, AetherValue>(TARGET_KEY,
                                                                               Option.<AetherValue>some(target),
                                                                               Option.<AetherValue>some(target.withVersion(A))));
        var transaction = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(TARGET_KEY,
                                                                                  "rollback-1",
                                                                                  LEADER,
                                                                                  List.of(),
                                                                                  mutations);
        List<Object> results = store.process(store.createBatch(List.<KVCommand<AetherKey>>of(transaction)));

        return results.stream()
                      .anyMatch(result -> result instanceof KVCommand.TransactionResult outcome && outcome.accepted());
    }
}
