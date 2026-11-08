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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;


/// #2746: the loss pattern quiche does not recover from, on the outbound datagrams of one endpoint.
///
/// Installed first on an endpoint's UDP channel. Once armed it holds every outbound 1-RTT (short-header) datagram. The
/// datagrams written within [#BURST_NANOS] of the first held one are the BURST: the tail of one stream, written in one
/// go. The later ones are what the endpoint sends while nothing reaches the peer: its PTO probes, ack-eliciting packets
/// with higher numbers. [#release] then drops every burst datagram but the second-to-last and sends the rest, so the
/// peer acknowledges the probes and the endpoint detects, in ONE batch, the loss of the stream's earlier data and of the
/// burst's last datagram. When the stream ended with a FIN-only frame, that frame is the burst's last datagram: quiche
/// retransmits the lost data but never the `FIN`, so the peer holds every byte and waits until the idle timeout.
/// When the `FIN` rides on the last data, the lost datagram carries data and `FIN`, which quiche retransmits.
///
/// `drop = false` is the control: everything held is sent, in order.
final class TailLossInjector extends ChannelOutboundHandlerAdapter {
    static final long BURST_NANOS = TimeUnit.MILLISECONDS.toNanos(10);

    private record Held(ChannelHandlerContext ctx, DatagramPacket packet, ChannelPromise promise, long at) {}

    private final List<Held> held = new ArrayList<>();
    private volatile boolean armed;
    private volatile int burst;
    private volatile int later;
    private volatile String log = "";

    void arm() {
        armed = true;
    }

    /// Datagrams of the burst, and datagrams sent after it, at the last [#release].
    int burst() {
        return burst;
    }

    int later() {
        return later;
    }

    String log() {
        return log;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (armed && msg instanceof DatagramPacket packet && isShortHeader(packet)) {
            held.add(new Held(ctx, packet, promise, System.nanoTime()));
        } else {
            super.write(ctx, msg, promise);
        }
    }

    private static boolean isShortHeader(DatagramPacket packet) {
        var content = packet.content();

        return content.isReadable() && (content.getByte(content.readerIndex()) & 0x80) == 0;
    }

    /// Stops holding and drops or sends what was held, on the channel's event loop; returns once done.
    void release(Channel channel, boolean drop) {
        channel.eventLoop().submit(() -> releaseHeld(drop)).syncUninterruptibly();
    }

    private void releaseHeld(boolean drop) {
        armed = false;
        if (held.isEmpty()) {
            log = "nothing held";

            return;
        }

        var first = held.getFirst().at();
        var burstSize = (int) held.stream().filter(h -> h.at() - first <= BURST_NANOS).count();
        var keep = burstSize - 2;
        var text = new StringBuilder();

        for (int i = 0; i < held.size(); i++) {
            var h = held.get(i);
            var dropIt = drop && i < burstSize && i != keep;

            text.append(i < burstSize
                        ? 'b'
                        : 'l')
                .append(dropIt
                        ? 'x'
                        : '+')
                .append(h.packet().content().readableBytes())
                .append(' ');
            if (dropIt) {
                ReferenceCountUtil.release(h.packet());
                h.promise().setSuccess();
            } else {
                h.ctx().write(h.packet(), h.promise());
            }
        }

        held.getFirst().ctx().flush();
        burst = burstSize;
        later = held.size() - burstSize;
        log = text.toString().trim();
        held.clear();
    }
}
