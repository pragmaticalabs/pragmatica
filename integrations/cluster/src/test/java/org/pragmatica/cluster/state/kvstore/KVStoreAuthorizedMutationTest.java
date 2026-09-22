package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KVStoreAuthorizedMutationTest {
    private record Key(String id) implements StructuredKey {}
    private record Value(String text) implements LeaderAuthorized {}
    private static final Key KEY = new Key("authority");
    private static final LeaderValue LEADER = new LeaderValue(new NodeId("core"), 1);
    private final KVStore<StructuredKey, Object> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });

    private void apply(KVCommand<StructuredKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    private KVCommand.TransactionResult transaction(String id, KVCommand.Mutation<StructuredKey, Object> mutation) {
        return (KVCommand.TransactionResult) store.process(store.createBatch(List.of(new KVCommand.LeaderTransaction<>(KEY, id, LEADER, List.of(), List.of(mutation)))))
                                                  .getFirst();
    }

    @Test
    void sequentialClaims_onlyFirstSucceeds() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        var first = new Value("a");
        apply(new KVCommand.LeaderTransaction<>(KEY, "first", LEADER, List.of(), List.of(new KVCommand.Mutation<>(KEY, Option.none(), Option.some(first)))));
        apply(new KVCommand.LeaderTransaction<>(KEY, "claim", LEADER, java.util.List.of(), List.of(new KVCommand.Mutation<>(KEY, Option.none(), Option.some(new Value("b"))))));
        assertThat(store.get(KEY).unwrap()).isEqualTo(first);
    }

    @Test
    void staleLeaderCannotApplyEvenWithMatchingValue() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, new LeaderValue(new NodeId("other"), 2)));
        apply(new KVCommand.LeaderTransaction<>(KEY, "claim", LEADER, java.util.List.of(), List.of(new KVCommand.Mutation<>(KEY, Option.none(), Option.some(new Value("a"))))));
        assertThat(store.get(KEY).isEmpty()).isTrue();
    }

    @Test
    void barePutAndRemoveCannotBypassAuthority() {
        apply(new KVCommand.Put<>(KEY, new Value("forged")));
        assertThat(store.get(KEY).isEmpty()).isTrue();
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        var value = new Value("accepted");
        apply(new KVCommand.LeaderTransaction<>(KEY, "claim", LEADER, java.util.List.of(), List.of(new KVCommand.Mutation<>(KEY, Option.none(), Option.some(value)))));
        apply(new KVCommand.Put<>(KEY, "unfenced"));
        apply(new KVCommand.Remove<>(KEY, Option.some(value)));
        assertThat(store.get(KEY).unwrap()).isEqualTo(value);
    }

    /// An authorized transaction may refresh the value but may NOT replace it with an unmarked one:
    /// a downgrade would leave the key open to a bare `Put` and a witnessless `Remove` forever. The
    /// accepted refresh is the in-run control — it proves the refusal is the downgrade arm and not a
    /// blanket rejection of the second transaction.
    @Test
    void authorizedValueCannotBeDowngradedToAnUnmarkedOne() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        var claimed = new Value("accepted");
        assertThat(transaction("claim", new KVCommand.Mutation<>(KEY, Option.none(), Option.some(claimed))).accepted()).isTrue();

        var refreshed = new Value("still-authorized");
        assertThat(transaction("refresh", new KVCommand.Mutation<>(KEY, Option.some(claimed), Option.some(refreshed))).accepted()).isTrue();
        assertThat(store.get(KEY).unwrap()).isEqualTo(refreshed);

        assertThat(transaction("downgrade", new KVCommand.Mutation<>(KEY, Option.some(refreshed), Option.some("plain"))).accepted()).isFalse();
        assertThat(store.get(KEY).unwrap()).isEqualTo(refreshed);

        apply(new KVCommand.Put<>(KEY, "forged"));
        apply(new KVCommand.Remove<>(KEY));
        assertThat(store.get(KEY).unwrap()).isEqualTo(refreshed);
    }

    /// Deletion remains the de-authorization path: it leaves no unprotected key behind, so it is
    /// admitted where the in-place downgrade above is refused.
    @Test
    void authorizedValueCanStillBeRemovedByItsLeader() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        var claimed = new Value("accepted");
        assertThat(transaction("claim", new KVCommand.Mutation<>(KEY, Option.none(), Option.some(claimed))).accepted()).isTrue();
        assertThat(transaction("release", new KVCommand.Mutation<>(KEY, Option.some(claimed), Option.none())).accepted()).isTrue();
        assertThat(store.get(KEY).isEmpty()).isTrue();
    }
}
