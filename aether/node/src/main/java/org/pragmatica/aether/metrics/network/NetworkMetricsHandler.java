package org.pragmatica.aether.metrics.network;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;

/**
 * Sharable Netty handler that tracks network I/O metrics.
 * <p>
 * Thread-safe: uses atomic operations for all counters.
 * <p>
 * Add to pipeline after codec handlers to track application-level messages,
 * or before codec handlers to track raw bytes.
 */
@Sharable
public final class NetworkMetricsHandler extends ChannelDuplexHandler {
    private final LongAdder bytesRead = new LongAdder();
    private final LongAdder bytesWritten = new LongAdder();
    private final LongAdder messagesRead = new LongAdder();
    private final LongAdder messagesWritten = new LongAdder();
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicInteger backpressureEvents = new AtomicInteger(0);
    private final AtomicLong lastBackpressureTimestamp = new AtomicLong(0);

    private NetworkMetricsHandler() {}

    public static NetworkMetricsHandler networkMetricsHandler() {
        return new NetworkMetricsHandler();
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        activeConnections.incrementAndGet();
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        activeConnections.decrementAndGet();
        super.channelInactive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        messagesRead.increment();
        if (msg instanceof ByteBuf buf) {
            bytesRead.add(buf.readableBytes());
        }
        super.channelRead(ctx, msg);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        messagesWritten.increment();
        if (msg instanceof ByteBuf buf) {
            bytesWritten.add(buf.readableBytes());
        }
        super.write(ctx, msg, promise);
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (!ctx.channel()
                .isWritable()) {
            // Channel became non-writable = backpressure
            backpressureEvents.incrementAndGet();
            lastBackpressureTimestamp.set(System.currentTimeMillis());
        }
        super.channelWritabilityChanged(ctx);
    }

    /**
     * Take a snapshot of current metrics.
     */
    public NetworkMetrics snapshot() {
        return new NetworkMetrics(bytesRead.sum(),
                                  bytesWritten.sum(),
                                  messagesRead.sum(),
                                  messagesWritten.sum(),
                                  activeConnections.get(),
                                  backpressureEvents.get(),
                                  lastBackpressureTimestamp.get());
    }

    /**
     * Take a snapshot and reset counters (for delta-based reporting).
     */
    public NetworkMetrics snapshotAndReset() {
        return new NetworkMetrics(bytesRead.sumThenReset(),
                                  bytesWritten.sumThenReset(),
                                  messagesRead.sumThenReset(),
                                  messagesWritten.sumThenReset(),
                                  activeConnections.get(),
                                  backpressureEvents.getAndSet(0),
                                  lastBackpressureTimestamp.get());
    }
}
