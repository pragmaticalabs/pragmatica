package org.pragmatica.cluster.consensus;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

public interface StateMachine<T extends StateMachineCommand> extends ProtocolMessage {
    void process(T command);

    Result<byte[]> makeSnapshot();

    Result<Unit> restoreSnapshot();
}
