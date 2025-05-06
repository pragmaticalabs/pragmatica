package org.pragmatica.cluster.consensus.rabia.infrastructure;

import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.state.Notification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

public record StateChangePrinter(NodeId id) implements Consumer<Notification> {
    private static final Logger logger = LoggerFactory.getLogger(StateChangePrinter.class);

    @Override
    public void accept(Notification notification) {
        logger.info("Node {} received state change: {}", id, notification);
    }
}
