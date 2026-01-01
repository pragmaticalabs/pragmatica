package org.pragmatica.aether.api;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket handler for dashboard real-time updates.
 *
 * <p>Manages connected dashboard clients and broadcasts metrics updates.
 */
public class DashboardWebSocketHandler extends SimpleChannelInboundHandler<WebSocketFrame> {
    private static final Logger log = LoggerFactory.getLogger(DashboardWebSocketHandler.class);
    private static final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);

    private final DashboardMetricsPublisher metricsPublisher;

    public DashboardWebSocketHandler(DashboardMetricsPublisher metricsPublisher) {
        this.metricsPublisher = metricsPublisher;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        channels.add(ctx.channel());
        log.info("Dashboard client connected: {}",
                 ctx.channel()
                    .remoteAddress());
        // Send initial state snapshot
        var initialState = metricsPublisher.buildInitialState();
        ctx.writeAndFlush(new TextWebSocketFrame(initialState));
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        channels.remove(ctx.channel());
        log.info("Dashboard client disconnected: {}",
                 ctx.channel()
                    .remoteAddress());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof TextWebSocketFrame textFrame) {
            handleClientMessage(ctx, textFrame.text());
        }
    }

    private void handleClientMessage(ChannelHandlerContext ctx, String message) {
        log.debug("Received from dashboard client: {}", message);
        // Parse client messages (subscribe, set threshold, get history)
        if (message.contains("\"type\":\"SUBSCRIBE\"")) {
            // Client subscribing to streams - currently all clients get all updates
            log.debug("Client subscribed to streams");
        }else if (message.contains("\"type\":\"SET_THRESHOLD\"")) {
            metricsPublisher.handleSetThreshold(message);
        }else if (message.contains("\"type\":\"GET_HISTORY\"")) {
            var history = metricsPublisher.buildHistoryResponse(message);
            ctx.writeAndFlush(new TextWebSocketFrame(history));
        }
    }

    /**
     * Broadcast a message to all connected dashboard clients.
     */
    public static void broadcast(String message) {
        channels.writeAndFlush(new TextWebSocketFrame(message));
    }

    /**
     * Get the number of connected dashboard clients.
     */
    public static int connectedClients() {
        return channels.size();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("WebSocket error for client {}",
                  ctx.channel()
                     .remoteAddress(),
                  cause);
        ctx.close();
    }
}
