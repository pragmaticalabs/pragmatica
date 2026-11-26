package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Mutation;
import org.pragmatica.cluster.state.kvstore.KVCommand.ReadWitness;
import org.pragmatica.cluster.state.kvstore.KVCommand.RefusalKind;
import org.pragmatica.cluster.state.kvstore.KVCommand.TransactionResult;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// TA2 of #3754 C1: the fence of a leader-only external effect is a transaction with the stored leader as its witness and an EMPTY
/// mutation list, run against the REAL applier. Accepted, it changes no storage and raises no notification, so a node can commit it
/// for every effect without making any subscriber act; refused, it names LEADER (a leader other than the stored one) or WITNESS (a
/// guard unequal to the committed value). The control is a one-mutation re-put of the same value, which does raise a notification:
/// it shows the notification probe can see a write at all.
class KVStoreWitnessOnlyTransactionTest {
    private record Key(String id) implements StructuredKey {}

    private static final Key GUARDED = new Key("guarded");
    private static final Key TARGET = new Key("target");
    private static final LeaderValue LEADER = new LeaderValue(new NodeId("core"), 1);
    private static final LeaderValue OTHER_LEADER = new LeaderValue(new NodeId("deposed"), 0);

    private KVStore<StructuredKey, Object> store;
    private List<Object> notified;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();

        store = new KVStore<>(router, stubSerializer(), stubDeserializer());
        notified = new ArrayList<>();
        router.addRoute(ValuePut.class, (ValuePut<StructuredKey, Object> put) -> notified.add(put.cause().value()));
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        apply(new KVCommand.Put<>(GUARDED, "committed"));
        notified.clear();
    }

    private void apply(KVCommand<StructuredKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    private TransactionResult transaction(LeaderValue leader,
                                          List<ReadWitness<StructuredKey>> guards,
                                          List<Mutation<StructuredKey, Object>> mutations) {
        return (TransactionResult) store.process(store.createBatch(List.of(new KVCommand.LeaderTransaction<>(TARGET,
                                                                                                             "tx",
                                                                                                             leader,
                                                                                                             guards,
                                                                                                             mutations))))
                                        .getFirst();
    }

    private static ReadWitness<StructuredKey> guard(String expected) {
        return new ReadWitness<>(GUARDED, Option.some(expected));
    }

    @Test
    void witnessOnlyTransaction_underTheStoredLeader_isAcceptedWithNoStorageChangeAndNoNotification() {
        var result = transaction(LEADER, List.of(guard("committed")), List.of());

        assertThat(result.accepted()).isTrue();
        assertThat(result.refusal().isEmpty()).isTrue();
        assertThat(store.get(GUARDED).or((Object) null)).isEqualTo("committed");
        assertThat(store.get(TARGET).isEmpty()).as("the transaction's key is untouched").isTrue();
        assertThat(notified).as("an empty mutation list notifies nobody").isEmpty();
    }

    @Test
    void witnessOnlyTransaction_namingAnotherLeader_isRefusedWithLeader() {
        var result = transaction(OTHER_LEADER, List.of(guard("committed")), List.of());

        assertThat(result.accepted()).isFalse();
        assertThat(result.refusal().map(KVCommand.Refusal::kind)).isEqualTo(Option.some(RefusalKind.LEADER));
        assertThat(notified).isEmpty();
    }

    @Test
    void witnessOnlyTransaction_withAnUnequalGuard_isRefusedWithWitness() {
        var result = transaction(LEADER, List.of(guard("stale")), List.of());

        assertThat(result.accepted()).isFalse();
        assertThat(result.refusal().map(KVCommand.Refusal::kind)).isEqualTo(Option.some(RefusalKind.WITNESS));
        assertThat(result.refusal().flatMap(KVCommand.Refusal::key)).isEqualTo(Option.some((Object) GUARDED));
        assertThat(notified).isEmpty();
    }

    /// Control: the same guard with a one-mutation re-put is accepted and DOES notify, so the empty-list case above is silent because
    /// it has no mutation, not because the probe is blind.
    @Test
    void control_aOneMutationRePut_raisesANotification() {
        var result = transaction(LEADER,
                                 List.of(guard("committed")),
                                 List.of(new Mutation<>(GUARDED, Option.some("committed"), Option.some("committed"))));

        assertThat(result.accepted()).isTrue();
        assertThat(notified).as("a mutation notifies").isNotEmpty();
    }

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
}
