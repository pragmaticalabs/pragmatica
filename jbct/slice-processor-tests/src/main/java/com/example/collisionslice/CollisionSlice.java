// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.collisionslice;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

/// Regression slice for the route-import simple-name collision bug.
///
/// The package declares two unrelated error families ([BuyError] and [CancelError]) whose nested
/// failure records share the simple name `StoreUnavailable`. Both are discovered for this slice and
/// mapped via `routes.toml`. The generator must reference them by fully-qualified name in the error
/// switch and must NOT emit two single-type imports of the same simple name (which fails to compile).
@Slice
public interface CollisionSlice {
    record BuyRequest(Long id) {}

    record CancelRequest(Long id) {}

    record OpResponse(Long id, String status) {}

    Promise<OpResponse> buy(BuyRequest request);

    Promise<OpResponse> cancel(CancelRequest request);

    static CollisionSlice collisionSlice() {
        return new CollisionSlice() {
            @Override
            public Promise<OpResponse> buy(BuyRequest request) {
                return Promise.success(new OpResponse(request.id(), "bought"));
            }

            @Override
            public Promise<OpResponse> cancel(CancelRequest request) {
                return Promise.success(new OpResponse(request.id(), "cancelled"));
            }
        };
    }
}
