// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.versionedslice;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

/// Test slice for verifying #198 API path-mode versioning route generation.
///
/// Its `routes.toml` declares two API versions:
///
///   - `[v1.routes] get` → method `getV1`, mounted at `{api.prefix}/v1/{id}`
///   - `[v2.routes] get` → method `getV2`, mounted at `{api.prefix}/v2/{id}`
///   - `[v2.routes] upsert` → method `upsertV2`, mounted at `{api.prefix}/v2/{id}`
@Slice
public interface VersionedSlice {
    record GetRequest(Long id) {}

    record GetResponse(Long id, String version, String name) {}

    record UpsertRequest(Long id, String name) {}

    record UpsertResponse(Long id, String version, boolean created) {}

    // v1 get → getV1
    Promise<GetResponse> getV1(GetRequest request);

    // v2 get → getV2
    Promise<GetResponse> getV2(GetRequest request);

    // v2 upsert → upsertV2
    Promise<UpsertResponse> upsertV2(UpsertRequest request);

    static VersionedSlice versionedSlice() {
        return new VersionedSlice() {
            @Override
            public Promise<GetResponse> getV1(GetRequest request) {
                return Promise.success(new GetResponse(request.id(), "v1", "item-v1"));
            }

            @Override
            public Promise<GetResponse> getV2(GetRequest request) {
                return Promise.success(new GetResponse(request.id(), "v2", "item-v2"));
            }

            @Override
            public Promise<UpsertResponse> upsertV2(UpsertRequest request) {
                return Promise.success(new UpsertResponse(request.id(), "v2", true));
            }
        };
    }
}
