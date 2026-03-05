package org.pragmatica.postgres.io.frontend;

import org.pragmatica.postgres.io.IO;
import org.pragmatica.postgres.message.frontend.SASLInitialResponse;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public class SASLInitialResponseEncoder extends SkipableEncoder<SASLInitialResponse> {

    @Override
    protected byte getMessageId() {
        return 'p';
    }

    @Override
    protected void writeBody(SASLInitialResponse msg, ByteBuffer buffer, Charset encoding) {
        IO.putCString(buffer, msg.saslMechanism(), encoding);
        byte[] clientFirstMessageContent = msg.clientFirstMessage().getBytes(encoding);
        buffer.putInt(clientFirstMessageContent.length);
        buffer.put(clientFirstMessageContent);
    }

    @Override
    public Class<SASLInitialResponse> getMessageType() {
        return SASLInitialResponse.class;
    }
}
