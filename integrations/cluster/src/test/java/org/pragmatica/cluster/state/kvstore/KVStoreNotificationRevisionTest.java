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
                                                                                                store.notificationRevision(),
                                                                                                store.isReplaying())));
    }

    private void recordRemoves() {
        router.addRoute(KVStoreNotification.ValueRemove.class,
                        (KVStoreNotification.ValueRemove<Key, String> remove) -> stamps.add(new Stamp(remove.cause().key(),
                                                                                                      store.notificationRevision(),
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
    void notificationRevision_isZeroOutsideADispatch() {
        recordPuts();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 3);

        assertThat(store.notificationRevision()).isZero();
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

    @Test
    void replayBurst_carriesTheCommittedRevision_andIsFlaggedReplay() {
        recordPuts();
        store.processCommitted(store.createBatch(List.of(new KVCommand.Put<>(FIRST, "one"))), 8);
        stamps.clear();

        store.replayNotifications();

        assertThat(stamps).containsExactly(new Stamp(FIRST, 8, true));
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
                              (KVStoreNotification.ValuePut<CodecKey, String> put) -> seen.add(target.notificationRevision()));
        target.restoreCommittedSnapshot(source.makeSnapshot().unwrap(), 10);

        target.replayNotifications();

        assertThat(seen).containsExactly(10L);
    }
}
