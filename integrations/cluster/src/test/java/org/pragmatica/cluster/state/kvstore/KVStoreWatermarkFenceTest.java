package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Running-max fence (#700): a `Put` of a [MonotonicFenced] value is applied only when its
/// watermark does not REGRESS the committed one. The canonical consumer is the entity checkpoint
/// claim, which the retention floor trusts when reclaiming log segments — a lower honest claim
/// landing after a higher one (two folds either side of a partition handover) would leave the
/// records between the two offsets on no reachable node. Equal is ACCEPTED, unlike the successor
/// fence: re-publishing the same coverage with fresh contents loses nothing. First write passes;
/// non-fenced values are untouched; a rejected write emits NO notification.
///
/// Same stub-codec posture as `KVStorePutFenceTest`: batches are applied directly, never
/// snapshotted.
class KVStoreWatermarkFenceTest {
    private record ClaimKey(String name) implements StructuredKey {}

    /// `tag` identifies WHICH writer's claim is committed — the acceptance criterion is that the
    /// HIGHER claim survives a lower one arriving later, not merely that "some" value remains.
    private record ClaimValue(String tag, long watermark) implements MonotonicFenced {
        @Override
        public long fenceWatermark() {
            return watermark;
        }
    }

    private record PlainKey(String name) implements StructuredKey {}

    private record PlainValue(String tag) {}

    private static final ClaimKey KEY = new ClaimKey("entity-checkpoint");

    private MessageRouter.MutableRouter router;
    private KVStore<StructuredKey, Object> store;
    private List<Object> putNotifications;

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    @BeforeEach
    void setUp() {
        router = MessageRouter.mutable();
        store = new KVStore<>(router, stubSerializer(), stubDeserializer());
        putNotifications = new ArrayList<>();
        router.addRoute(ValuePut.class,
                        (ValuePut<StructuredKey, Object> event) -> putNotifications.add(event.cause().value()));
    }

    private void put(StructuredKey key, Object value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
    }

    private Object stored(StructuredKey key) {
        return store.get(key).or((Object) null);
    }

    /// The #700 acceptance race: two writers, the LOWER offset arrives LAST — the higher claim
    /// stands, refused by the substrate rather than by either caller.
    @Test
    void put_rejectsRegressingWatermark_higherClaimStands() {
        put(KEY, new ClaimValue("owner-a", 100));
        put(KEY, new ClaimValue("owner-b", 40));

        assertThat(stored(KEY)).isEqualTo(new ClaimValue("owner-a", 100));
    }

    @Test
    void put_acceptsEqualWatermark_freshContentsReplaceTheClaim() {
        put(KEY, new ClaimValue("first-snapshot", 100));
        put(KEY, new ClaimValue("fresh-snapshot", 100));

        assertThat(stored(KEY)).isEqualTo(new ClaimValue("fresh-snapshot", 100));
    }

    @Test
    void put_acceptsAdvancingWatermark() {
        put(KEY, new ClaimValue("owner-a", 100));
        put(KEY, new ClaimValue("owner-b", 250));

        assertThat(stored(KEY)).isEqualTo(new ClaimValue("owner-b", 250));
    }

    @Test
    void put_firstWrite_passesWithNoCommittedClaim() {
        put(KEY, new ClaimValue("bootstrap", 0));

        assertThat(stored(KEY)).isEqualTo(new ClaimValue("bootstrap", 0));
    }

    @Test
    void put_nonFencedValues_areUntouchedByTheFence() {
        var key = new PlainKey("plain");

        put(key, new PlainValue("first"));
        put(key, new PlainValue("second"));

        assertThat(stored(key)).isEqualTo(new PlainValue("second"));
    }

    /// A fenced-out write must be INVISIBLE downstream: no ValuePut notification may leak for it —
    /// a rejected claim observed by a listener would be a regression report for a regression that
    /// never happened.
    @Test
    void put_rejectedWrite_emitsNoNotification() {
        put(KEY, new ClaimValue("owner-a", 100));
        putNotifications.clear();
        put(KEY, new ClaimValue("owner-b", 40));

        assertThat(putNotifications).isEmpty();
    }
}
