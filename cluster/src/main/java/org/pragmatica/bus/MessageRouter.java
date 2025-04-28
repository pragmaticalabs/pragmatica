package org.pragmatica.bus;

import org.pragmatica.lang.Functions.Fn1;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/// **NOTE**: This implementation assumes that instance is configured and then used without
/// changes.
public interface MessageRouter<R, T> {
    R route(T message);

    MessageRouter<R, T> addRoute(Class<? extends T> messageType, Fn1<? extends R, ? super T> receiver);

    void validate(List<Class<? extends T>> messageTypes);

    static <R, T> MessageRouter<R, T> create() {
        record messageRouter<R, T>(Map<Class<? extends T>, Fn1<? extends R, ? super T>> routingTable) implements
                MessageRouter<R, T> {

            @Override
            public R route(T message) {
                return routingTable.get(message.getClass()).apply(message);
            }

            @Override
            public MessageRouter<R, T> addRoute(Class<? extends T> messageType, Fn1<? extends R, ? super T> receiver) {
                if (routingTable.containsKey(messageType)) {
                    throw new IllegalArgumentException("Message type " + messageType.getSimpleName() + " already registered");
                }

                routingTable.put(messageType, receiver);

                return this;
            }

            @Override
            public void validate(List<Class<? extends T>> messageTypes) {
                var missing = messageTypes.stream()
                                          .filter(messageType -> !routingTable.containsKey(messageType))
                                          .map(Class::getSimpleName)
                                          .toList();

                if (!missing.isEmpty()) {
                    throw new IllegalArgumentException("Missing message types: " + String.join(", ", missing));
                }
            }
        }

        return new messageRouter<>(new HashMap<>());
    }
}
