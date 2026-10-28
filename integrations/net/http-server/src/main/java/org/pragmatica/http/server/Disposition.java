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

import java.util.function.Consumer;

import org.pragmatica.lang.Option;


/// What the server does with a request, decided on its HEADERS before any body byte is read.
public sealed interface Disposition {
    /// Read the body whole, up to `maxBytes`, and dispatch the request.
    ///
    /// `bounds` replaces the listener's general idle and minimum-rate bounds when present (an upload route); `release` runs
    /// exactly once when the body has been received or the request ended without it (refused, timed out, connection
    /// lost), so a concurrency permit taken at classification is returned.
    ///
    /// A body that crosses `maxBytes` (or a `Content-Length` above it) is answered 413 and the connection is closed
    /// without reading the rest.
    record Aggregate(long maxBytes, Option<BodyBounds> bounds, Runnable release) implements Disposition {}

    /// Answer now and close the connection without reading the body (RFC 9110 §10.1.1): `respond` writes the final
    /// response, so a 401 or a 429 is exactly the one the handler would have written.
    record Reject(Consumer<ResponseWriter> respond) implements Disposition {}

    /// Aggregate up to `maxBytes` under the listener's general bounds.
    static Disposition aggregate(long maxBytes) {
        return new Aggregate(maxBytes,
                             Option.empty(),
                             () -> {});
    }

    static Disposition aggregate(long maxBytes, BodyBounds bounds, Runnable release) {
        return new Aggregate(maxBytes, Option.some(bounds), release);
    }

    static Disposition reject(Consumer<ResponseWriter> respond) {
        return new Reject(respond);
    }
}
