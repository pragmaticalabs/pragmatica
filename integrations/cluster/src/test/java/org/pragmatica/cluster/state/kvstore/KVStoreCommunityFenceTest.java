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

/// Write-once community fence (#1840, H11): a write that would CHANGE the committed non-empty community of
/// a [CommunityFenced] value is refused in the applier, for a plain `Put` and for a `LeaderTransaction`
/// mutation (which then applies none of its mutations). Creation, an identical community and a committed
/// empty community pass; removal is untouched. A refused write emits no notification.
class KVStoreCommunityFenceTest {
    private record Key(String id) implements StructuredKey {}

    private record Assignment(String role, String community, String hint) implements CommunityFenced {
        @Override
        public String fenceCommunity() {
            return community;
        }
    }

    private static final Key NODE = new Key("node");
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
    void put_changingCommittedCommunity_isRefusedAndDirectiveUnchanged() {
        put(NODE, new Assignment("WORKER", "c1", ""));
        puts.set(0);
        put(NODE, new Assignment("WORKER", "c2", ""));

        assertThat(stored(NODE)).isEqualTo(new Assignment("WORKER", "c1", ""));
        assertThat(puts.get()).as("a refused write emits no notification").isZero();
    }

    @Test
    void put_clearingCommittedCommunity_isRefused() {
        put(NODE, new Assignment("WORKER", "c1", ""));
        put(NODE, new Assignment("WORKER", "", ""));

        assertThat(stored(NODE)).isEqualTo(new Assignment("WORKER", "c1", ""));
    }

    @Test
    void put_identicalCommunity_isAcceptedEvenWithOtherFieldsChanged() {
        put(NODE, new Assignment("WORKER", "c1", ""));
        put(NODE, new Assignment("WORKER", "c1", "hint-2"));

        assertThat(stored(NODE)).isEqualTo(new Assignment("WORKER", "c1", "hint-2"));
    }

    @Test
    void put_firstWrite_isAccepted() {
        put(NODE, new Assignment("WORKER", "c1", ""));

        assertThat(stored(NODE)).isEqualTo(new Assignment("WORKER", "c1", ""));
    }

    @Test
    void put_committedEmptyCommunity_fencesNothing() {
        put(NODE, new Assignment("CORE", "", ""));
        put(NODE, new Assignment("WORKER", "c1", ""));

        assertThat(stored(NODE)).isEqualTo(new Assignment("WORKER", "c1", ""));
    }

    @Test
    void put_nonFencedValues_areUntouched() {
        put(OTHER, "first");
        put(OTHER, "second");

        assertThat(stored(OTHER)).isEqualTo("second");
    }

    @Test
    void remove_thenCreateWithAnotherCommunity_isAllowed_removalIsOutOfScope() {
        put(NODE, new Assignment("WORKER", "c1", ""));
        apply(new KVCommand.Remove<>(NODE));
        put(NODE, new Assignment("WORKER", "c2", ""));

        assertThat(stored(NODE)).isEqualTo(new Assignment("WORKER", "c2", ""));
    }

    @Test
    void leaderTransaction_changingCommittedCommunity_isRefusedAndAppliesNoMutation() {
        put(LeaderKey.INSTANCE, LEADER);
        var committed = new Assignment("WORKER", "c1", "");

        put(NODE, committed);
        var result = apply(new KVCommand.LeaderTransaction<>(NODE,
                                                             "reassign",
                                                             LEADER,
                                                             List.of(),
                                                             List.of(new KVCommand.Mutation<>(OTHER, Option.none(), Option.some("created-alongside")),
                                                                     new KVCommand.Mutation<>(NODE,
                                                                                              Option.some(committed),
                                                                                              Option.some(new Assignment("WORKER", "c2", ""))))));

        assertThat(result).isEqualTo(new KVCommand.TransactionResult("reassign", false));
        assertThat(stored(NODE)).isEqualTo(committed);
        assertThat(store.get(OTHER).isEmpty()).as("a refused transaction applies none of its mutations").isTrue();
    }

    @Test
    void leaderTransaction_creatingDirective_isAccepted() {
        put(LeaderKey.INSTANCE, LEADER);
        var result = apply(new KVCommand.LeaderTransaction<>(NODE,
                                                             "create",
                                                             LEADER,
                                                             List.of(),
                                                             List.of(new KVCommand.Mutation<>(NODE,
                                                                                              Option.none(),
                                                                                              Option.some(new Assignment("WORKER", "c1", ""))))));

        assertThat(result).isEqualTo(new KVCommand.TransactionResult("create", true));
        assertThat(stored(NODE)).isEqualTo(new Assignment("WORKER", "c1", ""));
    }
}
