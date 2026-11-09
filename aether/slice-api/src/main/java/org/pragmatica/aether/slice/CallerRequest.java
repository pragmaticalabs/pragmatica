// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;

/// An inter-slice request together with its caller's identity (#2157). In-process only, like
/// [org.pragmatica.aether.slice.topic.ContextualEvent]: it is never encoded. On the sending side a generated proxy hands
/// it to its method handle; the invoker encodes [#request] as the payload and carries [#caller] beside it (on the wire,
/// in the invoke message). On the receiving side a generated method whose slice method takes a `SecurityContext`
/// declares this type as its parameter, and the bridge wraps the decoded payload with the caller it was given.
///
/// @param request the business request (`Unit`, the single business parameter, or the generated request record)
/// @param caller  the caller's identity; empty for an unauthenticated caller
public record CallerRequest(Object request, Option<CallerIdentity> caller) {
    public static CallerRequest callerRequest(Object request, Option<CallerIdentity> caller) {
        return new CallerRequest(request, caller);
    }

    /// The object to encode as the payload: the business request of a [CallerRequest], any other request itself.
    public static Object payloadOf(Object request) {
        return request instanceof CallerRequest callerRequest
               ? callerRequest.request()
               : request;
    }

    /// The caller identity a request carries: empty for anything but a [CallerRequest].
    public static Option<CallerIdentity> callerOf(Object request) {
        return request instanceof CallerRequest callerRequest
               ? callerRequest.caller()
               : Option.none();
    }
}
