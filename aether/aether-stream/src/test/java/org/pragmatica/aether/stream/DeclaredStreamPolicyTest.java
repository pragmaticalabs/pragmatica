// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.ReplicationFactorsError;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1564 R8: a DECLARED stream (a `[streams.X]` resource, a durable topic and its dead-letter stream, a durable entity's
/// log) redeclared with factors different from its committed ones is refused, typed; the management create path, which
/// carries no declaration, keeps its already-exists answer.
class DeclaredStreamPolicyTest {
    private static final String STREAM = "orders";

    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void createDeclaredStream_samePolicy_isTheAlreadyExistsAnswer() {
        manager.createDeclaredStream(config(3, 2)).onFailure(cause -> fail(cause.message()));

        manager.createDeclaredStream(config(3, 2))
               .onSuccess(_ -> fail("a repeat create answers STREAM_ALREADY_EXISTS"))
               .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_ALREADY_EXISTS));
    }

    @Test
    void createDeclaredStream_changedPolicy_isRefused() {
        manager.createDeclaredStream(config(3, 2)).onFailure(cause -> fail(cause.message()));

        manager.createDeclaredStream(config(3, 3))
               .onSuccess(_ -> fail("a changed replication policy must be refused"))
               .onFailure(cause -> assertThat(cause).isEqualTo(new ReplicationFactorsError.ChangedOnLiveResource("stream '" + STREAM + "'",
                                                                                                              new ReplicationFactors(3, 2),
                                                                                                              new ReplicationFactors(3, 3))));
        assertThat(manager.confirmationFactorFor(STREAM)).as("the committed policy is untouched").isEqualTo(2);
    }

    /// R8 against the COMMITTED value, not the local entry (v1680 N1). A management default materialized (3, 3) here
    /// first; the committed config then applied is (3, 2), which the local entry keeps over as weaker. A declaration
    /// of (3, 3) matches the local entry but not what KV holds, so it is refused rather than accepted as unchanged.
    @Test
    void createDeclaredStream_differsFromTheAppliedCommittedValue_isRefusedEvenWhenTheLocalEntryMatches() {
        manager.createStream(config(3, 3)).onFailure(cause -> fail(cause.message()));
        manager.onStreamConfigPut(committedPut(config(3, 2)));

        manager.createDeclaredStream(config(3, 3))
               .onSuccess(_ -> fail("a declaration differing from the committed value must be refused"))
               .onFailure(cause -> assertThat(cause).isEqualTo(new ReplicationFactorsError.ChangedOnLiveResource("stream '" + STREAM + "'",
                                                                                                              new ReplicationFactors(3, 2),
                                                                                                              new ReplicationFactors(3, 3))));
    }

    /// The same committed value, declared again: adopted, the idempotent already-exists answer.
    @Test
    void createDeclaredStream_equalToTheAppliedCommittedValue_proceeds() {
        manager.onStreamConfigPut(committedPut(config(3, 2)));

        manager.createDeclaredStream(config(3, 2))
               .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_ALREADY_EXISTS));
        assertThat(manager.confirmationFactorFor(STREAM)).isEqualTo(2);
    }

    /// The management create path carries no declaration: it keeps the already-exists answer, never refuses.
    @Test
    void createStream_managementPath_withDifferentFactors_isTheAlreadyExistsAnswer() {
        manager.createDeclaredStream(config(3, 3)).onFailure(cause -> fail(cause.message()));

        manager.createStream(config(3, 2))
               .onSuccess(_ -> fail("a repeat create answers STREAM_ALREADY_EXISTS"))
               .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.STREAM_ALREADY_EXISTS));
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> committedPut(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                  StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }

    private static StreamConfig config(int factor, int confirmation) {
        return StreamConfig.streamConfig(STREAM).withReplication(new ReplicationFactors(factor, confirmation));
    }
}
