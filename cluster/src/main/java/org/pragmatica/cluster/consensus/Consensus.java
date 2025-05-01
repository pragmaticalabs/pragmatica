package org.pragmatica.cluster.consensus;

import org.pragmatica.cluster.state.Command;

import java.util.List;

public interface Consensus<T extends ProtocolMessage, C extends Command> {
    void processMessage(T message);

    boolean submitCommands(List<C> commands);
}
