package org.pragmatica.consensus.rabia.infrastructure;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.state.StateMachineNotification;
import org.pragmatica.messaging.MessageReceiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

@SuppressWarnings("rawtypes")
public record StateChangePrinter(NodeId id) implements Consumer<StateMachineNotification> {
    private static final Logger logger = LoggerFactory.getLogger(StateChangePrinter.class);

    @MessageReceiver
    @Override
    public void accept(StateMachineNotification stateMachineNotification) {
        logger.trace("Node {} received state change: {}", id, stateMachineNotification);
    }
}
