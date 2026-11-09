// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.StreamConfig;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// #2960: the manager's presence read, which gates the all-stalled warning's raise. Mutations: the read always true, or always false, turns this red.
class StreamPartitionManagerStreamExistsTest {
    @Test
    void streamExists_isFalseBeforeTheStreamIsCreated_andTrueAfter() {
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);

        assertThat(manager.streamExists("orders")).isFalse();

        manager.createStream(StreamConfig.streamConfig("orders"));

        assertThat(manager.streamExists("orders")).isTrue();
        assertThat(manager.streamExists("other")).isFalse();
    }
}
