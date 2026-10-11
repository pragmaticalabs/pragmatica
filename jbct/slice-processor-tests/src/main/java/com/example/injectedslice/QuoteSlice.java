// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.injectedslice;

import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;


/// Injected dependency slice for the "slice-injects-slice, both with Request/Response" regression fixture.
///
/// [QuoteSlice] is injected into [com.example.hostslice.BookingSlice]. Both slices declare nested
/// `Request`/`Response` records, so in the host factory's generated `codec()` — whose adapter record
/// `implements BookingSlice` — a SIMPLE reference to this slice's `Request`/`Response` would be shadowed by
/// the host's inherited nested member types (JLS 6.5.5.2). These records use DIFFERENT arity from the
/// host's (Request: 1 vs 2, Response: 2 vs 3) so a regression to simple names is a hard, wrong-arity
/// compile failure rather than a silent mis-binding.
@Slice
public interface QuoteSlice {
    record Request(String event) {}

    record Response(String event, long amountMinor) {}

    Promise<Response> quote(Request request);

    static QuoteSlice quoteSlice() {
        return new QuoteSlice() {
            @Override
            public Promise<Response> quote(Request request) {
                return Promise.success(new Response(request.event(), 1_000L));
            }
        };
    }
}
