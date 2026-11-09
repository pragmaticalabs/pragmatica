// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.callermid;

import com.example.callerleaf.LeafSlice;
import com.example.callerleaf.Note;
import com.example.callerleaf.Seen;

import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

/// #2157 middle hop: each method passes the context it was given straight on to [LeafSlice], so the leaf sees the
/// identity the edge validated two hops away.
@Slice
public interface MidSlice {
    Promise<Seen> relay(SecurityContext context, Note note);
    Promise<Seen> relayContextOnly(SecurityContext context);
    Promise<Seen> relayBoth(SecurityContext context, Note note);

    static MidSlice midSlice(LeafSlice leaf) {
        return new MidSlice() {
            @Override
            public Promise<Seen> relay(SecurityContext context, Note note) {
                return leaf.whoAmI(context, new Note(note.text() + ">mid"));
            }

            @Override
            public Promise<Seen> relayContextOnly(SecurityContext context) {
                return leaf.contextOnly(context);
            }

            @Override
            public Promise<Seen> relayBoth(SecurityContext context, Note note) {
                return leaf.both(context.principal(), context, new Note(note.text() + ">mid"));
            }
        };
    }
}
