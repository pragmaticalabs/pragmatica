package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVCommand.Remove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.cluster.state.kvstore.LeaderValue.leaderValue;

/// A [LeaderValue] committed under a key other than the `LeaderKey`. The leader fence used to be keyed on the
/// `LeaderKey`, so there a lower `Put` and a witnessless `Remove` both applied (rc5 at 7b0f3f4a27). Since the leader
/// fence is the generic [EpochBearing] fence it follows the value: both are refused, exactly as under the
/// `LeaderKey`. No production code writes a `LeaderValue` anywhere but the `LeaderKey`; the stricter outcome is
/// the deliberate one.
class KVStoreLeaderValueOffKeyTest {
    private record OwnedKey(String domain) implements StructuredKey {}

    private static final OwnedKey KEY = new OwnedKey("core");
    private static final LeaderValue COMMITTED = leaderValue(NodeId.nodeId("node-a").unwrap(), 5L);

    private KVStore<StructuredKey, Object> store;

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }

    @BeforeEach
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        apply(new Put<>(KEY, COMMITTED));
    }

    private void apply(KVCommand<StructuredKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    @Test
    void lowerLeaderValuePut_underOtherKey_refused() {
        apply(new Put<>(KEY, leaderValue(NodeId.nodeId("node-b").unwrap(), 4L)));

        assertThat(store.get(KEY)).isEqualTo(Option.some(COMMITTED));
        assertThat(store.staleEpochRefusals()).isZero();
    }

    @Test
    void witnesslessRemove_ofLeaderValueUnderOtherKey_refused() {
        apply(new Remove<>(KEY));

        assertThat(store.get(KEY)).isEqualTo(Option.some(COMMITTED));
    }
}
