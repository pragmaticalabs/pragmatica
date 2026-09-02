package org.pragmatica.cluster.state.kvstore;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.cluster.state.kvstore.KVCommand.Get;
import org.pragmatica.cluster.state.kvstore.KVCommand.Noop;
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
            case Noop<?> ignored -> Option.none();
        };
    }

    private Option<V> handleGet(Get<K> get) {
        var value = Option.option(storage.get(get.key()));

        router.route(new ValueGet<>(get, value));

        return value;
    }

    private Option<V> handlePut(Put<K, V> put) {
        if (staleWrite(put.key(), put.value())) {
            return Option.option(storage.get(put.key()));
        }

        var oldValue = Option.option(storage.put(put.key(), put.value()));

        router.route(new ValuePut<>(put, oldValue));

        return oldValue;
    }

    /// The committed-state fence, shared by [#handlePut] and [#handleRemove] (#345 piece 1a, #379):
    /// a mutation (a `Put` value or a `Remove` witness) is rejected when it is either a stale
    /// `LeaderKey` write (H4 leader fence), a stale-epoch write to any [EpochBearing] value
    /// (ownership fence), a non-successor write to a [VersionFenced] value (lost-update fence,
    /// RFC-0018 #570), or a regressive write to a [MonotonicFenced] value (running-max fence,
    /// #700). All arms are pure functions of the committed storage content and the
    /// incoming value alone, so every replica decides identically inside the consensus applier.
    /// Snapshot restore ([#restoreSnapshot]) intentionally bypasses all fences: a restored snapshot
    /// is the authoritative committed state, not a competing write.
    private boolean staleWrite(K key, Object incoming) {
        return staleLeaderWrite(key, incoming) || staleEpochWrite(key, incoming) || staleSuccessorWrite(key, incoming)
               || regressiveWatermarkWrite(key, incoming);
    }

    /// H4 leader fence (cluster-topology-overhaul §Wave 8.2): `LeaderKey` writes are
    /// compare-and-put — applied only when the incoming [LeaderValue#viewSequence()] is strictly
    /// greater than the stored one. The check is deterministic (it depends only on the replicated
    /// storage content and the incoming value), so every replica accepts or rejects identically
    /// inside the consensus applier. A rejected write mutates nothing and emits NO notification — a
    /// stale leader must never be observed by the election FSMs.
    private boolean staleLeaderWrite(K key, Object incoming) {
        return key instanceof LeaderKey
               && incoming instanceof LeaderValue in
               && storage.get(key) instanceof LeaderValue stored
               && in.viewSequence() <= stored.viewSequence();
    }

    /// Ownership/governance epoch fence (#345 piece 1a): generalizes the leader fence to ANY
    /// [EpochBearing] value (governor announcement, DHT partition ownership). When BOTH the incoming
    /// and the currently-committed value under the key are `EpochBearing`, the write is rejected iff
    /// its epoch is STRICTLY older than the committed one — a deposed governor/owner cannot commit an
    /// OLD epoch over a newer one. A first write (no committed value) and any non-`EpochBearing`
    /// value pass through unchanged. Equal-or-newer epochs are accepted: governor reannouncement and
    /// dissolution legitimately re-write at the same epoch, and a stale-owner takeover rewrites
    /// ownership at the same epoch while bumping only its `ownershipTerm` — see [EpochBearing].
    private boolean staleEpochWrite(K key, Object incoming) {
        return incoming instanceof EpochBearing<?> in
               && storage.get(key) instanceof EpochBearing<?> stored
               && incomingEpochIsStale(in, stored);
    }

    /// Captures the recursive epoch type variable so the [Comparable#compareTo] is fully type-checked
    /// (never reflective). The cast of the stored epoch is safe: a single key only ever holds one
    /// `EpochBearing` value type (governor or ownership), so its epoch type matches the incoming one.
    @SuppressWarnings("unchecked")
    private static <E extends Comparable<E>> boolean incomingEpochIsStale(EpochBearing<E> incoming,
                                                                          EpochBearing<?> stored) {
        return incoming.fenceEpoch()
                       .compareTo((E) stored.fenceEpoch()) < 0;
    }

    /// Lost-update fence (RFC-0018, #570): [VersionFenced] values are compare-and-put on their
    /// version chain — a write is applied only when its incoming version is the IMMEDIATE SUCCESSOR
    /// of the committed one. An EQUAL version is rejected, unlike the epoch arm: epoch equality is a
    /// legitimate re-announcement, but on a version chain an equal write is the second writer of the
    /// read-modify-write race, silently overwriting the first. Version jumps are rejected too — a
    /// write built on anything but the current committed value is built on a stale read. A first
    /// write (no committed value, or a non-fenced one) passes: there is no chain to fence yet.
    /// Deterministic for the same reason as the arms above: reads only committed storage and the
    /// incoming value. A rejected write mutates nothing and emits NO notification; callers detect
    /// the loss by re-reading committed state after the apply (batch merging makes the applier's
    /// return value unattributable — see [VersionFenced]).
    private boolean staleSuccessorWrite(K key, Object incoming) {
        return incoming instanceof VersionFenced in
               && storage.get(key) instanceof VersionFenced stored
               && in.fenceVersion() != stored.fenceVersion() + 1;
    }

    /// Running-max fence (#700): [MonotonicFenced] values are advance-only — the write is rejected
    /// iff its watermark is STRICTLY LOWER than the committed one. Equal is ACCEPTED (unlike the
    /// successor arm): re-publishing the same coverage with fresh contents is legitimate; only
    /// regression loses data, because the retention floor may already have reclaimed the log below
    /// the committed claim, and a lower claim landing second would leave those records reachable
    /// from nowhere. First write passes (no claim yet). Deterministic like the arms above: reads
    /// only committed storage and the incoming value. A rejected write mutates nothing and emits NO
    /// notification (see [MonotonicFenced] for the caller-side detection caveat).
    private boolean regressiveWatermarkWrite(K key, Object incoming) {
        return incoming instanceof MonotonicFenced in
               && storage.get(key) instanceof MonotonicFenced stored
               && in.fenceWatermark() < stored.fenceWatermark();
    }

    private Option<V> handleRemove(Remove<K> remove) {
        if (staleRemove(remove)) {
            return Option.option(storage.get(remove.key()));
        }

        var oldValue = Option.option(storage.remove(remove.key()));

        router.route(new ValueRemove<>(remove, oldValue));

        return oldValue;
    }

    /// The delete-side committed-state fence (#379) — STRICTER than the `Put` fence by design. A
    /// `Remove` of a key whose COMMITTED value is fenced (any [EpochBearing] value, or the `LeaderKey`'s
    /// [LeaderValue]) is rejected — applied to nothing, NO `ValueRemove` emitted — UNLESS it carries a
    /// witness that is (a) present, (b) the SAME fenced kind as the committed value (an `EpochBearing`
    /// witness for an `EpochBearing`-valued key, a `LeaderValue` for the `LeaderKey`), and (c) current
    /// (equal-or-newer epoch, or strictly-greater `viewSequence`). A missing, wrong-typed, or stale
    /// witness fails, so a deposed owner cannot delete a fenced key even with a bare `Remove(key)` —
    /// closing the witnessless-delete gap. A non-fenced committed value, or an absent key, deletes
    /// freely — preserving every existing unfenced remover (locks, blueprints, registry entries; no
    /// production remover targets a fenced key today). The decision reads only committed storage and
    /// the command, so every replica accepts or rejects a delete identically inside the applier.
    private boolean staleRemove(Remove<K> remove) {
        var key = remove.key();

        return switch (storage.get(key)) {
            case LeaderValue committed when key instanceof LeaderKey -> !currentLeaderWitness(committed,
                                                                                              remove.witness());
            case EpochBearing<?> committed -> !currentEpochWitness(committed, remove.witness());
            case null, default -> false;
        };
    }

    /// A witness authorizes deleting an `EpochBearing`-valued key iff it is present, itself
    /// `EpochBearing` (matching kind), and NOT strictly-older than the committed epoch — the current
    /// owner or a newer one. Reuses the same [#incomingEpochIsStale] comparator as the `Put` fence.
    private boolean currentEpochWitness(EpochBearing<?> committed, Option<Object> witness) {
        return witness.map(w -> w instanceof EpochBearing<?> incoming && !incomingEpochIsStale(incoming, committed))
                      .or(false);
    }

    /// A witness authorizes deleting the `LeaderKey` iff it is present, itself a `LeaderValue`
    /// (matching kind), and its `viewSequence` is strictly greater than the committed one — the exact
    /// inverse of the `Put` leader fence's stale-OR-EQUAL (`<=`) rejection.
    private boolean currentLeaderWitness(LeaderValue committed, Option<Object> witness) {
        return witness.map(w -> w instanceof LeaderValue incoming && incoming.viewSequence() > committed.viewSequence())
                      .or(false);
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
                router.route(new ValuePut<>(new Put<>(key, value),
                                            Option.option(lastReplayedView.get(key))));
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
