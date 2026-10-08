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
package org.pragmatica.consensus.net.quic;

import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.quic.QuicStreamChannel;


/// #1727 (M2 mitigation) — the activity kick of ONE [QuicPeerConnection].
///
/// netty-quic can strand lost stream data: when its retransmission timer is already due while the
/// event loop is inside `connectionSend()`, it runs the timer inline, quiche declares the last packet
/// lost, and nothing calls `send()` again until some unrelated event. The only public lever that
/// reaches `connectionSend()` is a non-empty stream write, and a write on ANY stream re-queues the lost
/// frames of EVERY stream (quiche send_single). So after a lane data write the kick keeps writing a
/// tiny frame (the existing KeepAlive, sender-side only, no wire change) on the CONTROL lane every
/// [#intervalMs] until [#windowMs] pass with no data write. Zero cost when idle.
///
/// Owned by the CONNECTION, not by the peer's active connection: a superseded or draining connection
/// gets no regular keepalive, which is exactly where stranded data is lost at close. [#stop] ends it.
/// The real fix is upstream netty (defer the elapsed timer instead of running it inline); remove this
/// when the pinned netty has it.
final class QuicActivityKick {
    static final long DEFAULT_INTERVAL_MS = 100;
    static final long DEFAULT_WINDOW_MS = 5_000;

    private final Supplier<Option<QuicStreamChannel>> controlLane;
    private final ScheduledExecutorService executor;
    private final BooleanSupplier alive;
    private final BooleanSupplier suppressed;
    private final byte[] frame;
    private final long intervalMs;
    private final long windowMs;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicLong lastDataWriteNanos = new AtomicLong();
    private final AtomicLong kicksSent = new AtomicLong();
    private volatile boolean stopped;
    private volatile ScheduledFuture<?> pending;

    QuicActivityKick(Supplier<Option<QuicStreamChannel>> controlLane,
                     ScheduledExecutorService executor,
                     BooleanSupplier alive,
                     BooleanSupplier suppressed,
                     byte[] frame,
                     long intervalMs,
                     long windowMs) {
        this.controlLane = controlLane;
        this.executor = executor;
        this.alive = alive;
        this.suppressed = suppressed;
        this.frame = frame;
        this.intervalMs = intervalMs;
        this.windowMs = windowMs;
    }

    /// A lane data write was accepted: open (or extend) the kick window.
    @Contract
    void noteDataWrite() {
        if (stopped) {
            return;
        }

        lastDataWriteNanos.set(System.nanoTime());
        if (running.compareAndSet(false, true)) {
            schedule();
        }
    }

    /// The connection is closing: no further kick is sent.
    @Contract
    void stop() {
        stopped = true;
        var task = pending;

        if (task != null) {
            task.cancel(false);
        }
    }

    boolean isStopped() {
        return stopped;
    }

    long kicksSent() {
        return kicksSent.get();
    }

    private void schedule() {
        pending = executor.schedule(this::tick, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Contract
    private void tick() {
        if (stopped || !alive.getAsBoolean()) {
            running.set(false);

            return;
        }

        if (windowOpen()) {
            kick();
            schedule();

            return;
        }

        running.set(false);
        // A data write that landed between the window check and the release must not be lost.
        if (windowOpen() && running.compareAndSet(false, true)) {
            schedule();
        }
    }

    private boolean windowOpen() {
        return System.nanoTime() - lastDataWriteNanos.get() <= TimeUnit.MILLISECONDS.toNanos(windowMs);
    }

    @Contract
    private void kick() {
        if (suppressed.getAsBoolean()) {
            return;
        }

        controlLane.get().filter(lane -> lane.isActive() && lane.isWritable()).onPresent(this::send);
    }

    private void send(QuicStreamChannel lane) {
        kicksSent.incrementAndGet();
        var _ = lane.writeAndFlush(Unpooled.wrappedBuffer(frame));
    }
}
