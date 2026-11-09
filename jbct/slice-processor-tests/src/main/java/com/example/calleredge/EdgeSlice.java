// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.calleredge;

import com.example.callerleaf.Note;
import com.example.callerleaf.Seen;
import com.example.callermid.MidSlice;

import org.pragmatica.aether.http.handler.security.Principal;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

/// #2157 edge slice: its routes receive the security context the edge put on the request, and pass it on to
/// [MidSlice] explicitly.
@Slice
public interface EdgeSlice {
    Promise<Seen> enter(SecurityContext context, Note note);
    Promise<Seen> local(Principal principal, Note note);
    Promise<Seen> whoami(SecurityContext context);

    static EdgeSlice edgeSlice(MidSlice mid) {
        return new EdgeSlice() {
            @Override
            public Promise<Seen> enter(SecurityContext context, Note note) {
                return mid.relay(context, new Note(note.text() + ">edge"));
            }

            @Override
            public Promise<Seen> local(Principal principal, Note note) {
                return Promise.success(Seen.seen(principal, note.text() + ">edge"));
            }

            @Override
            public Promise<Seen> whoami(SecurityContext context) {
                return Promise.success(Seen.seen(context, "edge"));
            }
        };
    }
}
