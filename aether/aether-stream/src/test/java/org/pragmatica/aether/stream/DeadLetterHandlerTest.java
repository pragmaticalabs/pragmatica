// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.DeadLetterHandler.deadLetterHandler;

class DeadLetterHandlerTest {

    private DeadLetterHandler handler;

    @BeforeEach
    void setUp() {
        handler = deadLetterHandler();
    }

    @Nested
    class AppendAndRead {

        @Test
        void read_returnsRecordedEntry_afterAppend() {
            handler.append("orders", 0, 42L, "payload".getBytes(), "processing failed", 3).await().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

            var entries = handler.read("orders", 10);
            assertThat(entries).hasSize(1);
            assertThat(entries.getFirst().streamName()).isEqualTo("orders");
            assertThat(entries.getFirst().partition()).isEqualTo(0);
            assertThat(entries.getFirst().offset()).isEqualTo(42L);
            assertThat(entries.getFirst().payload()).isEqualTo("payload".getBytes());
            assertThat(entries.getFirst().errorMessage()).isEqualTo("processing failed");
            assertThat(entries.getFirst().attemptCount()).isEqualTo(3);
            assertThat(entries.getFirst().timestamp()).isGreaterThan(0L);
        }

        @Test
        void read_respectsMaxCount_whenMoreEntriesExist() {
            handler.append("orders", 0, 1L, "a".getBytes(), "err", 1).await().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
            handler.append("orders", 0, 2L, "b".getBytes(), "err", 1).await().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
            handler.append("orders", 0, 3L, "c".getBytes(), "err", 1).await().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

            var entries = handler.read("orders", 2);
            assertThat(entries).hasSize(2);
        }

        @Test
        void read_returnsEmptyList_whenNoEntries() {
            var entries = handler.read("nonexistent", 10);
            assertThat(entries).isEmpty();
        }

        @Test
        void read_isolatesStreams_differentStreamNames() {
            handler.append("orders", 0, 1L, "a".getBytes(), "err", 1).await().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));
            handler.append("events", 0, 2L, "b".getBytes(), "err", 1).await().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

            assertThat(handler.read("orders", 10)).hasSize(1);
            assertThat(handler.read("events", 10)).hasSize(1);
        }

        @Test
        void read_returnsAll_whenMaxCountExceedsEntries() {
            handler.append("orders", 0, 1L, "a".getBytes(), "err", 1).await().onFailure(cause -> org.junit.jupiter.api.Assertions.fail(cause.message()));

            var entries = handler.read("orders", 100);
            assertThat(entries).hasSize(1);
        }
    }
}
