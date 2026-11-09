// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.example.caller.mid;

import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;
import org.pragmatica.terra.example.caller.leaf.CallerLeaf;
import org.pragmatica.terra.example.caller.leaf.Trail;

/// #2157 Terra fixture: the middle hop, handing its caller's context on to the leaf.
@Slice
public interface CallerMid {
    Promise<Trail> relay(SecurityContext context, String path);

    static CallerMid callerMid(CallerLeaf leaf) {
        return (context, path) -> leaf.whoAmI(context, path + ">mid");
    }
}
