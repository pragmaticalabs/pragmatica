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


/// The minimum rate a request body must arrive at (F6), the same on HTTP/1.1 and HTTP/3: once `grace` has passed
/// since the request started, the body's bytes are averaged over each `window`, and a body arriving slower than
/// `bytesPerSecond` is refused (HTTP/1.1: the connection is closed; HTTP/3: 408 and the stream's input reset). It
/// complements the idle `readTimeout`, which a client defeats by sending one byte just inside it: a 1 B/s trickle is
/// never idle, but it is far below any minimum rate.
///
/// @param bytesPerSecond the minimum average rate; zero or negative disables the bound
/// @param grace          how long a request may run before the rate is first checked (slow starts, TCP/QUIC ramp-up)
/// @param window         the span each check averages over
public record BodyRateLimit(long bytesPerSecond, TimeSpan grace, TimeSpan window) {
    /// 1 KiB/s, 10 s grace, 10 s window. Guesses, not measurements: in the spirit of Apache's mod_reqtimeout
    /// (`body=20,MinRate=500`), loose enough that a client on a poor mobile link (tens of KiB/s) is never refused, tight
    /// enough that a trickling client releases its aggregation buffer within about 20 s.
    public static final long DEFAULT_BYTES_PER_SECOND = 1024;
    public static final TimeSpan DEFAULT_GRACE = TimeSpan.timeSpan(10).seconds();
    public static final TimeSpan DEFAULT_WINDOW = TimeSpan.timeSpan(10).seconds();
    public static final BodyRateLimit DEFAULT = bodyRateLimit(DEFAULT_BYTES_PER_SECOND, DEFAULT_GRACE, DEFAULT_WINDOW);

    public static BodyRateLimit bodyRateLimit(long bytesPerSecond, TimeSpan grace, TimeSpan window) {
        return new BodyRateLimit(bytesPerSecond, grace, window);
    }

    public boolean enabled() {
        return bytesPerSecond > 0 && window.nanos() > 0;
    }
}
