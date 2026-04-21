package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.consensus.StateMachine;
import org.pragmatica.cluster.state.kvstore.KVCommand.Get;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.cluster.state.kvstore.KVStoreLocalIO.Request.Find;
import org.pragmatica.cluster.state.kvstore.KVStoreLocalIO.Response.FoundEntries;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueGet;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageReceiver;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public class KVStore<K extends StructuredKey, V> implements StateMachine<KVCommand<K>> {
    private final Map<K, V> storage = new ConcurrentHashMap<>();
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final MessageRouter router;

    public KVStore(MessageRouter router, Serializer serializer, Deserializer deserializer) {
        this.router = router;
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public Option<V> process(KVCommand command) {
        return switch (command) {
            case Get<?> get -> handleGet((Get<K>) get);
            case Put<?, ?> put -> handlePut((Put<K, V>) put);
            case Remove<?> remove -> handleRemove((Remove<K>) remove);
        };
    }

    private Option<V> handleGet(Get<K> get) {
        var value = Option.option(storage.get(get.key()));
        router.route(new ValueGet<>(get, value));
        return value;
    }

    private Option<V> handlePut(Put<K, V> put) {
        // Why: LeaderKey is ephemeral runtime state — who the current leader is depends on
        // live Rabia term + peer reachability. Persisting it to the storage map means it
        // survives across full-cluster restart, which re-delivers a stale leader to nodes
        // whose cached state is from a prior cluster lifetime. Route the notification so
        // LeaderManager subscribers still see the commit, but skip the storage mutation.
        if (put.key() instanceof LeaderKey) {
            router.route(new ValuePut<>(put, Option.none()));
            return Option.none();
        }
        var oldValue = Option.option(storage.put(put.key(), put.value()));
        router.route(new ValuePut<>(put, oldValue));
        return oldValue;
    }

    private Option<V> handleRemove(Remove<K> remove) {
        var oldValue = Option.option(storage.remove(remove.key()));
        router.route(new ValueRemove<>(remove, oldValue));
        return oldValue;
    }

    @Override
    public Result<byte[]> makeSnapshot() {
        return Result.lift(Causes::fromThrowable, () -> serializer.encode(new HashMap<>(storage)));
    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<Unit> restoreSnapshot(byte[] snapshot) {
        return Result.lift(Causes::fromThrowable,
                           () -> deserializer.decode(snapshot))
                     .map(map -> (Map<K, V>) map)
                     .map(KVStore::stripEphemeralLeaderKey)
                     .onSuccessRun(this::notifyRemoveAll)
                     .onSuccessRun(storage::clear)
                     .onSuccess(storage::putAll)
                     .onSuccessRun(this::notifyPutAll)
                     .mapToUnit();
    }

    // Why: legacy snapshots may have persisted LeaderKey/LeaderValue. Since leader identity
    // is now ephemeral, strip any persisted leader entries from a restored snapshot so the
    // restarted node does not adopt a stale leader from a prior cluster lifetime.
    private static <K extends StructuredKey, V> Map<K, V> stripEphemeralLeaderKey(Map<K, V> restored) {
        restored.keySet().removeIf(key -> key instanceof LeaderKey);
        return restored;
    }

    private void notifyRemoveAll() {
        storage.forEach((key, value) -> router.route(new ValueRemove<>(new Remove<>(key), Option.some(value))));
    }

    private void notifyPutAll() {
        storage.forEach((key, value) -> router.route(new ValuePut<>(new Put<>(key, value), Option.none())));
    }

    @Override
    public Unit reset() {
        notifyRemoveAll();
        storage.clear();
        return Unit.unit();
    }

    public Map<K, V> snapshot() {
        return Map.copyOf(storage);
    }

    public Option<V> get(K key) {
        return Option.option(storage.get(key));
    }

    /// Iterates over entries matching the specified key and value types.
    /// This avoids ClassCastException when the store contains mixed key types (e.g., AetherKey and LeaderKey).
    ///
    /// @param keyClass   the expected key class
    /// @param valueClass the expected value class
    /// @param consumer   the action to perform on each matching entry
    /// @param <KK>       the key type
    /// @param <VV>       the value type
    @SuppressWarnings("unchecked")
    public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
        storage.forEach((key, value) -> {
                            if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                                consumer.accept((KK) key, (VV) value);
                            }
                        });
    }

    @MessageReceiver
    public void find(Find find) {
        router.routeAsync(() -> new FoundEntries<>(List.copyOf(storage.entrySet())));
    }
}
