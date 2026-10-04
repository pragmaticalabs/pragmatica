// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.projection;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.slice.kvstore.AetherKey.StreamCursorCheckpointKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamCursorCheckpointValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Contract;

/// Witnesses that a specific cursor record was ACCEPTED by this node's applier.
///
/// The applier emits a `ValuePut` only for a put it accepted, and emits it inside that put's apply; a refused
/// put emits nothing. A read-back of the committed value after the apply cannot tell "accepted, then
/// overtaken" from "refused": the rewind record's own apply restarts the consumer, and that consumer's first
/// checkpoint at the same epoch can commit before the read-back runs. [NodeReplayCursor] watches its
/// records here before it puts them, and a watched record counts as committed once its notification was seen.
public interface CommitWitness {
    /// Start watching for `record` committed under `key`; the watch must be closed when its rewind settles.
    Watch watch(StreamCursorCheckpointKey key, StreamCursorCheckpointValue record);

    /// Routed every `ValuePut`; anything that is not a watched cursor record is ignored.
    @Contract
    void onPut(ValuePut<?, ?> put);

    interface Watch {
        /// The record's accepted-put notification has been dispatched.
        boolean seen();

        @Contract
        void close();
    }

    static CommitWitness commitWitness() {
        return new WitnessState();
    }

    final class WitnessState implements CommitWitness {
        private final Map<StreamCursorCheckpointKey, Set<RecordWatch>> watches = new ConcurrentHashMap<>();

        @Override
        public Watch watch(StreamCursorCheckpointKey key, StreamCursorCheckpointValue record) {
            var watch = new RecordWatch(key, record);

            watches.compute(key, (_, existing) -> withWatch(existing, watch));

            return watch;
        }

        private static Set<RecordWatch> withWatch(Set<RecordWatch> existing, RecordWatch watch) {
            var set = existing == null
                      ? ConcurrentHashMap.<RecordWatch>newKeySet()
                      : existing;

            set.add(watch);

            return set;
        }

        @Contract
        @Override
        public void onPut(ValuePut<?, ?> put) {
            if (put.cause().key() instanceof StreamCursorCheckpointKey key && put.cause().value() instanceof StreamCursorCheckpointValue value) {
                watches.getOrDefault(key, Set.of())
                       .forEach(watch -> watch.observe(value));
            }
        }

        private final class RecordWatch implements Watch {
            private final StreamCursorCheckpointKey key;
            private final StreamCursorCheckpointValue record;
            private volatile boolean seen;

            private RecordWatch(StreamCursorCheckpointKey key, StreamCursorCheckpointValue record) {
                this.key = key;
                this.record = record;
            }

            private void observe(StreamCursorCheckpointValue value) {
                if (record.equals(value)) {
                    seen = true;
                }
            }

            @Override
            public boolean seen() {
                return seen;
            }

            @Contract
            @Override
            public void close() {
                watches.computeIfPresent(key, (_, set) -> {
                    set.remove(this);

                    return set.isEmpty()
                           ? null
                           : set;
                });
            }
        }
    }
}
