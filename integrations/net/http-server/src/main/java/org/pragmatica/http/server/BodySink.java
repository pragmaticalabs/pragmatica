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
package org.pragmatica.http.server;

import java.nio.ByteBuffer;

import org.pragmatica.http.HttpRequest;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// Takes a request body as it arrives instead of having it aggregated ([Disposition.Stream]), on HTTP/1.1 and HTTP/3
/// alike. The server pulls: it reads no further body bytes from the connection (HTTP/1.1) or the stream (HTTP/3) while a
/// chunk is being accepted, so a sink that is slow to accept slows the client down instead of growing a buffer.
///
/// Exactly one terminal call is made, after the last `accept` has resolved: [#complete] when the whole body arrived,
/// [#fail] when an `accept` failed, or [#abort] when the server ended the request itself. No call overlaps another.
public interface BodySink {
    /// Takes one chunk of the body. The buffer is valid only until the returned promise resolves: the sink must not keep
    /// it, or anything sliced from it, past that point. A failed promise ends the request with [#fail].
    Promise<Unit> accept(ByteBuffer chunk);

    /// The whole body arrived and was accepted. `request` is the request's head and trailers with an empty body; the sink
    /// now owns `response` and must answer it.
    @Contract
    void complete(HttpRequest request, ResponseWriter response);

    /// An `accept` failed with `cause`. The server reads no more of the body; the sink answers `response`, and the server
    /// then ends the connection (HTTP/1.1) or the stream's input (HTTP/3), since the rest of the body is unread.
    @Contract
    void fail(Cause cause, ResponseWriter response);

    /// The server ended the request without the whole body: it crossed the size limit, timed out, or the connection or
    /// stream went away. The server answers the client itself where it still can; the sink only cleans up.
    @Contract
    void abort(Cause cause);

    /// Why the server ended a streamed request, as passed to [#abort].
    enum Ended implements Cause {
        TOO_LARGE("Request body exceeds the limit"),
        TIMED_OUT("Request body timed out"),
        DISCONNECTED("Connection or stream closed before the body ended"),
        MALFORMED("Request could not be read");

        private final String message;

        Ended(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }
}
