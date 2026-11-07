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

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import org.pragmatica.http.HttpRequest;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;


/// A [BodySink] whose `accept`s the test resolves by hand, for the embedded-channel tests: every `accept` is pending until
/// [#succeedNext] or [#failNext]. Records the bytes of each chunk (copied while the chunk is valid), every terminal call,
/// and any terminal call made while an `accept` was outstanding.
final class ManualSink implements BodySink {
    private final ArrayDeque<Promise<Unit>> pending = new ArrayDeque<>();
    private final List<Integer> chunkSizes = new ArrayList<>();
    private final List<String> terminals = new ArrayList<>();
    private final List<String> violations = new ArrayList<>();
    private Option<HttpRequest> completed = Option.none();
    private Option<ResponseWriter> writer = Option.none();
    private int maxPending;

    @Override
    public Promise<Unit> accept(ByteBuffer chunk) {
        var promise = Promise.<Unit>promise();

        chunkSizes.add(chunk.remaining());
        pending.add(promise);
        maxPending = Math.max(maxPending, pending.size());

        return promise;
    }

    @Override
    public void complete(HttpRequest request, ResponseWriter response) {
        terminal("complete");
        completed = Option.some(request);
        writer = Option.some(response);
    }

    @Override
    public void fail(Cause cause, ResponseWriter response) {
        terminal("fail");
        writer = Option.some(response);
    }

    @Override
    public void abort(Cause cause) {
        terminal("abort:" + ((Ended) cause).name());
    }

    private void terminal(String name) {
        if (!pending.isEmpty()) {
            violations.add(name + " while an accept was outstanding");
        }

        terminals.add(name);
    }

    /// Resolves the oldest outstanding `accept` successfully.
    void succeedNext() {
        pending.poll().succeed(unit());
    }

    /// Fails the oldest outstanding `accept`.
    void failNext() {
        pending.poll().fail(RecordingSink.Rejected.REJECTED);
    }

    int outstanding() {
        return pending.size();
    }

    int maxPending() {
        return maxPending;
    }

    List<Integer> chunkSizes() {
        return chunkSizes;
    }

    List<String> terminals() {
        return terminals;
    }

    List<String> violations() {
        return violations;
    }

    Option<HttpRequest> completed() {
        return completed;
    }

    Option<ResponseWriter> writer() {
        return writer;
    }
}
