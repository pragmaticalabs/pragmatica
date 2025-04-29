package org.pragmatica.cluster.state.kvstore;

import org.pragmatica.cluster.net.netty.Serializer;
import org.pragmatica.cluster.state.Notification;
import org.pragmatica.cluster.state.StateMachine;
import org.pragmatica.cluster.state.kvstore.KVCommand.Get;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.cluster.state.kvstore.KVNotification.ValueGet;
import org.pragmatica.cluster.state.kvstore.KVNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class KVStore<K, V> implements StateMachine<KVCommand> {
    private final Map<K, V> storage = new ConcurrentHashMap<>();
    private final Serializer serializer;
    private Consumer<? super Notification> observer = _ -> {};

    public KVStore(Serializer serializer) {
        this.serializer = serializer;
    }

    @SuppressWarnings("unchecked")
    @Override
    public void process(KVCommand command) {
        switch (command) {
            case Get<?> get -> handleGet((Get<K>) get);
            case Put<?, ?> put -> handlePut((Put<K, V>) put);
            case Remove<?> remove -> handleRemove((Remove<K>) remove);
        }
    }

    private void handleGet(Get<K> get) {
        var value = storage.get(get.key());
        observer.accept(new ValueGet<>(get, Option.option(value)));
    }

    private void handlePut(Put<K, V> put) {
        var oldValue = storage.put(put.key(), put.value());
        observer.accept(new ValuePut<>(put, Option.option(oldValue)));
    }

    private void handleRemove(Remove<K> remove) {
        var oldValue = storage.remove(remove.key());
        observer.accept(new ValueRemove<>(remove, Option.option(oldValue)));
    }

    @Override
    public Result<byte[]> makeSnapshot() {
        return Result.lift(Causes::fromThrowable,
                           () -> serializer.encode(new HashMap<>(storage)));
    }

    @SuppressWarnings("unchecked")
    @Override
    public Result<Unit> restoreSnapshot(byte[] snapshot) {
        return Result.lift(Causes::fromThrowable, () -> serializer.decode(snapshot, HashMap.class))
                     .map(map -> (Map<K, V>) map)
                     .onSuccessRun(storage::clear)
                     .onSuccess(storage::putAll)
                     .map(_ -> Unit.unit());
    }

    @Override
    public void observeStateChanges(Consumer<? super Notification> observer) {
        this.observer = observer;
    }

    public Map<K, V> snapshot() {
        return new HashMap<>(storage);
    }
}
