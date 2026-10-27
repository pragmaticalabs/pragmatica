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

import org.pragmatica.lang.io.TimeSpan;


/// The time bounds a route puts on its request body, in place of the listener's general ones: an `idle` bound (no byte for
/// that long), a minimum `rate`, and a `maxDuration` for the whole body (zero: none). A request that breaks one is
/// answered 408 and its connection is closed.
///
/// @param idle        a body that delivers no byte for this long is refused
/// @param rate        the minimum rate the body must arrive at (see [BodyRateLimit])
/// @param maxDuration the longest the whole body may take, from the request HEADERS; zero or negative: no bound
public record BodyBounds(TimeSpan idle, BodyRateLimit rate, TimeSpan maxDuration) {
    public static BodyBounds bodyBounds(TimeSpan idle, BodyRateLimit rate, TimeSpan maxDuration) {
        return new BodyBounds(idle, rate, maxDuration);
    }
}
