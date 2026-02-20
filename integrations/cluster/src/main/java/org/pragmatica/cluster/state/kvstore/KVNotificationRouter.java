package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/// Sub-router for KV-Store notifications that dispatches by key type.
/// Replaces per-handler filterPut/filterRemove boilerplate with type-safe key-based dispatch.
public final class KVNotificationRouter<K extends StructuredKey, V> {
    private final Map<Class<? extends K>, List<Consumer<KVStoreNotification.ValuePut<?, ?>>>> putHandlers;
    private final Map<Class<? extends K>, List<Consumer<KVStoreNotification.ValueRemove<?, ?>>>> removeHandlers;
    private final Class<K> keyTypeFilter;

    private KVNotificationRouter(Class<K> keyTypeFilter,
                                 Map<Class<? extends K>, List<Consumer<KVStoreNotification.ValuePut<?, ?>>>> putHandlers,
                                 Map<Class<? extends K>, List<Consumer<KVStoreNotification.ValueRemove<?, ?>>>> removeHandlers) {
        this.keyTypeFilter = keyTypeFilter;
        this.putHandlers = Map.copyOf(putHandlers);
        this.removeHandlers = Map.copyOf(removeHandlers);
    }

    @SuppressWarnings("JBCT-RET-01")
    void handlePut(KVStoreNotification.ValuePut<?, ?> notification) {
        var key = notification.cause().key();

        if (!keyTypeFilter.isInstance(key)) {
            return;
        }

        Option.option(putHandlers.get(key.getClass()))
              .onPresent(handlers -> handlers.forEach(h -> h.accept(notification)));
    }

    @SuppressWarnings("JBCT-RET-01")
    void handleRemove(KVStoreNotification.ValueRemove<?, ?> notification) {
        var key = notification.cause().key();

        if (!keyTypeFilter.isInstance(key)) {
            return;
        }

        Option.option(removeHandlers.get(key.getClass()))
              .onPresent(handlers -> handlers.forEach(h -> h.accept(notification)));
    }

    /// Produce MessageRouter.Entry list for main router registration.
    public List<MessageRouter.Entry<?>> asRouteEntries() {
        return List.of(
            MessageRouter.Entry.route(KVStoreNotification.ValuePut.class, this::handlePut),
            MessageRouter.Entry.route(KVStoreNotification.ValueRemove.class, this::handleRemove)
        );
    }

    public static <K extends StructuredKey, V> Builder<K, V> builder(Class<K> keyTypeFilter) {
        return new Builder<>(keyTypeFilter);
    }

    public static final class Builder<K extends StructuredKey, V> {
        private final Class<K> keyTypeFilter;
        private final Map<Class<? extends K>, List<Consumer<KVStoreNotification.ValuePut<?, ?>>>> putHandlers = new LinkedHashMap<>();
        private final Map<Class<? extends K>, List<Consumer<KVStoreNotification.ValueRemove<?, ?>>>> removeHandlers = new LinkedHashMap<>();

        private Builder(Class<K> keyTypeFilter) {
            this.keyTypeFilter = keyTypeFilter;
        }

        @SuppressWarnings("unchecked")
        public <KK extends K, VV extends V> Builder<K, V> onPut(Class<KK> keyType, Consumer<KVStoreNotification.ValuePut<KK, VV>> handler) {
            putHandlers.computeIfAbsent(keyType, _ -> new ArrayList<>())
                       .add((Consumer<KVStoreNotification.ValuePut<?, ?>>) (Consumer<?>) handler);
            return this;
        }

        @SuppressWarnings("unchecked")
        public <KK extends K, VV extends V> Builder<K, V> onRemove(Class<KK> keyType, Consumer<KVStoreNotification.ValueRemove<KK, VV>> handler) {
            removeHandlers.computeIfAbsent(keyType, _ -> new ArrayList<>())
                          .add((Consumer<KVStoreNotification.ValueRemove<?, ?>>) (Consumer<?>) handler);
            return this;
        }

        public KVNotificationRouter<K, V> build() {
            var putCopy = new LinkedHashMap<Class<? extends K>, List<Consumer<KVStoreNotification.ValuePut<?, ?>>>>();
            putHandlers.forEach((k, v) -> putCopy.put(k, List.copyOf(v)));

            var removeCopy = new LinkedHashMap<Class<? extends K>, List<Consumer<KVStoreNotification.ValueRemove<?, ?>>>>();
            removeHandlers.forEach((k, v) -> removeCopy.put(k, List.copyOf(v)));

            return new KVNotificationRouter<>(keyTypeFilter, putCopy, removeCopy);
        }
    }
}
