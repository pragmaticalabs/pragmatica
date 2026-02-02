package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.consensus.state.StateMachineNotification;
import org.pragmatica.lang.Option;

import java.util.function.Consumer;

public sealed interface KVStoreNotification<K extends StructuredKey> extends StateMachineNotification<KVCommand<K>> {
    default boolean matches(StructuredPattern pattern) {
        return cause().key()
                    .matches(pattern);
    }

    record ValuePut<K extends StructuredKey, V>(KVCommand.Put<K, V> cause, Option<V> oldValue) implements KVStoreNotification<K> {}

    record ValueGet<K extends StructuredKey, V>(KVCommand.Get<K> cause,
                                                Option<V> value) implements KVStoreNotification<K> {}

    record ValueRemove<K extends StructuredKey, V>(KVCommand.Remove<K> cause, Option<V> value) implements KVStoreNotification<K> {}

    static <T extends StructuredKey> Consumer<T> filter(Class<T> keyType, Consumer<T> consumer) {
        return key -> {
            if (keyType.isInstance(key)) {
                consumer.accept(keyType.cast(key));
            }
        };
    }

    /**
     * Create a filtered consumer for ValuePut notifications that only processes notifications
     * where the key is an instance of the specified key type.
     *
     * <p>Due to Java type erasure, {@code ValuePut<AetherKey, AetherValue>} and
     * {@code ValuePut<LeaderKey, LeaderValue>} are the same class at runtime.
     * MessageRouter routes by exact class, so all ValuePut notifications go to all ValuePut handlers.
     * This filter ensures handlers only receive notifications for their expected key type.
     *
     * <p>Returns raw Consumer type to match MessageRouter.Entry.route() signature.
     *
     * @param keyType the expected key type class
     * @param consumer the handler to wrap
     * @return a filtered consumer that only invokes the handler for matching key types
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <K extends StructuredKey, V> Consumer<ValuePut> filterPut(Class<K> keyType,
                                                                     Consumer<ValuePut<K, V>> consumer) {
        return notification -> {
            if (keyType.isInstance(notification.cause()
                                               .key())) {
                consumer.accept((ValuePut<K, V>) notification);
            }
        };
    }

    /**
     * Create a filtered consumer for ValueRemove notifications that only processes notifications
     * where the key is an instance of the specified key type.
     *
     * <p>Returns raw Consumer type to match MessageRouter.Entry.route() signature.
     *
     * @param keyType the expected key type class
     * @param consumer the handler to wrap
     * @return a filtered consumer that only invokes the handler for matching key types
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <K extends StructuredKey, V> Consumer<ValueRemove> filterRemove(Class<K> keyType,
                                                                           Consumer<ValueRemove<K, V>> consumer) {
        return notification -> {
            if (keyType.isInstance(notification.cause()
                                               .key())) {
                consumer.accept((ValueRemove<K, V>) notification);
            }
        };
    }

    /**
     * Create a filtered consumer for ValueGet notifications that only processes notifications
     * where the key is an instance of the specified key type.
     *
     * <p>Returns raw Consumer type to match MessageRouter.Entry.route() signature.
     *
     * @param keyType the expected key type class
     * @param consumer the handler to wrap
     * @return a filtered consumer that only invokes the handler for matching key types
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static <K extends StructuredKey, V> Consumer<ValueGet> filterGet(Class<K> keyType,
                                                                     Consumer<ValueGet<K, V>> consumer) {
        return notification -> {
            if (keyType.isInstance(notification.cause()
                                               .key())) {
                consumer.accept((ValueGet<K, V>) notification);
            }
        };
    }
}
