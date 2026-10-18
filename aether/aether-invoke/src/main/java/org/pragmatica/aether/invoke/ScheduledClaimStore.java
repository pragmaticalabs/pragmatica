// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.invoke;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ScheduledTaskClaimKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ScheduledTaskClaimValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Where the durable in-flight claims of SINGLE-mode scheduled tasks live (#1155): the committed cluster state in a node,
/// a map in a test that does not share claims between schedulers.
///
/// `read` is the COMMITTED row as this node sees it. `write` resolves once the command has been applied, and a
/// `VersionFenced` write the applier refused still resolves: a refused write mutates nothing and says nothing, so the
/// writer confirms by reading the row back (see [ScheduledTaskClaims]).
public interface ScheduledClaimStore {
    Option<ScheduledTaskClaimValue> read(ScheduledTaskClaimKey key);
    Promise<Unit> write(KVCommand<AetherKey> command);

    /// A store private to one scheduler. It applies the same successor fence as the cluster applier, so a scheduler that
    /// does not share it with another sees exactly the pre-#1155 behaviour: a claim that holds on its own node only.
    static ScheduledClaimStore inMemory() {
        record inMemory(Map<ScheduledTaskClaimKey, ScheduledTaskClaimValue> rows) implements ScheduledClaimStore {
            @Override
            public Option<ScheduledTaskClaimValue> read(ScheduledTaskClaimKey key) {
                return Option.option(rows.get(key));
            }

            @Override
            public Promise<Unit> write(KVCommand<AetherKey> command) {
                return Promise.success(apply(command));
            }

            private synchronized Unit apply(KVCommand<AetherKey> command) {
                return switch (command) {
                    case KVCommand.Put<AetherKey, ?> put when put.key() instanceof ScheduledTaskClaimKey key && put.value() instanceof ScheduledTaskClaimValue value -> putIfSuccessor(key,
                                                                                                                                                                                       value);
                    case KVCommand.Remove<AetherKey> remove when remove.key() instanceof ScheduledTaskClaimKey key -> remove(key);
                    default -> Unit.unit();
                };
            }

            private Unit remove(ScheduledTaskClaimKey key) {
                rows.remove(key);

                return Unit.unit();
            }

            /// The applier's successor fence: a row over a committed one is refused unless it is the immediate successor.
            private Unit putIfSuccessor(ScheduledTaskClaimKey key, ScheduledTaskClaimValue incoming) {
                read(key).filter(committed -> incoming.fenceVersion() != committed.fenceVersion() + 1)
                    .onEmpty(() -> rows.put(key, incoming));

                return Unit.unit();
            }
        }

        return new inMemory(new ConcurrentHashMap<>());
    }
}
