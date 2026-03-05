package org.pragmatica.postgres.io.frontend;

import org.pragmatica.postgres.io.Encoder;
import org.pragmatica.postgres.message.frontend.SSLRequest;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/**
 * @author Marat Gainullin
 */
public class SSLRequestEncoder implements Encoder<SSLRequest> {

    @Override
    public Class<SSLRequest> getMessageType() {
        return SSLRequest.class;
    }

    @Override
    public void write(SSLRequest msg, ByteBuffer buffer, Charset encoding) {
        buffer.putInt(8);
        buffer.putInt(80877103);
    }
}
