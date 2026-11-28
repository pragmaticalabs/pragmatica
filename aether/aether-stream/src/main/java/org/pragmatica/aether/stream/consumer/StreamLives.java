// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.consumer;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;


/// The committed life of a stream, as the coordinator reads it (#3041): the incarnation of the stream's committed config,
/// or none while the stream has no committed config (never created, or deleted).
///
/// A consumer-group record carries the incarnation it was written under, and the applier admits a write to a group key
/// only while that incarnation is the committed one of the stream (`AetherKey.ConsumerGroupKey` is guarded by the
/// stream's `StreamConfigKey`). This is the coordinator's view of the same authority: it stamps every record with it.
@FunctionalInterface
public interface StreamLives {
    /// The incarnation of the stream's committed config, or none when no config is committed under the name.
    Option<Long> incarnationOf(String streamName);

    /// The lives as the node's own KV store has them committed: the same authority the applier checks the write against.
    static StreamLives committedIn(KVStore<AetherKey, AetherValue> kvStore) {
        return streamName -> kvStore.getTyped(StreamConfigKey.streamConfigKey(streamName),
                                              StreamConfigValue.class)
                                    .map(value -> value.config()
                                                       .incarnation());
    }
}
