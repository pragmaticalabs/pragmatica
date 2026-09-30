// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.ErrorMapper;
import org.pragmatica.aether.stream.StreamError;
import org.pragmatica.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;


/// stream-offheap-budget-spec §6 / reconciliation #9: a `STREAM_MEMORY_EXCEEDED` reaching an app route
/// (the floor-create failure now propagates instead of being masked) maps to HTTP 503 with the exact
/// off-heap detail. `ErrorMapper.defaultMapper` routes a non-`HttpError` cause carrying the cause message
/// to 500 — or 503 when the cause is transient (#1737). The budget cause IS transient (a capacity shortage
/// that clears as the pool frees), so it answers 503. This test previously pinned 500 from before that
/// ruling; the Management-API publish path does not use `ErrorMapper` and still answers 500.
class StreamRoutesPublishMemoryExceededTest {
    private static final String EXPECTED_DETAIL = "Total off-heap memory limit exceeded";

    @Test
    void streamRoutes_publish_memoryExceeded_maps503WithDetail() {
        var mapped = ErrorMapper.defaultMapper().map(StreamError.General.STREAM_MEMORY_EXCEEDED);

        assertThat(mapped.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(StreamError.General.STREAM_MEMORY_EXCEEDED.message()).isEqualTo(EXPECTED_DETAIL);
        assertThat(mapped.message()).contains(EXPECTED_DETAIL);
    }
}
