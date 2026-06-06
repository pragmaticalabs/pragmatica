package org.pragmatica.cluster.state.kvstore;

import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.SliceCodec;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.serialization.FrameworkCodecs.frameworkCodecs;

/// Verifies the replay signal exposed by [KVStore#isReplaying()]: a router subscriber invoked
/// synchronously during the [KVStore#restoreSnapshot] re-emit fan-out observes `true`, while the
/// same subscriber invoked during a live apply (via [KVStore#process]) observes `false`, and the
/// flag is always lowered once `restoreSnapshot` returns — even if a subscriber throws.
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
    void isReplaying_false_during_live_apply_true_during_restore_resetAfter() {
        var router = MessageRouter.mutable();
        var store = newStore(router);
        var observedDuringPut = new ArrayList<Boolean>();

        router.addRoute(ValuePut.class, (ValuePut<ReplayKey, String> put) -> observedDuringPut.add(store.isReplaying()));

        // (a) LIVE apply via the engine apply entry point — subscriber must see isReplaying()==false.
        store.process(store.createBatch(List.of(new Put<>(new ReplayKey("alpha"), "live-value"))));

        assertThat(observedDuringPut)
            .as("live apply must be observed as NOT replaying")
            .containsExactly(false);

        // (b) snapshot the live state, then restore it — the re-emit fan-out must be seen as replaying.
        var snapshotBytes = store.makeSnapshot().unwrap();
        observedDuringPut.clear();

        store.restoreSnapshot(snapshotBytes).unwrap();

        assertThat(observedDuringPut)
            .as("restore fan-out re-emits one ValuePut per restored entry, each seen as replaying")
            .containsExactly(true);

        // (c) flag is lowered once restoreSnapshot returns (the finally in replaceAllDuringReplay).
        assertThat(store.isReplaying())
            .as("replay flag must be reset after restoreSnapshot returns")
            .isFalse();
    }

    @Test
    void isReplaying_reset_even_when_restore_subscriber_throws() {
        var router = MessageRouter.mutable();
        var store = newStore(router);
        var sawReplayingWhileThrowing = new ArrayList<Boolean>();

        // Seed one entry BEFORE registering the throwing subscriber, so the snapshot is non-empty
        // without the seed put itself tripping the throw.
        store.process(store.createBatch(List.of(new Put<>(new ReplayKey("beta"), "seed"))));
        var snapshotBytes = store.makeSnapshot().unwrap();

        // Subscriber that records the flag, then throws during the restore fan-out.
        router.addRoute(ValuePut.class, (ValuePut<ReplayKey, String> put) -> {
            sawReplayingWhileThrowing.add(store.isReplaying());
            throw new IllegalStateException("subscriber failure during replay");
        });

        // restoreSnapshot's onSuccess fan-out propagates the subscriber throw; the finally in
        // replaceAllDuringReplay must still lower the flag before it escapes. Tolerate the throw.
        try {
            store.restoreSnapshot(snapshotBytes);
        } catch (RuntimeException ignored) {
            // Expected: the throwing subscriber surfaces as an exception out of the fan-out.
        }

        assertThat(sawReplayingWhileThrowing)
            .as("the throwing subscriber ran during the replay fan-out")
            .contains(true);

        assertThat(store.isReplaying())
            .as("replay flag must be reset even when a subscriber throws during the fan-out")
            .isFalse();
    }
}
