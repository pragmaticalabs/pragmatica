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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

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
/// **Pacing.** Turning reading off and on is reported to the transport ([Pacing#hold], [Pacing#resume]), so the client's
/// bounds do not run while the server, not the client, is the one waiting. The sink's wait is bounded on its own: an
/// `accept` outstanding for [Pacing#stallBound] runs [Pacing#onStalled] once; the transport answers and ends the request.
///
/// **Exactly one terminal call**, never overlapping an `accept`: [BodySink#complete] once the body ended ([#end]) and
/// every chunk was accepted; [BodySink#fail] (through the transport's `onFailed`) when an `accept` failed; and
/// [BodySink#abort] when the server ended the request ([#abort]), deferred until an outstanding `accept` resolves, but
/// no longer than [Pacing#stallBound]: an `accept` that stays outstanding past that is abandoned and the sink is aborted
/// anyway (the one deliberate overlap), so a hung sink cannot hold the request's admission forever; the abandoned
/// `accept`'s late resolution only releases its buffer. Every buffer held is released on every one of those exits.
///
/// Per request, not sharable.
final class BodyStream {
    /// How the transport follows the stream: `hold` runs when the stream turns reading off to wait for its sink, `resume`
    /// when it turns reading back on; `onStalled` runs once when one `accept` stays outstanding for `stallBound` (zero or
    /// less: no bound).
    record Pacing(Runnable hold, Runnable resume, TimeSpan stallBound, Runnable onStalled) {
        static Pacing pacing(Runnable hold, Runnable resume, TimeSpan stallBound, Runnable onStalled) {
            return new Pacing(hold, resume, stallBound, onStalled);
        }

        /// What the transport answers a stalled sink: 503, retryable after the stall bound (at least one second). The
        /// server is behind, not the client.
        @Contract
        void answerStalled(ResponseWriter writer) {
            writer.header("Retry-After",
                          String.valueOf(Math.max(1,
                                                  TimeUnit.NANOSECONDS.toSeconds(stallBound.nanos()))))
                  .error(HttpStatus.SERVICE_UNAVAILABLE, "Request body storage stalled; retry later");
        }
    }

    private final EventExecutor executor;
    private final Channel channel;
    private final BodySink sink;
    private final Consumer<Cause> onFailed;
    private final Pacing pacing;
    private Option<ScheduledFuture<?>> stallTimer = none();
    private final ArrayDeque<Piece> waiting = new ArrayDeque<>();
    private Option<Piece> accepting = none();
    private Option<Runnable> onEnd = none();
    private Option<Cause> abortAfterAccept = none();
    private Option<ScheduledFuture<?>> abortTimer = none();
    private boolean abandoned;
    private boolean ended;

    /// A view handed to the sink and the received buffer it is a view of, released once per view.
    private record Piece(ByteBuf owner, ByteBuffer view) {}

    private BodyStream(EventExecutor executor,
                       Channel channel,
                       BodySink sink,
                       Consumer<Cause> onFailed,
                       Pacing pacing) {
        this.executor = executor;
        this.channel = channel;
        this.sink = sink;
        this.onFailed = onFailed;
        this.pacing = pacing;
    }

    /// Streams a body read from `channel` into `sink`. `onFailed` answers a failed `accept`: it hands the sink its
    /// response through [BodySink#fail] and ends what the transport still reads. `pacing` is told when reading stops and
    /// resumes, and when the sink stalls.
    static BodyStream bodyStream(EventExecutor executor,
                                 Channel channel,
                                 BodySink sink,
                                 Consumer<Cause> onFailed,
                                 Pacing pacing) {
        return new BodyStream(executor, channel, sink, onFailed, pacing);
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
        pacing.hold().run();
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
            cancelStall();
            releaseWaiting();
            accepting.apply(() -> sink.abort(cause), _ -> deferAbort(cause));
        }

        return unit();
    }

    /// The abort waits for the outstanding `accept`, for at most the stall bound.
    private void deferAbort(Cause cause) {
        abortAfterAccept = some(cause);
        abortTimer = pacing.stallBound().nanos() > 0
                     ? some(executor.schedule(this::abortDeadline,
                                              pacing.stallBound().nanos(),
                                              TimeUnit.NANOSECONDS))
                     : none();
    }

    /// The `accept` is still outstanding at the deadline: it is abandoned and the sink aborted now. Its late resolution
    /// finds the abort already given and does nothing but release its buffer.
    private void abortDeadline() {
        abortTimer = none();
        abortAfterAccept.onPresent(this::abandonAccept);
    }

    private void abandonAccept(Cause cause) {
        abandoned = true;
        sink.abort(cause);
    }

    /// How the transport follows this stream.
    Pacing pacing() {
        return pacing;
    }

    /// Test seam: whether a received buffer is still held, waiting or being accepted.
    boolean holdsBuffer() {
        return accepting.isPresent() || !waiting.isEmpty();
    }

    private void pump() {
        if (ended || accepting.isPresent()) {
            return;
        }

        if (waiting.isEmpty()) {
            drained();

            return;
        }

        var next = waiting.poll();

        accepting = some(next);
        armStall();
        sink.accept(next.view()).onResult(result -> executor.execute(() -> settled(next, result)));
    }

    private void drained() {
        onEnd.apply(this::readOn, this::complete);
    }

    private void readOn() {
        channel.config().setAutoRead(true);
        pacing.resume().run();
    }

    private void armStall() {
        stallTimer = pacing.stallBound().nanos() > 0
                     ? some(executor.schedule(this::stalled,
                                              pacing.stallBound().nanos(),
                                              TimeUnit.NANOSECONDS))
                     : none();
    }

    private void cancelStall() {
        stallTimer.onPresent(timer -> timer.cancel(false));
        stallTimer = none();
    }

    /// An `accept` stayed outstanding for the stall bound: the transport answers and ends the request, which aborts the
    /// sink once that `accept` resolves.
    private void stalled() {
        stallTimer = none();
        if (!ended) {
            pacing.onStalled().run();
        }
    }

    private void complete(Runnable complete) {
        ended = true;
        complete.run();
    }

    private void settled(Piece piece, Result<Unit> result) {
        cancelStall();
        abortTimer.onPresent(timer -> timer.cancel(false));
        abortTimer = none();
        accepting = none();
        piece.owner().release();
        if (!abandoned) {
            abortAfterAccept.apply(() -> proceed(result), sink::abort);
        }
    }

    private void proceed(Result<Unit> result) {
        switch (result) {
            case Result.Success<Unit>_ -> pump();
            case Result.Failure<Unit>(var cause) -> failed(cause);
        }
    }

    private void failed(Cause cause) {
        ended = true;
        releaseWaiting();
        onFailed.accept(cause);
    }

    private void releaseWaiting() {
        waiting.forEach(piece -> piece.owner()
                                      .release());
        waiting.clear();
    }
}
