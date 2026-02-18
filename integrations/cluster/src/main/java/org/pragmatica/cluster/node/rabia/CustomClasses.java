package org.pragmatica.cluster.node.rabia;

import org.pragmatica.consensus.rabia.*;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.serialization.ClassRegistrator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Stream;

import static org.pragmatica.utility.HierarchyScanner.concreteSubtypes;

public interface CustomClasses extends ClassRegistrator {
    CustomClasses INSTANCE = new CustomClasses() {};

    @Override
    default List<Class<?>> classesToRegister() {
        var classes = new ArrayList<Class<?>>();
        classes.addAll(concreteSubtypes(RabiaProtocolMessage.class));
        classes.addAll(concreteSubtypes(NetworkMessage.class));
        classes.addAll(concreteSubtypes(KVCommand.class));
        Stream.of(HashMap.class,
                  RabiaPersistence.SavedState.class,
                  NodeId.class,
                  NodeInfo.class,
                  NodeAddress.class,
                  BatchId.class,
                  CorrelationId.class,
                  Phase.class,
                  Batch.class,
                  StateValue.class,
                  byte[].class)
              .forEach(classes::add);
        classes.add(List.of()
                        .getClass());
        classes.add(List.of(1)
                        .getClass());
        classes.add(List.of(1, 2, 3)
                        .getClass());
        return List.copyOf(classes);
    }
}
