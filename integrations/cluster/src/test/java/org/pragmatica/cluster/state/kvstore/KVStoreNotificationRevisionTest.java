// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.serialization.SliceCodec;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// Phase-3 design 4.4, W-10 (e), MT-10c: every published notification carries the revision of the batch that produced it, so a consumer
/// that read `(snapshot, revision r)` under the store monitor can discard `revision <= r` and keep everything after.
class KVStoreNotificationRevisionTest {
    private record Key(String id) implements StructuredKey {}

    private static final Key FIRST = new Key("first");
    private static final Key SECOND = new Key("second");
    private static final Key THIRD = new Key("third");

    private record Stamp(Key key, long revision, boolean replay) {}

    private final MessageRouter.MutableRouter router = MessageRouter.mutable();
    private final KVStore<Key, String> store = new KVStore<>(router, new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final List<Stamp> stamps = new ArrayList<>();

    private void recordPuts() {
        router.addRoute(KVStoreNotification.ValuePut.class,
                        (KVStoreNotification.ValuePut<Key, String> put) -> stamps.add(new Stamp(put.cause().key(),
                                                                                                store.notificationRevision().or(-1L),
                                                                                                store.isReplaying())));
    }

    private void recordRemoves() {
        router.addRoute(KVStoreNotification.ValueRemove.class,
                        (KVStoreNotification.ValueRemove<Key, String> remove) -> stamps.add(new Stamp(remove.cause().key(),
                                                                                                      store.notificationRevision().or(-1L),
                                                                                                      store.isReplaying())));
    }

    @Test
    void notificationRevision_isTheBatchSlot_forEveryNotificationOfTheBatch() {
        recordPuts();

        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"), new KVCommand.Put<>(SECOND, "two"))), 17);

        assertThat(stamps).containsExactly(new Stamp(FIRST, 17, false), new Stamp(SECOND, 17, false));
    }

    @Test
    void notificationRevision_isNotTheFieldBeforeTheBatch() {
        recordPuts();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 5);
        stamps.clear();

        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(SECOND, "two"))), 6);

        // MT-10c: stamped from the committedRevision field this reads 5, and a snapshot at r = 5 would discard it.
        assertThat(stamps).containsExactly(new Stamp(SECOND, 6, false));
    }

    @Test
    void snapshotAtR_discardsBatchesAtOrBelowR_keepsTheNextBatch() {
        recordPuts();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 5);
        long r;
        java.util.Map<Key, String> snapshot;

        synchronized (store) {
            r = store.committedRevision();
            snapshot = store.snapshot();
        }

        stamps.clear();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(SECOND, "two"), new KVCommand.Put<>(THIRD, "three"))), r + 1);

        assertThat(snapshot).containsOnlyKeys(FIRST);
        assertThat(stamps).hasSize(2).allSatisfy(stamp -> assertThat(stamp.revision()).isGreaterThan(r));
    }

    @Test
    void snapshotTakenInsideTheDispatch_containsTheBatch_andItsNotificationsAreAtOrBelowR() {
        var cut = new ArrayList<Long>();
        router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<Key, String> put) -> {
            if (put.cause().key().equals(FIRST)) {
                synchronized (store) {
                    cut.add(store.committedRevision());
                    cut.add((long) store.snapshot().size());
                }
            }
        });
        recordPuts();

        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"), new KVCommand.Put<>(SECOND, "two"))), 9);

        assertThat(cut).containsExactly(9L, 2L);
        assertThat(stamps).allSatisfy(stamp -> assertThat(stamp.revision()).isLessThanOrEqualTo(cut.getFirst()));
    }

    @Test
    void reentrantBatch_keepsItsOwnSlot_whileTheEarlierBatchIsStillDraining() {
        var committed = new ArrayList<Long>();
        router.addRoute(KVStoreNotification.ValuePut.class, (KVStoreNotification.ValuePut<Key, String> put) -> {
            if (put.cause().key().equals(FIRST)) {
                store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(THIRD, "three"))), 11);
                committed.add(store.committedRevision());
            }
        });
        recordPuts();

        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"), new KVCommand.Put<>(SECOND, "two"))), 10);

        assertThat(committed).containsExactly(11L);
        assertThat(stamps).containsExactly(new Stamp(FIRST, 10, false), new Stamp(SECOND, 10, false), new Stamp(THIRD, 11, false));
    }

    @Test
    void notificationRevision_isEmptyOutsideADispatch() {
        recordPuts();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 3);

        assertThat(store.notificationRevision().isEmpty()).isTrue();
    }

    @Test
    void mutationWithoutASlot_carriesTheUnchangedCommittedRevision() {
        recordPuts();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 4);
        stamps.clear();

        store.process(store.createBatch(List.of(new KVCommand.Put<>(SECOND, "two"))));

        assertThat(stamps).containsExactly(new Stamp(SECOND, 4, false));
        assertThat(store.committedRevision()).isEqualTo(4);
    }

    /// The replay is a DIFF against the delivered view (KVStore dispatcher design §3.7): a key already delivered with an unchanged value is not
    /// re-delivered. Harmless to FSM2, which drops every replay-flagged input whatever its revision (`FsmRuntime.keyValue`: `kv.replay()` ->
    /// `dropped_replay`). A replay that carries a change is stamped and flagged in
    /// [#replayAfterACommittedSnapshotRestore_carriesTheRestoredRevision].
    @Test
    void replayBurst_ofAnAlreadyDeliveredUnchangedKey_deliversNothing() {
        recordPuts();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 8);
        stamps.clear();

        store.replayNotifications();

        assertThat(stamps).isEmpty();
    }

    @Test
    void reset_stampsItsRemovesWithZero() {
        recordRemoves();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 8);

        store.reset();

        assertThat(stamps).containsExactly(new Stamp(FIRST, 0, false));
    }

    private record CodecKey(String id) implements StructuredKey {}

    @Test
    void replayAfterACommittedSnapshotRestore_carriesTheRestoredRevision() {
        var keyCodec = new SliceCodec.TypeCodec<>(CodecKey.class,
                                                  SliceCodec.deterministicTag(CodecKey.class.getName()),
                                                  (codec, buf, value) -> codec.write(buf, value.id()),
                                                  (codec, buf) -> new CodecKey((String) codec.read(buf)));
        var codecs = new ArrayList<SliceCodec.TypeCodec<?>>();

        codecs.add(keyCodec);
        codecs.addAll(KvstoreCodecs.CODECS);
        var codec = SliceCodec.sliceCodec(frameworkCodecs(), codecs);
        var source = new KVStore<CodecKey, String>(MessageRouter.mutable(), codec, codec);
        var targetRouter = MessageRouter.mutable();
        var target = new KVStore<CodecKey, String>(targetRouter, codec, codec);
        var seen = new ArrayList<Long>();

        source.process(source.createBatch(List.of(new KVCommand.Put<>(new CodecKey("k"), "v"))));
        targetRouter.addRoute(KVStoreNotification.ValuePut.class,
                              (KVStoreNotification.ValuePut<CodecKey, String> put) -> seen.add(target.notificationRevision().or(-1L)));
        target.restoreCommittedSnapshot(source.makeSnapshot().unwrap(), 10);

        target.replayNotifications();

        assertThat(seen).containsExactly(10L);
    }

    /// D8 (v2770c F1): the install is signalled by the replay item itself, with the store's committed revision, BEFORE its burst and also when
    /// the burst is empty. Mutation: the dispatcher not calling the listener (or calling it from the burst's first message).
    @Test
    void replayStart_isSignalled_withTheCommittedRevision_evenWhenTheBurstIsEmpty() {
        var events = new ArrayList<String>();

        recordPuts();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 8);
        store.onReplayStart(revision -> events.add("install@" + revision));

        store.replayNotifications();

        assertThat(events).containsExactly("install@8");
    }

    /// The signal precedes the burst, for a committed restore (the install's revision) and for a plain `restoreSnapshot` (the unchanged one, the
    /// metadata channel's path, v2770c F2).
    @Test
    void replayStart_precedesTheBurst_forBothInstallPaths() {
        var keyCodec = new SliceCodec.TypeCodec<>(CodecKey.class,
                                                  SliceCodec.deterministicTag(CodecKey.class.getName()),
                                                  (codec, buf, value) -> codec.write(buf, value.id()),
                                                  (codec, buf) -> new CodecKey((String) codec.read(buf)));
        var codecs = new ArrayList<SliceCodec.TypeCodec<?>>();

        codecs.add(keyCodec);
        codecs.addAll(KvstoreCodecs.CODECS);
        var codec = SliceCodec.sliceCodec(frameworkCodecs(), codecs);
        var source = new KVStore<CodecKey, String>(MessageRouter.mutable(), codec, codec);
        var targetRouter = MessageRouter.mutable();
        var target = new KVStore<CodecKey, String>(targetRouter, codec, codec);
        var events = new ArrayList<String>();

        source.process(source.createBatch(List.of(new KVCommand.Put<>(new CodecKey("k"), "v"))));
        targetRouter.addRoute(KVStoreNotification.ValuePut.class,
                              (KVStoreNotification.ValuePut<CodecKey, String> put) -> events.add("put"));
        target.onReplayStart(revision -> events.add("install@" + revision));
        target.restoreCommittedSnapshot(source.makeSnapshot().unwrap(), 10);
        target.replayNotifications();
        source.process(source.createBatch(List.of(new KVCommand.Put<>(new CodecKey("k2"), "v2"))));
        target.restoreSnapshot(source.makeSnapshot().unwrap());
        target.replayNotifications();

        assertThat(events).containsExactly("install@10", "put", "install@10", "put");
    }

    /// #2981 C1: replay-start is a listener LIST. FSM2's install signal and the node lease's revoke both register; a single slot would let the
    /// second registration displace the first (fail-open revoke). Mutation: back to one slot (assignment) -> RED on "first".
    @Test
    void replayStart_tellsEveryRegisteredListener_inRegistrationOrder() {
        var events = new ArrayList<String>();

        recordPuts();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 8);
        store.onReplayStart(revision -> events.add("first@" + revision));
        store.onReplayStart(revision -> events.add("second@" + revision));

        store.replayNotifications();

        assertThat(events).containsExactly("first@8", "second@8");
    }

    /// #2981 C1: one failing replay-start listener does not skip the ones registered after it (the revoke must not depend on FSM2's listener
    /// staying healthy). Mutation: drop the per-listener isolation -> RED (the throw escapes and "second" is never told).
    @Test
    void replayStart_aThrowingListener_doesNotSkipTheOthers() {
        var events = new ArrayList<String>();

        recordPuts();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 8);
        store.onReplayStart(KVStoreNotificationRevisionTest::failingListener);
        store.onReplayStart(revision -> events.add("second@" + revision));

        store.replayNotifications();

        assertThat(events).containsExactly("second@8");
    }

    /// #2981 step 2, S2 (r7 S2): every reset listener is told, in registration order, BEFORE the store is emptied (the node lease is revoked
    /// before the view can regress). Mutations: `reset()` not calling the listeners -> RED (no events); listeners told after `resetStorage`
    /// -> RED (they see 0 entries).
    @Test
    void reset_tellsEveryResetListener_beforeTheStoreIsEmptied() {
        var events = new ArrayList<String>();

        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 8);
        store.onReset(() -> events.add("first:" + store.snapshot().size()));
        store.onReset(() -> events.add("second:" + store.snapshot().size()));

        store.reset();

        assertThat(events).containsExactly("first:1", "second:1");
        assertThat(store.snapshot()).isEmpty();
    }

    private static void failingListener(long revision) {
        throw new IllegalStateException("listener failure at " + revision);
    }
}
