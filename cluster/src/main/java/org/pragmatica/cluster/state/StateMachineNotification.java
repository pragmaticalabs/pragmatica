package org.pragmatica.cluster.state;

import org.pragmatica.messaging.Message;

/// State machine notifications root.
public interface StateMachineNotification<T> extends Message.Local {
    T cause();
}
