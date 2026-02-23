package org.pragmatica.aether.invoke;

import io.netty.buffer.ByteBuf;
import org.pragmatica.serialization.Serializer;

/// Minimal stub serializer for unit tests.
@SuppressWarnings("JBCT-STY-04")
class StubSerializer implements Serializer {
    @Override
    public <T> void write(ByteBuf byteBuf, T object) {
        byteBuf.writeBytes(new byte[0]);
    }
}
