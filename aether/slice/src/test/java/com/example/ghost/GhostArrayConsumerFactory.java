// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package com.example.ghost;

import java.util.List;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;

/// #758 fixture: a consumer slice factory whose signature references [GhostProviderType] as an
/// ARRAY — the JVM then reports the descriptor `[Lcom/example/ghost/GhostProviderType;`, which the
/// diagnosis must unwrap to the element class before deciding anything.
public class GhostArrayConsumerFactory {
    public static Promise<Slice> ghostArrayConsumerSlice(GhostProviderType[] ignored) {
        return Promise.success(new Slice() {
            @Override
            public List<SliceMethod<?, ?>> methods() {
                return List.of();
            }
        });
    }
}
