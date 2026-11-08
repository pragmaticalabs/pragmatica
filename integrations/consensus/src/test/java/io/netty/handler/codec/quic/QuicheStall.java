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
package io.netty.handler.codec.quic;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/// #1727 (M2) test instrument. Reaches into netty-codec-classes-quic 4.2.18 (package-private
/// QuicheQuicChannel / TimeoutHandler / Quiche) to put a connection into the lost-wake-up state
/// deterministically. Lives in netty's package because the members are package-private; it is tied to
/// the pinned netty version on purpose, and fails loudly (reflection error) when that moves.
public final class QuicheStall {
    private QuicheStall() {}

    /// The state read around the induced stall. Times are quiche's own: nanos until its timer, -1 = no timer.
    public record Reading(long quicheTimerBefore, long quicheTimerAfter, boolean nettyTimerArmedAfter, String sendResult) {}

    /// quiche's current timer in nanos (-1: none), read on the channel's event loop.
    public static long quicheTimerNanos(QuicChannel channel) throws Exception {
        return channel.eventLoop().submit(() -> Quiche.quiche_conn_timeout_as_nanos(connection(channel).address()))
                      .get(5, TimeUnit.SECONDS);
    }

    /// In ONE event-loop task, exactly what netty does when its retransmission timer is late: replace netty's
    /// timer with an already-elapsed one, wait until quiche's own timer is due, then run `connectionSend()` as
    /// netty does after every write/recv. `scheduleTimeout()` then finds the elapsed timer and runs it inline from
    /// inside the send; quiche declares the in-flight packet lost; the nested send is a re-entrant no-op.
    public static Reading induceLateTimer(QuicChannel channel) throws Exception {
        return channel.eventLoop().submit(() -> induce((QuicheQuicChannel) channel)).get(10, TimeUnit.SECONDS);
    }

    /// #2904: closes the race between the test thread and netty's own timer. quiche's loss timer for the dropped
    /// packet is armed for only ~1/8 RTT after the ACK of the next packet (tens of ms); a test thread that polls
    /// the timer and then submits [#induceLateTimer] can lose that race under load, netty's genuine timer then
    /// retransmits and the stall state is never reached. This watcher runs ON the event loop: once `ready` holds
    /// it re-checks every millisecond and induces in the very task that first sees quiche's timer in
    /// `(0, maxNanos)`, so netty's timer cannot fire in between. The future completes with the reading.
    public static CompletableFuture<Reading> induceWhenLossTimerArmed(QuicChannel channel, long maxNanos, BooleanSupplier ready) {
        var result = new CompletableFuture<Reading>();

        channel.eventLoop().execute(() -> watch((QuicheQuicChannel) channel, maxNanos, ready, result));
        return result;
    }

    private static void watch(QuicheQuicChannel channel, long maxNanos, BooleanSupplier ready, CompletableFuture<Reading> result) {
        if (result.isDone()) {
            return;
        }
        try {
            var timer = Quiche.quiche_conn_timeout_as_nanos(connection(channel).address());

            if (ready.getAsBoolean() && timer > 0 && timer < maxNanos) {
                result.complete(induce(channel));
                return;
            }
            channel.eventLoop().schedule(() -> watch(channel, maxNanos, ready, result), 1, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            result.completeExceptionally(e);
        }
    }

    private static Reading induce(QuicheQuicChannel channel) throws Exception {
        var connection = connection(channel);
        var handler = field(QuicheQuicChannel.class, "timeoutHandler").get(channel);
        var timeoutFuture = field(handler.getClass(), "timeoutFuture");
        var existing = (java.util.concurrent.ScheduledFuture<?>) timeoutFuture.get(handler);

        if (existing != null) {
            existing.cancel(false);
        }
        timeoutFuture.set(handler, channel.eventLoop().schedule(() -> {}, 0, TimeUnit.NANOSECONDS));

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var before = Quiche.quiche_conn_timeout_as_nanos(connection.address());

        while (before > 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
            before = Quiche.quiche_conn_timeout_as_nanos(connection.address());
        }

        var send = QuicheQuicChannel.class.getDeclaredMethod("connectionSend", QuicheQuicConnection.class);

        send.setAccessible(true);
        var result = send.invoke(channel, connection);
        var after = Quiche.quiche_conn_timeout_as_nanos(connection.address());
        var armed = timeoutFuture.get(handler) != null;

        return new Reading(before, after, armed, String.valueOf(result));
    }

    private static QuicheQuicConnection connection(QuicChannel channel) throws Exception {
        return (QuicheQuicConnection) field(QuicheQuicChannel.class, "connection").get(channel);
    }

    private static Field field(Class<?> type, String name) throws Exception {
        var field = type.getDeclaredField(name);

        field.setAccessible(true);

        return field;
    }
}
