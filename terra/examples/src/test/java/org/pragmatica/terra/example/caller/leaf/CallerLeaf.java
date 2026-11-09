// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.example.caller.leaf;

import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.aether.slice.annotation.Slice;
import org.pragmatica.lang.Promise;

/// #2157 Terra fixture: the far end of edge -> mid -> leaf; reports the caller's context it was handed.
@Slice
public interface CallerLeaf {
    Promise<Trail> whoAmI(SecurityContext context, String path);

    static CallerLeaf callerLeaf() {
        return (context, path) -> Promise.success(Trail.trail(context, path + ">leaf"));
    }
}
