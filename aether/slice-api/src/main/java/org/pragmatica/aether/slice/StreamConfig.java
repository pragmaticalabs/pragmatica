// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Option.none;


/// `replicas` is the replication factor — the total number of copies of each partition INCLUDING the
/// owner (Kafka `replication.factor`). `minSyncReplicas` is the minimum in-sync replica count,
/// INCLUDING the owner, that must confirm a write before it resolves (Kafka `min.insync.replicas`):
/// `<= 1` resolves on the local owner write (0 = eventual, 1 = owner-only), `>= 2` awaits
/// `minSyncReplicas - 1` distinct non-self replica acks. Invariant: `0 <= minSyncReplicas <= replicas`
/// and `replicas >= 1`. `consistencyMode` remains the independent READ knob.
@Codec
public record StreamConfig(String name,
                           int partitions,
                           RetentionPolicy retention,
                           String autoOffsetReset,
                           long maxEventSizeBytes,
                           ConsistencyMode consistencyMode,
                           int replicas,
                           int minSyncReplicas,
                           StreamCompression compression,
                           Option<String> encryptionKeyId) {
    private static final int DEFAULT_PARTITIONS = 4;
    private static final String DEFAULT_AUTO_OFFSET_RESET = "latest";
    private static final long DEFAULT_MAX_EVENT_SIZE_BYTES = 1_048_576L;
    private static final int DEFAULT_REPLICAS = 1;
    private static final int DEFAULT_MIN_SYNC_REPLICAS = 0;

    public static final StreamConfig DEFAULT = new StreamConfig("",
                                                                DEFAULT_PARTITIONS,
                                                                RetentionPolicy.retentionPolicy(),
                                                                DEFAULT_AUTO_OFFSET_RESET,
                                                                DEFAULT_MAX_EVENT_SIZE_BYTES,
                                                                ConsistencyMode.EVENTUAL,
                                                                DEFAULT_REPLICAS,
                                                                DEFAULT_MIN_SYNC_REPLICAS,
                                                                StreamCompression.NONE,
                                                                none());

    public static StreamConfig streamConfig(String name) {
        return new StreamConfig(name,
                                DEFAULT_PARTITIONS,
                                RetentionPolicy.retentionPolicy(),
                                DEFAULT_AUTO_OFFSET_RESET,
                                DEFAULT_MAX_EVENT_SIZE_BYTES,
                                ConsistencyMode.EVENTUAL,
                                DEFAULT_REPLICAS,
                                DEFAULT_MIN_SYNC_REPLICAS,
                                StreamCompression.NONE,
                                none());
    }

    public static StreamConfig streamConfig(String name,
                                            int partitions,
                                            RetentionPolicy retention,
                                            String autoOffsetReset) {
        return new StreamConfig(name,
                                partitions,
                                retention,
                                autoOffsetReset,
                                DEFAULT_MAX_EVENT_SIZE_BYTES,
                                ConsistencyMode.EVENTUAL,
                                DEFAULT_REPLICAS,
                                DEFAULT_MIN_SYNC_REPLICAS,
                                StreamCompression.NONE,
                                none());
    }

    public static StreamConfig streamConfig(String name,
                                            int partitions,
                                            RetentionPolicy retention,
                                            String autoOffsetReset,
                                            long maxEventSizeBytes) {
        return new StreamConfig(name,
                                partitions,
                                retention,
                                autoOffsetReset,
                                maxEventSizeBytes,
                                ConsistencyMode.EVENTUAL,
                                DEFAULT_REPLICAS,
                                DEFAULT_MIN_SYNC_REPLICAS,
                                StreamCompression.NONE,
                                none());
    }

    public static StreamConfig streamConfig(String name,
                                            int partitions,
                                            RetentionPolicy retention,
                                            String autoOffsetReset,
                                            long maxEventSizeBytes,
                                            ConsistencyMode consistencyMode) {
        return new StreamConfig(name,
                                partitions,
                                retention,
                                autoOffsetReset,
                                maxEventSizeBytes,
                                consistencyMode,
                                DEFAULT_REPLICAS,
                                DEFAULT_MIN_SYNC_REPLICAS,
                                StreamCompression.NONE,
                                none());
    }

    public static StreamConfig streamConfig(String name,
                                            int partitions,
                                            RetentionPolicy retention,
                                            String autoOffsetReset,
                                            long maxEventSizeBytes,
                                            ConsistencyMode consistencyMode,
                                            int minSyncReplicas) {
        return new StreamConfig(name,
                                partitions,
                                retention,
                                autoOffsetReset,
                                maxEventSizeBytes,
                                consistencyMode,
                                DEFAULT_REPLICAS,
                                minSyncReplicas,
                                StreamCompression.NONE,
                                none());
    }

    public static StreamConfig streamConfig(String name,
                                            int partitions,
                                            RetentionPolicy retention,
                                            String autoOffsetReset,
                                            long maxEventSizeBytes,
                                            ConsistencyMode consistencyMode,
                                            int replicas,
                                            int minSyncReplicas,
                                            StreamCompression compression,
                                            Option<String> encryptionKeyId) {
        return new StreamConfig(name,
                                partitions,
                                retention,
                                autoOffsetReset,
                                maxEventSizeBytes,
                                consistencyMode,
                                replicas,
                                minSyncReplicas,
                                compression,
                                encryptionKeyId);
    }
}
