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
import org.pragmatica.lang.Contract;
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
    /// The view that was last DELIVERED to subscribers via [#replayNotifications()]. Used to
    /// compute the DIFF-replay on a mid-life snapshot install (cluster-topology-overhaul §5.8,
    /// AMENDED 2026-06-11): only keys that are new/changed/vanished relative to this view emit a
    /// notification. Empty until the first replay (cold boot fires one put per restored key).
    private final Map<K, V> lastReplayedView = new ConcurrentHashMap<>();
    /// Per-thread flag, true only while [#replayNotifications()] is firing the synthetic
    /// notification burst on THIS thread. Subscribers invoked synchronously during the burst can
    /// query [#isReplaying()] to distinguish a replayed (historical-as-of-sync) notification from
    /// a live apply — e.g. the cluster-event aggregator suppresses re-publishing historical events
    /// during replay. Thread-scoped: a concurrent live apply on another thread reads false.
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
        if (staleWrite(put)) {
            return Option.option(storage.get(put.key()));
        }
        var oldValue = Option.option(storage.put(put.key(), put.value()));
        router.route(new ValuePut<>(put, oldValue));
        return oldValue;
    }

    /// The committed-state fence: a `Put` is rejected (applied to nothing, NO `ValuePut` emitted)
    /// when it is either a stale `LeaderKey` write (H4 leader fence) or a stale-epoch write to any
    /// [EpochBearing] value (ownership fence, #345 piece 1a). Both arms are pure functions of the
    /// committed storage content and the command itself, so every replica decides identically inside
    /// the consensus applier. Snapshot restore ([#restoreSnapshot]) intentionally bypasses both
    /// fences: a restored snapshot is the authoritative committed state, not a competing write.
    private boolean staleWrite(Put<K, V> put) {
        return staleLeaderWrite(put) || staleEpochWrite(put);
    }

    /// H4 leader fence (cluster-topology-overhaul §Wave 8.2): `LeaderKey` writes are
    /// compare-and-put — applied only when the incoming [LeaderValue#viewSequence()] is strictly
    /// greater than the stored one. The check is deterministic (it depends only on the replicated
    /// storage content and the command itself), so every replica accepts or rejects identically
    /// inside the consensus applier. A rejected write mutates nothing and emits NO `ValuePut`
    /// notification — a stale leader must never be observed by the election FSMs.
    private boolean staleLeaderWrite(Put<K, V> put) {
        return put.key() instanceof LeaderKey
               && put.value() instanceof LeaderValue incoming
               && storage.get(put.key()) instanceof LeaderValue stored
               && incoming.viewSequence() <= stored.viewSequence();
    }

    /// Ownership/governance epoch fence (#345 piece 1a): generalizes the leader fence to ANY
    /// [EpochBearing] value (governor announcement, DHT partition ownership). When BOTH the incoming
    /// and the currently-committed value under the key are `EpochBearing`, the write is rejected iff
    /// its epoch is STRICTLY older than the committed one — a deposed governor/owner cannot commit an
    /// OLD epoch over a newer one. A first write (no committed value) and any non-`EpochBearing`
    /// value pass through unchanged. Equal-or-newer epochs are accepted: governor reannouncement and
    /// dissolution legitimately re-write at the same epoch, and a stale-owner takeover rewrites
    /// ownership at the same epoch while bumping only its `ownershipTerm` — see [EpochBearing].
    private boolean staleEpochWrite(Put<K, V> put) {
        return put.value() instanceof EpochBearing<?> incoming
               && storage.get(put.key()) instanceof EpochBearing<?> stored
               && incomingEpochIsStale(incoming, stored);
    }

    /// Captures the recursive epoch type variable so the [Comparable#compareTo] is fully type-checked
    /// (never reflective). The cast of the stored epoch is safe: a single key only ever holds one
    /// `EpochBearing` value type (governor or ownership), so its epoch type matches the incoming one.
    @SuppressWarnings("unchecked")
    private static <E extends Comparable<E>> boolean incomingEpochIsStale(EpochBearing<E> incoming,
                                                                          EpochBearing<?> stored) {
        return incoming.fenceEpoch().compareTo((E) stored.fenceEpoch()) < 0;
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

    /// SILENT snapshot install (cluster-topology-overhaul §5.8, AMENDED 2026-06-11): replaces
    /// storage with the synced state and fires NO notifications. The deferred notification burst
    /// is delivered by [#replayNotifications()] once the engine is ACTIVE, so consumers never see
    /// a KV notification before the engine is operational.
    @SuppressWarnings("unchecked")
    @Override
    public Result<Unit> restoreSnapshot(byte[] snapshot) {
        return Result.lift(Causes::fromThrowable,
                           () -> deserializer.decode(snapshot))
                     .map(map -> (Map<K, V>) map)
                     .onSuccess(this::installSilently)
                     .mapToUnit();
    }

    /// Silent install: replace storage with the restored map, NO notifications. The
    /// notification consequences are computed later by [#replayNotifications()] as a diff against
    /// [#lastReplayedView].
    private void installSilently(Map<K, V> restored) {
        storage.clear();
        storage.putAll(restored);
    }

    /// Notification replay — the `sync → activate → replay` step (cluster-topology-overhaul §5.8,
    /// AMENDED 2026-06-11). Synthesises the notification burst for the current storage as a DIFF
    /// against the last-replayed view: a synthetic `ValuePut` per new/changed key and a synthetic
    /// `ValueRemove` per vanished key. The first replay after a cold boot diffs against an empty
    /// view, so it fires one put per restored key (full replay); a mid-life install on an
    /// already-ACTIVE lagging node fires only the delta. MUTATION-FREE: it reads `storage` and
    /// routes notifications, never touching `storage` itself (the H4 `LeaderKey` fence in
    /// [#handlePut] is never exercised — replay does not go through the apply path). The
    /// last-replayed view is advanced to the current storage afterwards so the next install
    /// diffs correctly.
    @Override
    public Unit replayNotifications() {
        replaying.set(Boolean.TRUE);
        try {
            replayRemovedKeys();
            replayPutKeys();
        } finally {
            replaying.set(Boolean.FALSE);
        }
        lastReplayedView.clear();
        lastReplayedView.putAll(storage);
        return Unit.unit();
    }

    /// True while THIS thread is firing the [#replayNotifications()] synthetic burst. Subscribers
    /// invoked synchronously during the burst use this to suppress side-effects that must fire
    /// only on a live apply (e.g. re-publishing historical cluster events). Thread-scoped: a
    /// concurrent live apply on another thread reads false.
    public boolean isReplaying() {
        return replaying.get();
    }

    /// Fire a synthetic `ValueRemove` for every key present in the last-replayed view but absent
    /// from current storage (vanished on a mid-life install).
    private void replayRemovedKeys() {
        lastReplayedView.forEach((key, value) -> {
            if (!storage.containsKey(key)) {
                router.route(new ValueRemove<>(new Remove<>(key), Option.some(value)));
            }
        });
    }

    /// Fire a synthetic `ValuePut` for every key whose current value differs from (or is absent
    /// in) the last-replayed view — new and changed keys. Unchanged keys are skipped so a mid-life
    /// install delivers only the delta.
    private void replayPutKeys() {
        storage.forEach((key, value) -> {
            if (!value.equals(lastReplayedView.get(key))) {
                router.route(new ValuePut<>(new Put<>(key, value), Option.option(lastReplayedView.get(key))));
            }
        });
    }

    @Override
    public Unit reset() {
        notifyRemoveAll();
        storage.clear();
        lastReplayedView.clear();
        return Unit.unit();
    }

    private void notifyRemoveAll() {
        storage.forEach((key, value) -> router.route(new ValueRemove<>(new Remove<>(key), Option.some(value))));
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
    @Contract
    public <KK, VV> void forEach(Class<KK> keyClass, Class<VV> valueClass, BiConsumer<KK, VV> consumer) {
        storage.forEach((key, value) -> {
                            if (keyClass.isInstance(key) && valueClass.isInstance(value)) {
                                consumer.accept((KK) key, (VV) value);
                            }
                        });
    }

    @MessageReceiver
    @Contract
    public void find(Find find) {
        router.routeAsync(() -> new FoundEntries<>(List.copyOf(storage.entrySet())));
    }
}
