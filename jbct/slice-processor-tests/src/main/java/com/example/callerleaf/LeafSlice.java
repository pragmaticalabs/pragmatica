// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.callerleaf;

import org.pragmatica.aether.http.handler.RequestHeaders;
import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

/// #2157 receiving slice: every shape of a method called on its caller's behalf. A `SecurityContext` parameter takes
/// the caller's identity from beside the payload; the payload is what is left (one record, nothing, or a record and
/// `RequestHeaders`). A method taking only a `Principal` keeps it in the payload (guess G1b).
@Slice
public interface LeafSlice {
    Promise<Seen> whoAmI(SecurityContext context, Note note);
    Promise<Seen> contextOnly(SecurityContext context);
    Promise<Seen> both(Principal principal, SecurityContext context, Note note);
    Promise<Seen> withHeaders(SecurityContext context, RequestHeaders headers, Note note);
    Promise<Seen> principalOnly(Principal principal, Note note);

    static LeafSlice leafSlice() {
        return new LeafSlice() {
            @Override
            public Promise<Seen> whoAmI(SecurityContext context, Note note) {
                return Promise.success(Seen.seen(context, note.text() + ">leaf"));
            }

            @Override
            public Promise<Seen> contextOnly(SecurityContext context) {
                return Promise.success(Seen.seen(context, "leaf"));
            }

            @Override
            public Promise<Seen> both(Principal principal, SecurityContext context, Note note) {
                return Promise.success(Seen.seen(context, note.text() + ">leaf:principal=" + principal.value()));
            }

            @Override
            public Promise<Seen> withHeaders(SecurityContext context, RequestHeaders headers, Note note) {
                return Promise.success(Seen.seen(context,
                                                 note.text() + ">leaf:probe=" + headers.first("x-probe")
                                                                                      .or("none")));
            }

            @Override
            public Promise<Seen> principalOnly(Principal principal, Note note) {
                return Promise.success(Seen.seen(principal, note.text() + ">leaf"));
            }
        };
    }
}
