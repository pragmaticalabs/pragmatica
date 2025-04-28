package org.pragmatica.cluster.net.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import org.pragmatica.cluster.consensus.ProtocolMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MessageEncoder<T extends ProtocolMessage> extends MessageToByteEncoder<ProtocolMessage> {
    private static final Logger logger = LoggerFactory.getLogger(MessageEncoder.class);
    private final Serializer<T> serializer;

    public MessageEncoder(Serializer<T> serializer) {
        this.serializer = serializer;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void encode(ChannelHandlerContext ctx, ProtocolMessage msg, ByteBuf out) {
        try {
            byte[] bytes = serializer.encode((T) msg);
            out.writeInt(bytes.length);
            out.writeBytes(bytes);
        } catch (Exception e) {
            logger.error("Error encoding message", e);
            ctx.close();
        }
    }
} 