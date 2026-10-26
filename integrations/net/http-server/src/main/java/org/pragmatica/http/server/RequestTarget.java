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
package org.pragmatica.http.server;

import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import io.netty.handler.codec.http.QueryStringDecoder;


/// The decoded path and query of a request target, the one place either transport percent-decodes it (#2419): HTTP/1.1
/// and HTTP/3 both build `HttpRequest.path()` and `HttpRequest.queryParams()` from this, so a handler sees the same
/// values whichever transport carried the request. The path keeps `+` literal; only the query reads it as a space.
/// Everything past this point treats the values as decoded and never decodes them again (#2420).
record RequestTarget(String path, QueryParams queryParams) {
    /// A malformed percent-escape (`%zz`, a lone `%`) is a failure, not a throw: each transport answers it 400. A query
    /// with more than the decoder's 1024 parameters is not a failure: the decoder drops the excess silently, on both
    /// transports alike.
    static Result<RequestTarget> requestTarget(String uri) {
        return Result.lift(t -> new HttpServerError.MalformedRequestTarget(Causes.fromThrowable(t)),
                           () -> decode(uri));
    }

    private static RequestTarget decode(String uri) {
        var decoder = new QueryStringDecoder(uri);

        return new RequestTarget(decoder.path(),
                                 QueryParams.queryParams(decoder.parameters()));
    }
}
