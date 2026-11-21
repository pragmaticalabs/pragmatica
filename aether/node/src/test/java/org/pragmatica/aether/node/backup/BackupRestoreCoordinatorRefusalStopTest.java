// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVCommand.Refusal;
import org.pragmatica.cluster.state.kvstore.KVCommand.RefusalKind;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #3303 RA-5a, the behaviour-red pin: it uses only symbols that exist at the base (the seven-argument factory, [Refusal],
/// [RefusalKind]), so it compiles there and fails on behaviour: at the base every refused restore transaction is retried, whatever
/// its kind. A decision write refused by the ARM check refuses the same way on every attempt, so the pass must stop with no retry.
class BackupRestoreCoordinatorRefusalStopTest {
    private static final SliceCodec NODE_CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final NodeId NODE_1 = NodeId.nodeId("node-1").unwrap();

    private final List<Runnable> retries = new CopyOnWriteArrayList<>();

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void armRefusedDecision_stopsThePass_schedulesNoRetry() {
        var kvStore = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), NODE_CODEC, NODE_CODEC);

        kvStore.processCommitted(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE,
                                                                                        LeaderValue.leaderValue(NODE_1, 1L)))),
                                 1L);
        var coordinator = BackupRestoreCoordinator.backupRestoreCoordinator(NODE_1,
                                                                            DeploymentEngine.FSM2,
                                                                            kvStore,
                                                                            this::refuseWithArm,
                                                                            Option.none(),
                                                                            _ -> {},
                                                                            this::capture);

        coordinator.activate();
        assertThat(retries).as("a deterministic refusal is never retried").isEmpty();
        assertThat(coordinator.isComplete()).isFalse();
    }

    private Promise<List<Object>> refuseWithArm(List<KVCommand<AetherKey>> commands) {
        var id = ((KVCommand.LeaderTransaction<?, ?>) commands.getFirst()).transactionId();
        var refusal = Refusal.refusal(RefusalKind.ARM, Option.some(ConfigKey.forKey("alpha")));

        return Promise.success(List.of(KVCommand.TransactionResult.transactionResult(id, Option.some(refusal))));
    }

    private ScheduledFuture<?> capture(Runnable runnable, TimeSpan delay) {
        retries.add(runnable);

        return new NeverFires();
    }

    private static final class NeverFires extends CompletableFuture<Object> implements ScheduledFuture<Object> {
        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
