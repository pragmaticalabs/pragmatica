// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskClaimKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskClaimValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.CorrelationId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;


/// Where the durable in-flight claims of SINGLE-mode scheduled tasks live (#1155): the committed cluster state in a node,
/// an applier of its own in a scheduler that does not share claims with another.
///
/// `read` is the COMMITTED row as this node sees it. `write` resolves once the command has been applied, and a write the
/// applier refused still resolves: a refused write mutates nothing and says nothing, so the writer confirms by reading
/// the row back (see [ScheduledTaskClaims]).
public interface ScheduledClaimStore {
    Option<ScheduledTaskClaimValue> read(ScheduledTaskClaimKey key);
    Promise<Unit> write(KVCommand<AetherKey> command);

    /// A store private to one scheduler: the cluster's own applier ([KVStore]) over a map nobody else writes, so every
    /// fence the cluster enforces is enforced here by the same code, not by a copy of its rules. A scheduler that does not
    /// share it with another sees exactly the pre-#1155 behaviour: a claim that holds on its own node only.
    static ScheduledClaimStore inMemory() {
        // The applier is fed commands directly and never snapshotted, so the codec is only what its constructor asks for.
        var codec = FrameworkCodecs.frameworkCodecs();

        return over(new KVStore<>(MessageRouter.mutable(), codec, codec));
    }

    /// A store over `applier`, which several schedulers (and the state writes of their fires) can share, as they share the
    /// committed state in a node. Commands are applied one at a time, in the order they arrive.
    static ScheduledClaimStore over(KVStore<AetherKey, AetherValue> applier) {
        record over(KVStore<AetherKey, AetherValue> applier) implements ScheduledClaimStore {
            @Override
            public Option<ScheduledTaskClaimValue> read(ScheduledTaskClaimKey key) {
                return applier.getTyped(key, ScheduledTaskClaimValue.class);
            }

            @Override
            public Promise<Unit> write(KVCommand<AetherKey> command) {
                return Promise.success(apply(command));
            }

            private Unit apply(KVCommand<AetherKey> command) {
                synchronized (applier) {
                    applier.process(ScheduledClaimStore.batchOf(command));
                }

                return Unit.unit();
            }
        }

        return new over(applier);
    }

    /// A batch of one command, built without `createBatch`: its id is a hash of the encoded command, and the codec of an
    /// in-memory applier has no encoding for the claim types (it is never asked to encode anything else either).
    private static Batch<KVCommand<AetherKey>> batchOf(KVCommand<AetherKey> command) {
        return new Batch<>(Batch.Id.randomId(),
                           List.of(CorrelationId.randomCorrelationId()),
                           System.nanoTime(),
                           List.of(command));
    }
}
