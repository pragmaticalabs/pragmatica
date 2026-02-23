package org.pragmatica.aether.invoke;

import io.netty.buffer.ByteBuf;
import org.pragmatica.serialization.Deserializer;

/// Minimal stub deserializer for unit tests.
@SuppressWarnings("JBCT-STY-04")
class StubDeserializer implements Deserializer {
    @Override
    @SuppressWarnings("unchecked")
    public <T> T read(ByteBuf byteBuf) {
        return (T) new Object();
    }
}
