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

/// Cross-key assignment fence (#1271): a `Put` to an [AssignmentGuarded] key is applied only when the
/// committed authority under its guard key carries the SAME token as the incoming value. The canonical
/// consumer is a consumer-group cursor checkpoint guarded by the group-partition assignment record — a
/// deposed assignee must not move the cursor from the moment the reassignment commits, including
/// BEFORE the new assignee's first checkpoint (the gap a per-key epoch fence leaves open).
///
/// Same stub-codec posture as `KVStoreWatermarkFenceTest`: batches are applied directly.
class KVStoreAssignmentGuardTest {
    private record AssignmentKey(String name) implements StructuredKey {}

    private record Assignment(String token) implements AssignmentTokenBearing {
        @Override
        public Object guardToken() {
            return token;
        }
    }

    private record CursorKey(String name) implements StructuredKey, AssignmentGuarded {
        @Override
        public Object guardKey() {
            return ASSIGNMENT;
        }
    }

    private record Cursor(String token, long offset) implements AssignmentTokenBearing {
        @Override
        public Object guardToken() {
            return token;
        }
    }

    private record PlainCursor(long offset) {}

    private static final AssignmentKey ASSIGNMENT = new AssignmentKey("orders/0/group");
    private static final CursorKey CURSOR = new CursorKey("orders/0/group");

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
        var router = MessageRouter.mutable();

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

    @Test
    void put_acceptsGuardedWrite_whenTokenMatchesCommittedAssignment() {
        put(ASSIGNMENT, new Assignment("node-a@1"));
        put(CURSOR, new Cursor("node-a@1", 10));

        assertThat(stored(CURSOR)).isEqualTo(new Cursor("node-a@1", 10));
    }

    /// The #1271 acceptance race at the applier: the assignment moves to B, and A's checkpoint lands
    /// BEFORE B has written anything. A's commit must be refused — the cursor keeps A's last accepted
    /// value — so B's later resume cannot be overwritten by, or overwrite, a deposed advance.
    @Test
    void put_rejectsDeposedAssigneeWrite_beforeSuccessorsFirstCommit() {
        put(ASSIGNMENT, new Assignment("node-a@1"));
        put(CURSOR, new Cursor("node-a@1", 10));
        put(ASSIGNMENT, new Assignment("node-b@2"));

        put(CURSOR, new Cursor("node-a@1", 50));

        assertThat(stored(CURSOR)).isEqualTo(new Cursor("node-a@1", 10));
    }

    @Test
    void put_acceptsSuccessorWrite_afterReassignment() {
        put(ASSIGNMENT, new Assignment("node-a@1"));
        put(CURSOR, new Cursor("node-a@1", 10));
        put(ASSIGNMENT, new Assignment("node-b@2"));

        put(CURSOR, new Cursor("node-b@2", 10));

        assertThat(stored(CURSOR)).isEqualTo(new Cursor("node-b@2", 10));
    }

    /// Within its own assignment the assignee may REWIND — the fence is about who writes, never about
    /// the direction of the offset (the #1239 ruling: rewinding is legitimate at the store).
    @Test
    void put_acceptsRewind_bySameAssignee() {
        put(ASSIGNMENT, new Assignment("node-a@1"));
        put(CURSOR, new Cursor("node-a@1", 50));
        put(CURSOR, new Cursor("node-a@1", 20));

        assertThat(stored(CURSOR)).isEqualTo(new Cursor("node-a@1", 20));
    }

    /// No committed assignment means no write belongs to anyone — refusing on absence is the point.
    @Test
    void put_rejectsGuardedWrite_whenNoAssignmentIsCommitted() {
        put(CURSOR, new Cursor("node-a@1", 10));

        assertThat(stored(CURSOR)).isNull();
    }

    /// A guarded key accepts only token-bearing writes: an unfenced value would bypass the guard.
    @Test
    void put_rejectsTokenlessWrite_toGuardedKey() {
        put(ASSIGNMENT, new Assignment("node-a@1"));
        put(CURSOR, new PlainCursor(10));

        assertThat(stored(CURSOR)).isNull();
    }

    @Test
    void put_rejectedWrite_emitsNoNotification() {
        put(ASSIGNMENT, new Assignment("node-b@2"));
        putNotifications.clear();
        put(CURSOR, new Cursor("node-a@1", 50));

        assertThat(putNotifications).isEmpty();
    }

    /// Determinism: the decision is a pure function of committed storage and the incoming value, so
    /// two replicas applying the same log reach the same state.
    @Test
    void process_isDeterministic_acrossReplicasApplyingTheSameLog() {
        var log = List.<Put<StructuredKey, Object>>of(new Put<>(ASSIGNMENT, new Assignment("node-a@1")),
                                                      new Put<>(CURSOR, new Cursor("node-a@1", 10)),
                                                      new Put<>(ASSIGNMENT, new Assignment("node-b@2")),
                                                      new Put<>(CURSOR, new Cursor("node-a@1", 50)),
                                                      new Put<>(CURSOR, new Cursor("node-b@2", 12)));
        var replica = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());

        log.forEach(command -> put(command.key(), command.value()));
        log.forEach(command -> replica.process(replica.createBatch(List.of(command))));

        assertThat(replica.get(CURSOR).or((Object) null)).isEqualTo(stored(CURSOR))
                                                          .isEqualTo(new Cursor("node-b@2", 12));
    }
}
