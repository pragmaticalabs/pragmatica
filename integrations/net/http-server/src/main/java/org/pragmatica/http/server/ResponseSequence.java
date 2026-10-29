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

import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.EventExecutor;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;


/// The order in which the responses of one HTTP/1.1 connection are written (RFC 9112 section 9.3.2: in the order of the
/// requests, whichever handler finishes first).
///
/// A slot is reserved when a request is answered by a writer, on the event loop and so in request order. A finished
/// response is handed over from any thread, and is WRITTEN only by the event loop, which writes the ready slots from the
/// first one on and stops at the first that is not ready: the order of the writes is the order of the slots, never the
/// order in which handlers finished or the thread they finished on. What a response does after it is written, such as
/// closing the connection for `Connection: close`, is part of its write and so also waits its turn: a later close never
/// overtakes an earlier response. A response that ends the connection also ends the sequence: nothing behind it is
/// written.
///
/// A slot that is not completed within the timeout is completed by its guard, which answers 504 in its place (the
/// writer's `expire`), so one stuck handler holds the responses behind it for at most that long.
///
/// **Bounds.** The responses held are bounded by the unanswered requests the codec accepts (its pipeline depth, 128),
/// and so the memory held is at most 128 times the largest response body; there is no byte limit. A 129th unanswered
/// request makes the codec close the connection, and the answers held for the earlier requests are discarded with it, so
/// behind a handler that never answers (and before its timeout) the cap loses them.
///
/// Per connection; completed from any thread, drained on the event loop.
final class ResponseSequence {
    private static final AttributeKey<ResponseSequence> SEQUENCE = AttributeKey.valueOf("responseSequence");

    private final ScheduledExecutorService loop;
    private final TimeSpan timeout;
    private final ArrayDeque<Slot> pending = new ArrayDeque<>();
    private boolean closed;

    ResponseSequence(ScheduledExecutorService loop, TimeSpan timeout) {
        this.loop = loop;
        this.timeout = timeout;
    }

    /// Installs the connection's sequence with the response timeout of the server (zero or negative: no bound).
    @Contract
    static void install(Channel channel, TimeSpan timeout) {
        channel.attr(SEQUENCE).set(new ResponseSequence(channel.eventLoop(), timeout));
    }

    /// The connection's sequence; one without a timeout when none was installed.
    static ResponseSequence of(Channel channel) {
        var created = new ResponseSequence(channel.eventLoop(),
                                           TimeSpan.timeSpan(0).millis());

        return option(channel.attr(SEQUENCE).setIfAbsent(created)).or(created);
    }

    /// The place of the next response, taken in request order.
    synchronized Slot reserve() {
        var slot = new Slot();

        pending.addLast(slot);

        return slot;
    }

    /// Answers the slot with `onTimeout` if it is still not completed when the timeout passes.
    @Contract
    void guard(Slot slot, Runnable onTimeout) {
        if (timeout.nanos() > 0) {
            slot.timer = some(loop.schedule(onTimeout, timeout.nanos(), TimeUnit.NANOSECONDS));
        }
    }

    /// Hands over the slot's write. It runs on the event loop once every earlier slot has been written; `closes` says the
    /// write ends the connection, so nothing behind it is written. The first completion of a slot counts, a later one
    /// (a response arriving after its timeout answered) is ignored.
    @Contract
    void complete(Slot slot, Runnable write, boolean closes) {
        if (slot.claim(write, closes)) {
            slot.cancelTimer();
            submitDrain();
        }
    }

    /// A completion on the event loop drains at once: every earlier slot that is ready has been marked ready before this
    /// call, so the drain writes them first, in order. One from another thread hands the drain to the loop, which is the only
    /// place a write happens. A loop that is shut down has nothing left to write: the connection is gone with it.
    @Contract
    private void submitDrain() {
        if (onLoop()) {
            drain();
        } else {
            hopToLoop();
        }
    }

    private boolean onLoop() {
        return loop instanceof EventExecutor executor && executor.inEventLoop();
    }

    @Contract
    private void hopToLoop() {
        try {
            loop.execute(this::drain);
        } catch (RejectedExecutionException e) {
        // the event loop is shut down; the connection is closed with it
        }
    }

    private synchronized void drain() {
        Stream.generate(this::pollReady).takeWhile(Option::isPresent).forEach(ready -> ready.onPresent(this::run));
    }

    private Option<Slot> pollReady() {
        return closed || pending.isEmpty() || !pending.peekFirst()
                                                      .isReady()
               ? none()
               : some(pending.pollFirst());
    }

    private void run(Slot slot) {
        slot.write.run();
        closed = slot.closes;
        if (closed) {
            abandonPending();
        }
    }

    /// The connection ends with the write that just ran: the slots queued behind it will never be written, so their timers
    /// are cancelled instead of firing later to answer a request nobody can receive.
    private void abandonPending() {
        pending.forEach(Slot::cancelTimer);
        pending.clear();
    }

    static final class Slot {
        private final AtomicBoolean claimed = new AtomicBoolean(false);
        private volatile boolean ready;
        private volatile Runnable write;
        private volatile boolean closes;
        private volatile Option<ScheduledFuture<?>> timer = none();

        private boolean claim(Runnable write, boolean closes) {
            if (!claimed.compareAndSet(false, true)) {
                return false;
            }

            this.write = write;
            this.closes = closes;
            this.ready = true;

            return true;
        }

        private boolean isReady() {
            return ready;
        }

        private void cancelTimer() {
            timer.onPresent(future -> future.cancel(false));
        }
    }
}
