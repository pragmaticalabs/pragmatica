// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.pragmatica.aether.http.adapter.impl.SliceRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.http.routing.RequestContext;


/// The security context of a routed request (#2157), read from the request itself: a generated route hands it to a
/// slice method's `Principal`/`SecurityContext` parameter directly, never through ambient state, so an asynchronous step
/// between the edge and the handler cannot lose it.
public sealed interface RequestSecurity {
    /// The context the edge validated and put on `request`; the anonymous context for a request that carries none, as
    /// the edge answers an unauthenticated request.
    static SecurityContext securityOf(RequestContext request) {
        return request instanceof SliceRequestContext sliceRequest
               ? sliceRequest.security()
               : SecurityContext.securityContext();
    }

    record unused() implements RequestSecurity {}
}
