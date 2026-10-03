// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

import static org.pragmatica.lang.Option.none;


/// `replicationFactor` and `confirmationFactor` are the resource's [ReplicationFactors] (#1564): the number of
/// copies of each partition, the owner included, and how many of them, the owner included, hold a write before
/// it is acknowledged. They are stored resolved: a stream's declaration is resolved against the cluster defaults
/// once, through [ReplicationDeclaration#resolve], before the config is committed. `consistencyMode` remains the
/// independent READ knob.
@Codec
public record StreamConfig(String name,
                           int partitions,
                           RetentionPolicy retention,
                           String autoOffsetReset,
                           long maxEventSizeBytes,
                           ConsistencyMode consistencyMode,
                           int replicationFactor,
                           int confirmationFactor,
                           StreamCompression compression,
                           Option<String> encryptionKeyId,
                           long incarnation) {
    /// No incarnation assigned: a config built by a factory, before a cluster create mints one.
    public static final long NO_INCARNATION = 0L;
    private static final int DEFAULT_PARTITIONS = 4;
    /// `"earliest"` is the only value the system accepts, so it is the only honest default (#677).
    ///
    /// This read `"latest"` until 2026-08-29, which made the record's own factories hand out a value
    /// `StreamResourceValidator` REJECTS — "auto-offset-reset 'latest' has no runtime effect". A
    /// never-committed consumer always starts at offset 0 (earliest), permanently, by the **#478
    /// ruling**, not as a gap to be closed later.
    ///
    /// The two paths did not meet in practice — deployed configs come from `StreamConfigParser`, which
    /// already defaulted to `"earliest"`, while this default only reached programmatic construction,
    /// which is not validated — so the contradiction was latent rather than live. It was still a trap
    /// sitting under a public factory: two authorities declaring different defaults for one field, with
    /// the validator agreeing only with the other one.
    private static final String DEFAULT_AUTO_OFFSET_RESET = "earliest";
    private static final long DEFAULT_MAX_EVENT_SIZE_BYTES = 1_048_576L;
    /// #1564: the owner's built-in replication factors (RF 3, CF 2). Every declared stream resolves its own
    /// factors against the committed cluster defaults instead; this reaches only programmatic construction.
    private static final int DEFAULT_REPLICATION_FACTOR = ReplicationFactors.BUILT_IN.replicationFactor();
    private static final int DEFAULT_CONFIRMATION_FACTOR = ReplicationFactors.BUILT_IN.confirmationFactor();

    public static final StreamConfig DEFAULT = new StreamConfig("",
                                                                DEFAULT_PARTITIONS,
                                                                RetentionPolicy.retentionPolicy(),
                                                                DEFAULT_AUTO_OFFSET_RESET,
                                                                DEFAULT_MAX_EVENT_SIZE_BYTES,
                                                                ConsistencyMode.EVENTUAL,
                                                                DEFAULT_REPLICATION_FACTOR,
                                                                DEFAULT_CONFIRMATION_FACTOR,
                                                                StreamCompression.NONE,
                                                                none(),
                                                                NO_INCARNATION);

    /// This config under a different `name`, every other field carried over verbatim.
    ///
    /// The one caller shape is #1040's qualification step: the config binder derives `name` from the
    /// `resources.toml` section suffix (`ProviderBasedConfigService.deriveNameFromSectionSuffix`), which
    /// yields the bare local alias, and the materialization path rewrites it to the engine key the
    /// management routes resolve to. A copy method rather than a new component, so the `@Codec` wire
    /// form is untouched — this substitutes the name BEFORE the config is committed, and nothing
    /// persists both spellings.
    public StreamConfig withName(String newName) {
        return new StreamConfig(newName,
                                partitions,
                                retention,
                                autoOffsetReset,
                                maxEventSizeBytes,
                                consistencyMode,
                                replicationFactor,
                                confirmationFactor,
                                compression,
                                encryptionKeyId,
                                incarnation);
    }

    /// This config as one LIFE of its stream (#1278 review): `incarnation` identifies a cluster create of the name.
    /// Minted once when a stream is created through the cluster and carried, unchanged, by every republish, so every
    /// durable artifact of the stream (WAL directory, sealed-segment refs, reclaimed-through floors) is keyed by it,
    /// and a node opens only the artifacts of the incarnation the committed config names — a stream destroyed and
    /// created again under the same name never inherits the old life's records, watermark or floor.
    public StreamConfig withIncarnation(long newIncarnation) {
        return new StreamConfig(name,
                                partitions,
                                retention,
                                autoOffsetReset,
                                maxEventSizeBytes,
                                consistencyMode,
                                replicationFactor,
                                confirmationFactor,
                                compression,
                                encryptionKeyId,
                                newIncarnation);
    }

    /// The stored factors as a pair. The engine re-checks them with [ReplicationFactors#replicationFactors].
    public ReplicationFactors replication() {
        return new ReplicationFactors(replicationFactor, confirmationFactor);
    }

    /// This config carrying `factors`, every other field carried over verbatim (#1564: resolution happens
    /// after the rest of the declaration is parsed).
    public StreamConfig withReplication(ReplicationFactors factors) {
        return new StreamConfig(name,
                                partitions,
                                retention,
                                autoOffsetReset,
                                maxEventSizeBytes,
                                consistencyMode,
                                factors.replicationFactor(),
                                factors.confirmationFactor(),
                                compression,
                                encryptionKeyId,
                                incarnation);
    }

    public static StreamConfig streamConfig(String name) {
        return new StreamConfig(name,
                                DEFAULT_PARTITIONS,
                                RetentionPolicy.retentionPolicy(),
                                DEFAULT_AUTO_OFFSET_RESET,
                                DEFAULT_MAX_EVENT_SIZE_BYTES,
                                ConsistencyMode.EVENTUAL,
                                DEFAULT_REPLICATION_FACTOR,
                                DEFAULT_CONFIRMATION_FACTOR,
                                StreamCompression.NONE,
                                none(),
                                NO_INCARNATION);
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
                                DEFAULT_REPLICATION_FACTOR,
                                DEFAULT_CONFIRMATION_FACTOR,
                                StreamCompression.NONE,
                                none(),
                                NO_INCARNATION);
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
                                DEFAULT_REPLICATION_FACTOR,
                                DEFAULT_CONFIRMATION_FACTOR,
                                StreamCompression.NONE,
                                none(),
                                NO_INCARNATION);
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
                                DEFAULT_REPLICATION_FACTOR,
                                DEFAULT_CONFIRMATION_FACTOR,
                                StreamCompression.NONE,
                                none(),
                                NO_INCARNATION);
    }

    public static StreamConfig streamConfig(String name,
                                            int partitions,
                                            RetentionPolicy retention,
                                            String autoOffsetReset,
                                            long maxEventSizeBytes,
                                            ConsistencyMode consistencyMode,
                                            int confirmationFactor) {
        return new StreamConfig(name,
                                partitions,
                                retention,
                                autoOffsetReset,
                                maxEventSizeBytes,
                                consistencyMode,
                                DEFAULT_REPLICATION_FACTOR,
                                confirmationFactor,
                                StreamCompression.NONE,
                                none(),
                                NO_INCARNATION);
    }

    public static StreamConfig streamConfig(String name,
                                            int partitions,
                                            RetentionPolicy retention,
                                            String autoOffsetReset,
                                            long maxEventSizeBytes,
                                            ConsistencyMode consistencyMode,
                                            int replicationFactor,
                                            int confirmationFactor,
                                            StreamCompression compression,
                                            Option<String> encryptionKeyId) {
        return new StreamConfig(name,
                                partitions,
                                retention,
                                autoOffsetReset,
                                maxEventSizeBytes,
                                consistencyMode,
                                replicationFactor,
                                confirmationFactor,
                                compression,
                                encryptionKeyId,
                                NO_INCARNATION);
    }
}
