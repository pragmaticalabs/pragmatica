// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package com.example.hostslice;

import com.example.injectedslice.QuoteSlice;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

/// Host slice that INJECTS [QuoteSlice], where BOTH slices declare nested `Request`/`Response` records.
///
/// Permanent regression fixture for the codec-generation shadowing bug (PR #364, part 2): the generated
/// `BookingSliceFactory` adapter record `implements BookingSlice`, whose nested `Request`/`Response` are
/// inherited member types. By JLS 6.5.5.2 they shadow single-type imports, so a SIMPLE name for the
/// injected [QuoteSlice]'s `Request`/`Response` in the generated `codec()` body would silently resolve to
/// THIS slice's types. The fix emits fully-qualified names for those codec references.
///
/// The nested records here use DIFFERENT arity from [QuoteSlice]'s (Request: 2 vs 1, Response: 3 vs 2), so
/// a regression to simple names is a hard, wrong-arity compile failure — and because this module compiles
/// its generated sources with javac, that failure breaks the build (the strongest possible regression gate).
@Slice
public interface BookingSlice {
    record Request(String seat, String customer) {}

    record Response(String booking, long total, String currency) {}

    Promise<Response> book(Request request);

    static BookingSlice bookingSlice(QuoteSlice quote) {
        return new BookingSlice() {
            @Override
            public Promise<Response> book(Request request) {
                return quote.quote(new QuoteSlice.Request(request.seat()))
                            .map(quoted -> confirm(request, quoted));
            }
        };
    }

    private static Response confirm(Request request, QuoteSlice.Response quoted) {
        return new Response(request.customer() + ":" + request.seat(), quoted.amountMinor(), "USD");
    }
}
