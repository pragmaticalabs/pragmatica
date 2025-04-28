package org.pragmatica.cluster.net.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import org.pragmatica.cluster.consensus.ProtocolMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class MessageDecoder<T extends ProtocolMessage> extends ByteToMessageDecoder {
    private static final Logger logger = LoggerFactory.getLogger(MessageDecoder.class);

    private final Serializer<T> serializer;

    public MessageDecoder(Serializer<T> serializer) {
        this.serializer = serializer;
    }

    @SuppressWarnings("unchecked")
    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.readableBytes() < 4) {
            return;
        }

        in.markReaderIndex();
        int length = in.readInt();

        if (in.readableBytes() < length) {
            in.resetReaderIndex();
            return;
        }

        try {
            var bytes = new byte[length];
            in.readBytes(bytes);
            out.add(serializer.decode(bytes, (Class<T>) ProtocolMessage.class));
        } catch (Exception e) {
            logger.error("Error decoding message", e);
            ctx.close();
        }
    }
} 