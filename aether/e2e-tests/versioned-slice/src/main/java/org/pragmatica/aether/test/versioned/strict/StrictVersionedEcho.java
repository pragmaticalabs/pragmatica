// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.test.versioned.strict;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// Two-version test slice for #198 §7 HEADER-mode versioning with `requireVersionHeader = true`.
///
/// Its `routes.toml` declares `[api] prefix = "/api/strict"`, `requireVersionHeader = true`, and two
/// versions (`get` → `getV1` / `getV2`). When deployed in header mode a request omitting the version
/// header must be rejected with `400` (§7), proving the required-header gate; a request naming a
/// valid version is dispatched to that version's handler.
@Slice
public interface StrictVersionedEcho {
    record GetRequest(Long id) {}

    record GetResponse(Long id, String version, String message) {}

    Promise<GetResponse> getV1(GetRequest request);
    Promise<GetResponse> getV2(GetRequest request);

    static StrictVersionedEcho strictVersionedEcho() {
        return new StrictVersionedEchoImpl();
    }

    final class StrictVersionedEchoImpl implements StrictVersionedEcho {
        @Override
        public Promise<GetResponse> getV1(GetRequest request) {
            return Promise.success(new GetResponse(request.id(), "v1", "strict-v1-echo-" + request.id()));
        }

        @Override
        public Promise<GetResponse> getV2(GetRequest request) {
            return Promise.success(new GetResponse(request.id(), "v2", "strict-v2-echo-" + request.id()));
        }
    }
}
