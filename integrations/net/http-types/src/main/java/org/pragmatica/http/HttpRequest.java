/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.http;

import java.nio.charset.StandardCharsets;

/// Transport-agnostic HTTP request surface.
///
/// The common, `Route`-free, Netty-free base shared by every request abstraction: the
/// transport-edge request (Netty/HTTP3 servers) and the post-routing handler context
/// ([org.pragmatica.http.routing.RequestContext], which extends this with `route()`, JSON
/// body parsing and path/query matching).
public interface HttpRequest {
    /// Unique request ID for tracing and logging.
    /// Format: req_[ulid] (e.g., req_01hq4x2abc...)
    String requestId();

    /// HTTP method.
    HttpMethod method();

    /// Request path (without query string).
    String path();

    /// Request headers.
    Headers headers();

    /// Query parameters.
    QueryParams queryParams();

    /// Request body as bytes.
    byte[] body();

    /// Request body as UTF-8 string.
    default String bodyAsString() {
        var bytes = body();
        return bytes.length == 0
               ? ""
               : new String(bytes, StandardCharsets.UTF_8);
    }

    /// Check if request has a body.
    default boolean hasBody() {
        return body().length > 0;
    }
}
