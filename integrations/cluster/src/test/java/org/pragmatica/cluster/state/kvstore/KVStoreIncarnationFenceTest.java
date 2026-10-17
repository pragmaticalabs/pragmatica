package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/// Life fence (#1278): a write that would replace the committed non-zero incarnation of an [IncarnationFenced]
/// value is refused in the applier, for a plain `Put` and for a `LeaderTransaction` mutation. Creation, the same
/// incarnation, a committed zero and a create after removal pass. A refused write emits no notification.
class KVStoreIncarnationFenceTest {
    private record Key(String id) implements StructuredKey {}

    private record Life(long incarnation, int replicas) implements IncarnationFenced {
        @Override
        public long fenceIncarnation() {
            return incarnation;
        }
    }

    private static final Key STREAM = new Key("orders");
    private static final Key OTHER = new Key("other");
    private static final LeaderValue LEADER = new LeaderValue(new NodeId("core"), 1);
    private final AtomicInteger puts = new AtomicInteger();
    private final KVStore<StructuredKey, Object> store = new KVStore<>(router(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });

    private MessageRouter router() {
        var router = MessageRouter.mutable();
        router.addRoute(KVStoreNotification.ValuePut.class, _ -> puts.incrementAndGet());
        return router;
    }

    private Object apply(KVCommand<StructuredKey> command) {
        return store.process(store.createBatch(List.of(command))).getFirst();
    }

    private void put(StructuredKey key, Object value) {
        apply(new KVCommand.Put<>(key, value));
    }

    private Object stored(StructuredKey key) {
        return store.get(key).or((Object) null);
    }

    @Test
    void put_concurrentFirstCreates_firstCommittedLifeWins_secondIsRefusedSilently() {
        put(STREAM, new Life(11, 3));
        puts.set(0);
        put(STREAM, new Life(22, 3));

        assertThat(stored(STREAM)).isEqualTo(new Life(11, 3));
        assertThat(puts.get()).as("a refused write emits no notification").isZero();
    }

    @Test
    void put_sameIncarnation_isAccepted_aRewriteOfTheSameLife() {
        put(STREAM, new Life(11, 1));
        put(STREAM, new Life(11, 3));

        assertThat(stored(STREAM)).isEqualTo(new Life(11, 3));
    }

    @Test
    void put_recreateBeforeTheRemovalApplied_isRefused_afterItApplied_isAccepted() {
        put(STREAM, new Life(11, 3));
        put(STREAM, new Life(22, 3));

        assertThat(stored(STREAM)).as("the old life still committed").isEqualTo(new Life(11, 3));

        apply(new KVCommand.Remove<>(STREAM));
        put(STREAM, new Life(22, 3));

        assertThat(stored(STREAM)).isEqualTo(new Life(22, 3));
    }

    @Test
    void put_committedZeroIncarnation_fencesNothing() {
        put(STREAM, new Life(0, 3));
        put(STREAM, new Life(22, 3));

        assertThat(stored(STREAM)).isEqualTo(new Life(22, 3));
    }

    @Test
    void put_nonFencedValues_areUntouched() {
        put(OTHER, "first");
        put(OTHER, "second");

        assertThat(stored(OTHER)).isEqualTo("second");
    }

    @Test
    void leaderTransaction_replacingTheCommittedLife_isRefusedAndAppliesNoMutation() {
        put(LeaderKey.INSTANCE, LEADER);
        var committed = new Life(11, 3);

        put(STREAM, committed);
        var result = apply(new KVCommand.LeaderTransaction<>(STREAM,
                                                             "reincarnate",
                                                             LEADER,
                                                             List.of(),
                                                             List.of(new KVCommand.Mutation<>(OTHER, Option.none(), Option.some("created-alongside")),
                                                                     new KVCommand.Mutation<>(STREAM,
                                                                                              Option.some(committed),
                                                                                              Option.some(new Life(22, 3))))));

        assertThat(result).isEqualTo(new KVCommand.TransactionResult("reincarnate", false));
        assertThat(stored(STREAM)).isEqualTo(committed);
        assertThat(store.get(OTHER).isEmpty()).as("a refused transaction applies none of its mutations").isTrue();
    }
}
