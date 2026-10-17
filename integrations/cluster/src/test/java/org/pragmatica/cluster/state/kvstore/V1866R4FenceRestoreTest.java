package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// v1866 round 4, attack 1: a node whose state came from a snapshot install refuses a competing life exactly as a node
/// that applied the same history live, on the plain Put and the LeaderTransaction paths, so the two never diverge.
class V1866R4FenceRestoreTest {
    private record Key(String id) implements StructuredKey {}

    private record Life(long incarnation) implements IncarnationFenced {
        @Override
        public long fenceIncarnation() {
            return incarnation;
        }
    }

    private static final Key STREAM = new Key("orders");
    private static final LeaderValue LEADER = new LeaderValue(new NodeId("core"), 1);

    @Test
    void restoredNode_refusesACompetingLife_likeALiveNode_andTheyDoNotDiverge() {
        var live = store(Map.of());
        live.processCommitted(batch(live, new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER)), 1);
        live.processCommitted(batch(live, new KVCommand.Put<>(STREAM, new Life(11))), 2);

        var snapshotState = new HashMap<StructuredKey, Object>();
        snapshotState.put(LeaderKey.INSTANCE, LEADER);
        snapshotState.put(STREAM, new Life(11));
        var restored = store(snapshotState);
        assertThat(restored.restoreCommittedSnapshot(new byte[]{1}, 2).isSuccess()).as("fixture: snapshot installed").isTrue();
        assertThat(restored.get(STREAM).or((Object) null)).as("fixture: restored life a").isEqualTo(new Life(11));

        for (var node : List.of(live, restored)) {
            node.processCommitted(batch(node, new KVCommand.Put<>(STREAM, new Life(22))), 3);
            node.processCommitted(batch(node,
                                        new KVCommand.LeaderTransaction<>(STREAM,
                                                                          "reincarnate",
                                                                          LEADER,
                                                                          List.of(),
                                                                          List.of(new KVCommand.Mutation<>(STREAM,
                                                                                                           Option.some(new Life(11)),
                                                                                                           Option.some(new Life(22)))))),
                                  4);
        }

        assertThat(live.get(STREAM).or((Object) null)).as("live node keeps life a").isEqualTo(new Life(11));
        assertThat(restored.get(STREAM).or((Object) null)).as("restored node keeps life a, no divergence").isEqualTo(new Life(11));
    }

    @SuppressWarnings("unchecked")
    private static KVStore<StructuredKey, Object> store(Map<StructuredKey, Object> snapshot) {
        return new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return (T) new HashMap<>(snapshot);
            }
        });
    }

    private static org.pragmatica.consensus.StateMachine.Batch<KVCommand<StructuredKey>> batch(KVStore<StructuredKey, Object> store, KVCommand<StructuredKey> command) {
        return store.createBatch(List.of(command));
    }
}
