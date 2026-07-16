// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.widedep;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

/// Injected dependency slice with 16 methods — the wide-dependency fixture for the `Promise.all`
/// batching that lifts a slice factory past the core arity-15 ceiling.
///
/// [com.example.widehost.WideHostSlice] injects this slice, so its generated factory provisions one
/// method handle PER method here (16 handles). Before the fix the processor hard-errored above 15;
/// now it batches the handles into `Tuple` parts and cascades `Tuple.map`. Because
/// slice-processor-tests compiles its generated sources with javac, a malformed batched factory would
/// fail the module build outright.
@Slice
public interface WideDepSlice {
    record Ping(String value) {}

    record Pong(String value) {}

    Promise<Pong> m01(Ping request);

    Promise<Pong> m02(Ping request);

    Promise<Pong> m03(Ping request);

    Promise<Pong> m04(Ping request);

    Promise<Pong> m05(Ping request);

    Promise<Pong> m06(Ping request);

    Promise<Pong> m07(Ping request);

    Promise<Pong> m08(Ping request);

    Promise<Pong> m09(Ping request);

    Promise<Pong> m10(Ping request);

    Promise<Pong> m11(Ping request);

    Promise<Pong> m12(Ping request);

    Promise<Pong> m13(Ping request);

    Promise<Pong> m14(Ping request);

    Promise<Pong> m15(Ping request);

    Promise<Pong> m16(Ping request);

    static WideDepSlice wideDepSlice() {
        return new WideDepSlice() {
            @Override public Promise<Pong> m01(Ping request) { return echo(request); }
            @Override public Promise<Pong> m02(Ping request) { return echo(request); }
            @Override public Promise<Pong> m03(Ping request) { return echo(request); }
            @Override public Promise<Pong> m04(Ping request) { return echo(request); }
            @Override public Promise<Pong> m05(Ping request) { return echo(request); }
            @Override public Promise<Pong> m06(Ping request) { return echo(request); }
            @Override public Promise<Pong> m07(Ping request) { return echo(request); }
            @Override public Promise<Pong> m08(Ping request) { return echo(request); }
            @Override public Promise<Pong> m09(Ping request) { return echo(request); }
            @Override public Promise<Pong> m10(Ping request) { return echo(request); }
            @Override public Promise<Pong> m11(Ping request) { return echo(request); }
            @Override public Promise<Pong> m12(Ping request) { return echo(request); }
            @Override public Promise<Pong> m13(Ping request) { return echo(request); }
            @Override public Promise<Pong> m14(Ping request) { return echo(request); }
            @Override public Promise<Pong> m15(Ping request) { return echo(request); }
            @Override public Promise<Pong> m16(Ping request) { return echo(request); }

            private Promise<Pong> echo(Ping request) {
                return Promise.success(new Pong(request.value()));
            }
        };
    }
}
