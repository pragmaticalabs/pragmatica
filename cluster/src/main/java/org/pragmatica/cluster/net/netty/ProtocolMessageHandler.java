package org.pragmatica.cluster.net.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import org.pragmatica.cluster.consensus.ProtocolMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public class ProtocolMessageHandler<T extends ProtocolMessage> extends SimpleChannelInboundHandler<ProtocolMessage> {
    private static final Logger logger = LoggerFactory.getLogger(ProtocolMessageHandler.class);
    private final Consumer<Channel> peerConnected;
    private final Consumer<Channel> peerDisconnected;
    private final Consumer<T> messageHandler;

    public ProtocolMessageHandler(Consumer<Channel> peerConnected, Consumer<Channel> peerDisconnected, Consumer<T> messageHandler) {
        this.peerConnected = peerConnected;
        this.peerDisconnected = peerDisconnected;
        this.messageHandler = messageHandler;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ProtocolMessage msg) {
        logger.debug("Received message: {}", msg);
        
        try {
            messageHandler.accept((T) msg);
        } catch (Exception e) {
            logger.error("Error handling message: {}", msg, e);
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        logger.info("New channel active: {}", ctx.channel().remoteAddress());

        peerConnected.accept(ctx.channel());
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        logger.info("Channel deactivated: {}", ctx.channel().remoteAddress());

        peerDisconnected.accept(ctx.channel());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Error in channel", cause);
        ctx.close();
    }
}