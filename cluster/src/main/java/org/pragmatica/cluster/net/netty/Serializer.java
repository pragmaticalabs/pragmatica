package org.pragmatica.cluster.net.netty;

import org.pragmatica.cluster.consensus.ProtocolMessage;

public interface Serializer<T extends ProtocolMessage> {
    T decode(byte[] bytes, Class<T> clazz);
    byte[] encode(T object);
}
