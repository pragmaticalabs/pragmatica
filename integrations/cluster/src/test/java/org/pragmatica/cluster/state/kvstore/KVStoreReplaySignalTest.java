package org.pragmatica.cluster.state.kvstore;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.SliceCodec;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// Verifies the sync → activate → replay semantics (cluster-topology-overhaul §5.8, AMENDED
/// 2026-06-11): `restoreSnapshot` installs state SILENTLY (no notifications); the deferred
/// notification burst is delivered by `replayNotifications()`, during which `isReplaying()` is
/// true; a mid-life install does DIFF-replay (puts for new/changed, removes for vanished).
class KVStoreReplaySignalTest {
    /// Self-contained test key; codec registered manually below so the test pulls in no codegen.
    record ReplayKey(String id) implements StructuredKey {}

    private static SliceCodec buildTestCodec() {
        var keyTag = SliceCodec.deterministicTag(ReplayKey.class.getName());
        var keyCodec = new SliceCodec.TypeCodec<>(
            ReplayKey.class, keyTag,
            (codec, buf, value) -> codec.write(buf, value.id()),
            (codec, buf) -> new ReplayKey((String) codec.read(buf))
        );
        var codecs = new ArrayList<SliceCodec.TypeCodec<?>>();
        codecs.add(keyCodec);
        // KVCommand.Put codec is needed because createBatch serializes the command list to derive
        // a deterministic batch id; KvstoreCodecs supplies the KVCommand.* codecs.
        codecs.addAll(KvstoreCodecs.CODECS);
        return SliceCodec.sliceCodec(frameworkCodecs(), codecs);
    }

    private static KVStore<ReplayKey, String> newStore(MessageRouter.MutableRouter router) {
        var codec = buildTestCodec();
        return new KVStore<>(router, codec, codec);
    }

    @Test
    void restoreSnapshot_isSilent_replayFiresNotificationsMarkedReplaying() {
        var router = MessageRouter.mutable();
        var store = newStore(router);
        var observedDuringPut = new ArrayList<Boolean>();

        router.addRoute(ValuePut.class, (ValuePut<ReplayKey, String> put) -> observedDuringPut.add(store.isReplaying()));

        // (a) LIVE apply — subscriber sees isReplaying()==false.
        store.process(store.createBatch(List.of(new Put<>(new ReplayKey("alpha"), "live-value"))));
        assertThat(observedDuringPut)
            .as("live apply is observed as NOT replaying")
            .containsExactly(false);

        // (b) SILENT install: snapshot the live state, then restore it into a fresh store — NO
        // notifications must fire during restore.
        var snapshotBytes = store.makeSnapshot().unwrap();
        var freshRouter = MessageRouter.mutable();
        var freshStore = newStore(freshRouter);
        var freshObserved = new ArrayList<Boolean>();
        freshRouter.addRoute(ValuePut.class, (ValuePut<ReplayKey, String> put) -> freshObserved.add(freshStore.isReplaying()));

        freshStore.restoreSnapshot(snapshotBytes).unwrap();
        assertThat(freshObserved)
            .as("restoreSnapshot is SILENT — no notifications before replay")
            .isEmpty();

        // (c) REPLAY: the deferred burst fires one put per restored key, marked isReplaying()==true.
        freshStore.replayNotifications();
        assertThat(freshObserved)
            .as("replay fires one put per restored key, each marked replaying")
            .containsExactly(true);

        // (d) isReplaying() is lowered once the burst returns.
        assertThat(freshStore.isReplaying())
            .as("replay flag is reset after replayNotifications returns")
            .isFalse();
    }

    @Test
    void replayNotifications_diffReplay_putsChangedAndNew_removesVanished() {
        var router = MessageRouter.mutable();
        var store = newStore(router);

        // First install + replay establishes the last-replayed view: {keep, change, drop}.
        store.process(store.createBatch(List.of(new Put<>(new ReplayKey("keep"), "v1"))));
        store.process(store.createBatch(List.of(new Put<>(new ReplayKey("change"), "old"))));
        store.process(store.createBatch(List.of(new Put<>(new ReplayKey("drop"), "gone-soon"))));
        store.replayNotifications();

        // Mid-life install: keep unchanged, change updated, drop vanished, add new.
        var sourceRouter = MessageRouter.mutable();
        var source = newStore(sourceRouter);
        source.process(source.createBatch(List.of(new Put<>(new ReplayKey("keep"), "v1"))));
        source.process(source.createBatch(List.of(new Put<>(new ReplayKey("change"), "new"))));
        source.process(source.createBatch(List.of(new Put<>(new ReplayKey("add"), "fresh"))));
        var midLifeSnapshot = source.makeSnapshot().unwrap();

        var puts = new ArrayList<ReplayKey>();
        var removes = new ArrayList<ReplayKey>();
        router.addRoute(ValuePut.class, (ValuePut<ReplayKey, String> p) -> puts.add(p.cause().key()));
        router.addRoute(ValueRemove.class, (ValueRemove<ReplayKey, String> r) -> removes.add(r.cause().key()));

        store.restoreSnapshot(midLifeSnapshot).unwrap();
        store.replayNotifications();

        assertThat(puts)
            .as("diff-replay fires puts for changed + new keys only (unchanged 'keep' skipped)")
            .containsExactlyInAnyOrder(new ReplayKey("change"), new ReplayKey("add"));
        assertThat(removes)
            .as("diff-replay fires a remove for the vanished key")
            .containsExactly(new ReplayKey("drop"));
    }
}
