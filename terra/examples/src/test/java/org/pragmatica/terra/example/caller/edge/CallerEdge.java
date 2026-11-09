// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.example.caller.edge;

import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.terra.example.caller.leaf.Trail;
import org.pragmatica.terra.example.caller.mid.CallerMid;

/// #2157 Terra fixture: the HTTP edge of edge -> mid -> leaf; one authenticated route and one public route.
@Slice
public interface CallerEdge {
    Promise<Trail> enter(SecurityContext context);

    Promise<Trail> enterOpen(SecurityContext context);

    static CallerEdge callerEdge(CallerMid mid) {
        return new CallerEdge() {
            @Override
            public Promise<Trail> enter(SecurityContext context) {
                return mid.relay(context, "edge");
            }

            @Override
            public Promise<Trail> enterOpen(SecurityContext context) {
                return mid.relay(context, "open-edge");
            }
        };
    }
}
