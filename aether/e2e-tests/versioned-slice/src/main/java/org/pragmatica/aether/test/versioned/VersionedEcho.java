// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.versioned;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// Two-version test slice for #198 API path-mode versioning.
///
/// The `routes.toml` declares an `[api] prefix = "/api/orders"` and two versions:
///
///   - `[v1.routes] get` → `getV1`, served at `GET /api/orders/v1/{id}`
///   - `[v2.routes] get` → `getV2`, served at `GET /api/orders/v2/{id}`
///
/// Each handler returns a version-tagged response so an HTTP caller can prove that the same bind
/// key resolves to the version-specific implementation behind its `/vN/` path.
@Slice
public interface VersionedEcho {
    record GetRequest(Long id) {}

    record GetResponse(Long id, String version, String message) {}

    Promise<GetResponse> getV1(GetRequest request);
    Promise<GetResponse> getV2(GetRequest request);

    static VersionedEcho versionedEcho() {
        return new VersionedEchoImpl();
    }

    final class VersionedEchoImpl implements VersionedEcho {
        @Override
        public Promise<GetResponse> getV1(GetRequest request) {
            return Promise.success(new GetResponse(request.id(), "v1", "v1-echo-" + request.id()));
        }

        @Override
        public Promise<GetResponse> getV2(GetRequest request) {
            return Promise.success(new GetResponse(request.id(), "v2", "v2-echo-" + request.id()));
        }
    }
}
