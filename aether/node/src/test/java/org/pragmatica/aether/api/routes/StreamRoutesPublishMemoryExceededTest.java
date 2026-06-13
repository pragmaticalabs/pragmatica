// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.http.routing.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;


/// stream-offheap-budget-spec §6 / reconciliation #9: a `STREAM_MEMORY_EXCEEDED` reaching the publish
/// route (the floor-create failure now propagates here instead of being masked) maps to HTTP 500 with
/// the exact off-heap detail. `ErrorMapper.defaultMapper` already routes any non-`HttpError` cause to
/// 500 carrying the cause message — this pins that behaviour for the budget cause specifically.
class StreamRoutesPublishMemoryExceededTest {
    private static final String EXPECTED_DETAIL = "Total off-heap memory limit exceeded";

    @Test
    void streamRoutes_publish_memoryExceeded_maps500WithDetail() {
        var mapped = ErrorMapper.defaultMapper().map(StreamError.General.STREAM_MEMORY_EXCEEDED);

        assertThat(mapped.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(StreamError.General.STREAM_MEMORY_EXCEEDED.message()).isEqualTo(EXPECTED_DETAIL);
        assertThat(mapped.message()).contains(EXPECTED_DETAIL);
    }
}
