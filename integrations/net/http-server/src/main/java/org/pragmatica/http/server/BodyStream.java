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
import java.util.function.Consumer;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.concurrent.EventExecutor;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Unit.unit;


/// One streamed request body on its way to a [BodySink] ([Disposition.Stream]), shared by the HTTP/1.1 and HTTP/3
/// pipelines so both apply the same semantics. Confined to the channel's event loop: a sink's promise may resolve on any
/// thread, and its resolution hops back onto the loop before anything here runs.
///
/// **Pull.** The chunks that arrived and are not yet accepted wait in arrival order, and at most one `accept` is
/// outstanding. Reading from the channel is off (`autoRead` false) while a chunk waits or an `accept` is outstanding, and
/// back on when both are drained. What one read already delivered still arrives after reading stops, so the server holds
/// at most what one read of the connection (HTTP/1.1) or of the stream (HTTP/3, at most its flow-control window) delivers.
///
/// **No copy.** A chunk is handed to the sink as a view of the received buffer (one view per component of a composite
/// buffer); the buffer is released when the last `accept` given a view of it resolves.
///
/// **Exactly one terminal call**, never overlapping an `accept`: [BodySink#complete] once the body ended ([#end]) and
/// every chunk was accepted; [BodySink#fail] (through the transport's `onFailed`) when an `accept` failed; and
/// [BodySink#abort] when the server ended the request ([#abort]), deferred until an outstanding `accept` resolves. Every
/// buffer held is released on every one of those exits.
///
/// Per request, not sharable.
final class BodyStream {
    private final EventExecutor executor;
    private final Channel channel;
    private final BodySink sink;
    private final Consumer<Cause> onFailed;
    private final ArrayDeque<Piece> waiting = new ArrayDeque<>();
    private Option<Piece> accepting = none();
    private Option<Runnable> onEnd = none();
    private Option<Cause> abortAfterAccept = none();
    private boolean ended;

    /// A view handed to the sink and the received buffer it is a view of, released once per view.
    private record Piece(ByteBuf owner, ByteBuffer view) {}

    private BodyStream(EventExecutor executor, Channel channel, BodySink sink, Consumer<Cause> onFailed) {
        this.executor = executor;
        this.channel = channel;
        this.sink = sink;
        this.onFailed = onFailed;
    }

    /// Streams a body read from `channel` into `sink`. `onFailed` answers a failed `accept`: it hands the sink its
    /// response through [BodySink#fail] and ends what the transport still reads.
    static BodyStream bodyStream(EventExecutor executor, Channel channel, BodySink sink, Consumer<Cause> onFailed) {
        return new BodyStream(executor, channel, sink, onFailed);
    }

    /// Takes the next chunk of the body, and with it the ownership of `content`: it is released when accepted, or when
    /// the request ends first. Reading stops until it is accepted.
    Unit offer(ByteBuf content) {
        if (ended || !content.isReadable()) {
            content.release();

            return unit();
        }

        var views = content.nioBuffers();

        if (views.length > 1) {
            content.retain(views.length - 1);
        }

        for (var view : views) {
            waiting.add(new Piece(content, view));
        }

        channel.config().setAutoRead(false);
        pump();

        return unit();
    }

    /// The body ended: `complete` runs once every chunk was accepted, as the request's one terminal call. Reading is
    /// not turned back on; the transport decides when to read the next request.
    Unit end(Runnable complete) {
        if (!ended) {
            onEnd = some(complete);
            pump();
        }

        return unit();
    }

    /// The server ended the request without the whole body: what waits is released, and the sink is aborted with
    /// `cause`, once the outstanding `accept` (if any) resolved. Idempotent, and a no-op after any other terminal call.
    Unit abort(Cause cause) {
        if (!ended) {
            ended = true;
            releaseWaiting();
            accepting.apply(() -> sink.abort(cause), _ -> abortAfterAccept = some(cause));
        }

        return unit();
    }

    /// Test seam: whether a received buffer is still held, waiting or being accepted.
    boolean holdsBuffer() {
        return accepting.isPresent() || !waiting.isEmpty();
    }

    /// Accepts the next waiting chunk unless one is outstanding. Never reached once a terminal call was decided: `offer`
    /// and `end` check for that, and the settling of an `accept` reaches it only on success with no abort pending.
    private void pump() {
        if (accepting.isPresent()) {
            return;
        }

        if (waiting.isEmpty()) {
            drained();

            return;
        }

        var next = waiting.poll();

        accepting = some(next);
        // A dependent step runs on the thread that resolves the promise, so the resolution reaches the loop in one hop;
        // an `onResult` handler would first go through the promise's own async executor.
        sink.accept(next.view())
            .withResult(result -> executor.execute(() -> settled(next, result)));
    }

    private void drained() {
        onEnd.apply(() -> channel.config().setAutoRead(true), this::complete);
    }

    private void complete(Runnable complete) {
        ended = true;
        complete.run();
    }

    private void settled(Piece piece, Result<Unit> result) {
        accepting = none();
        piece.owner().release();
        abortAfterAccept.apply(() -> proceed(result), sink::abort);
    }

    private void proceed(Result<Unit> result) {
        result.onFailure(this::failed)
              .onSuccessRun(this::pump);
    }

    private void failed(Cause cause) {
        ended = true;
        releaseWaiting();
        onFailed.accept(cause);
    }

    private void releaseWaiting() {
        waiting.forEach(piece -> piece.owner().release());
        waiting.clear();
    }
}
