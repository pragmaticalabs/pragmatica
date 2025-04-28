package org.pragmatica.cluster.consensus;

public interface Consensus<T extends ProtocolMessage> {
    void processMessage(T message);
}
