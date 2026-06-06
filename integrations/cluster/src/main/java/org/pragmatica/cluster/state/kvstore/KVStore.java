package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
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
    /// Per-thread flag, true while this store is re-applying a restored/resync snapshot — see
    /// [#isReplaying()]. Thread-scoped so a concurrent live apply on another thread reads false.
    private final ThreadLocal<Boolean> replaying = ThreadLocal.withInitial(() -> Boolean.FALSE);

    public KVStore(MessageRouter router, Serializer serializer, Deserializer deserializer) {
        this.router = router;
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Override
    public <R> List<R> process(Batch<KVCommand<K>> batch) {
        return batch.commands()
                    .stream()
                    .map(command -> (R) processCommand(command))
                    .toList();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Option<V> processCommand(KVCommand command) {
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

    /// The serializer used for snapshot serialization. [StateMachine#createBatch] reuses it to
    /// derive a deterministic content-based batch id.
    @Override
    public Serializer serializer() {
        return serializer;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<Unit> restoreSnapshot(byte[] snapshot) {
        return Result.lift(Causes::fromThrowable,
                           () -> deserializer.decode(snapshot))
                     .map(map -> (Map<K, V>) map)
                     .onSuccess(this::replaceAllDuringReplay)
                     .mapToUnit();
    }

    /// True while THIS thread is re-applying a restored/resync snapshot (the [#restoreSnapshot]
    /// notify fan-out). Both fresh-node restore and already-ACTIVE catch-up resync funnel through
    /// `restoreSnapshot`, so one flag here covers both. Router subscribers invoked synchronously
    /// during the replay fan-out can query this to suppress side-effects (e.g. re-emitting historical
    /// events) that must fire only on live application. Thread-scoped: a concurrent live apply on
    /// another thread reads false.
    public boolean isReplaying() {
        return replaying.get();
    }

    /// Re-applies a restored snapshot with the replay flag raised so router subscribers can tell
    /// replayed application from live. try/finally guarantees the flag is lowered even if a subscriber
    /// throws (paired with the consensus apply-executor exception containment).
    private void replaceAllDuringReplay(Map<K, V> restored) {
        replaying.set(Boolean.TRUE);
        try {
            notifyRemoveAll();
            storage.clear();
            storage.putAll(restored);
            notifyPutAll();
        } finally {
            replaying.set(Boolean.FALSE);
        }
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

    /// Typed lookup that tolerates the runtime mixed-storage model (e.g., a `KVStore<AetherKey,
    /// AetherValue>` also stores `LeaderKey`/`LeaderValue` entries via the unchecked-cast path in
    /// `process`). Used by callers that need to read foreign-typed atoms (e.g., the leader-election
    /// FSM pulls `LeaderKey`/`LeaderValue` even though its kvStore is parameterized for AetherKey).
    /// Returns `Option.none()` when the key is absent OR the stored value isn't an instance of
    /// `valueClass`.
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <VV> Option<VV> getTyped(StructuredKey key, Class<VV> valueClass) {
        var raw = ((Map) storage).get(key);
        if (raw == null || !valueClass.isInstance(raw)) {
            return Option.none();
        }
        return Option.some((VV) raw);
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
