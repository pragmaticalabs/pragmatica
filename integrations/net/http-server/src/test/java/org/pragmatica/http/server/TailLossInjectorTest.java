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

import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Contract;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultEventLoopGroup;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.socket.DatagramPacket;

import static org.assertj.core.api.Assertions.assertThat;

/// #2873: pins on [TailLossInjector]'s two seams that the FIN-loss wire tests reach only by timing. Runs on a real event
/// loop with a recording handler where the socket would be, so no datagram leaves the process.
class TailLossInjectorTest {
    private static final long BOUND_MS = 400;

    /// What the injector reports at the moment the first datagram is flushed to the endpoint.
    private record Seen(int burst, int later, String log) {}

    /// Stands where the socket would be: records what the injector reports when the flush reaches it.
    private static final class Recorder extends ChannelOutboundHandlerAdapter {
        private final TailLossInjector injector;
        private final CopyOnWriteArrayList<Seen> flushes = new CopyOnWriteArrayList<>();

        Recorder(TailLossInjector injector) {
            this.injector = injector;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            promise.setSuccess();
        }

        @Override
        @Contract
        public void flush(ChannelHandlerContext ctx) {
            flushes.add(new Seen(injector.burst(), injector.later(), injector.log()));
        }
    }

    private final TailLossInjector injector = new TailLossInjector();
    private DefaultEventLoopGroup group;
    private LocalChannel channel;
    private Recorder recorder;

    @BeforeEach
    void start() {
        group = new DefaultEventLoopGroup(1);
        recorder = new Recorder(injector);
        channel = new LocalChannel();
        channel.pipeline().addLast(recorder).addLast(injector);
        group.register(channel).syncUninterruptibly();
    }

    @AfterEach
    void stop() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    /// Writes `burst` short-header datagrams, ends the burst, then writes `probes` more, all in one task on the loop.
    private void hold(int burst, int probes) {
        channel.eventLoop().submit(() -> holdOnLoop(burst, probes)).syncUninterruptibly();
    }

    private void holdOnLoop(int burst, int probes) {
        injector.arm();
        for (int i = 0; i < burst; i++) {
            channel.pipeline().write(datagram());
        }
        injector.endBurst();
        for (int i = 0; i < probes; i++) {
            channel.pipeline().write(datagram());
        }
    }

    private static DatagramPacket datagram() {
        var content = Unpooled.buffer(8).writeByte(0x40).writeZero(7);

        return new DatagramPacket(content, new InetSocketAddress("127.0.0.1", 9));
    }

    @Test
    void release_publishesBurstLaterAndLog_beforeTheFlushThatLetsThePeerReact() {
        hold(3, 2);

        injector.release(channel, false);

        assertThat(recorder.flushes).as("one flush").hasSize(1);
        assertThat(recorder.flushes.getFirst().burst()).as("burst known at the flush").isEqualTo(3);
        assertThat(recorder.flushes.getFirst().later()).as("later known at the flush").isEqualTo(2);
        assertThat(recorder.flushes.getFirst().log()).as("log known at the flush").isNotEmpty();
    }

    @Test
    void awaitProbes_waitsForTheRequestedCount_notForOne() {
        hold(2, 1);
        var started = System.nanoTime();

        injector.awaitProbes(channel, 2, BOUND_MS);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("two probes were never held: the wait ran to its bound")
                                                                              .isGreaterThanOrEqualTo(BOUND_MS - 50);
    }

    @Test
    void awaitProbes_returnsAtOnceWhenTheRequestedCountIsHeld() {
        hold(2, 2);
        var started = System.nanoTime();

        injector.awaitProbes(channel, 2, BOUND_MS * 10);

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).as("the count was held: no wait to the bound")
                                                                              .isLessThan(BOUND_MS * 5);
    }
}
