// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.widehost;

import com.example.widedep.WideDepSlice;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

/// Host slice that injects [WideDepSlice] (16 methods). Its generated `WideHostSliceFactory` must
/// provision 16 slice-method handles — past the flat `Promise.all` arity-15 ceiling — proving the
/// batched assembly generates and compiles through the real module build.
@Slice
public interface WideHostSlice {
    record Request(String value) {}

    record Response(String value) {}

    Promise<Response> handle(Request request);

    static WideHostSlice wideHostSlice(WideDepSlice dep) {
        return request -> dep.m16(new WideDepSlice.Ping(request.value()))
                             .map(pong -> new Response(pong.value()));
    }
}
