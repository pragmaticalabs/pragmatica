// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2201 3.2 item 2: the retention source sealed-tier retention reads is the committed config this node has APPLIED, else a
/// locally committed entry; a stream with neither answers nothing, and retention then withholds it.
class CommittedRetentionTest {
    private static final String STREAM = "orders";
    private static final RetentionPolicy LOCAL = RetentionPolicy.retentionPolicy(10, 20, 30).withDeclared(RetentionDimensions.COUNT);
    private static final RetentionPolicy APPLIED = RetentionPolicy.retentionPolicy(40, 50, 60).withDeclared(RetentionDimensions.AGE);

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
    void aLocallyCommittedEntry_answersItsRetention() {
        manager.createDeclaredStream(config(LOCAL)).onFailure(cause -> fail(cause.message()));

        assertThat(manager.committedRetention(STREAM)).isEqualTo(Option.some(LOCAL));
    }

    @Test
    void theAppliedCommittedConfig_answers_andWinsOverTheLocalEntry() {
        manager.createDeclaredStream(config(LOCAL)).onFailure(cause -> fail(cause.message()));
        manager.onStreamConfigPut(committedPut(config(APPLIED)));

        assertThat(manager.committedRetention(STREAM)).isEqualTo(Option.some(APPLIED));
    }

    @Test
    void anAppliedConfigAlone_answers() {
        manager.onStreamConfigPut(committedPut(config(APPLIED)));

        assertThat(manager.committedRetention(STREAM)).isEqualTo(Option.some(APPLIED));
    }

    @Test
    void aStreamThisNodeHasNeitherAppliedNorCommitted_answersNothing() {
        assertThat(manager.committedRetention(STREAM)).isEqualTo(Option.none());
    }

    @Test
    void aDestroyedStream_answersNothing() {
        manager.onStreamConfigPut(committedPut(config(APPLIED)));
        manager.destroyStream(STREAM);

        assertThat(manager.committedRetention(STREAM)).isEqualTo(Option.none());
    }

    private static StreamConfig config(RetentionPolicy retention) {
        return StreamConfig.streamConfig(STREAM, 1, retention, "earliest");
    }

    private static ValuePut<StreamConfigKey, StreamConfigValue> committedPut(StreamConfig config) {
        return new ValuePut<>(new KVCommand.Put<>(StreamConfigKey.streamConfigKey(config.name()),
                                                  StreamConfigValue.streamConfigValue(config)),
                              Option.none());
    }
}
