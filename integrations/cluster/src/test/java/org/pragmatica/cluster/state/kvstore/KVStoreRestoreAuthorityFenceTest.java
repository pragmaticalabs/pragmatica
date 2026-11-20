package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// The fence of the one restore-authority cell (#3303, rows 11-14 and F-HOME): every arm is keyed by
/// [RestoreAuthorityKey], not by the value's marker. Each refusal test carries an in-run control where one exists
/// (an accepted write proving the refusal is the arm, not a blanket rejection).
class KVStoreRestoreAuthorityFenceTest {
    private record Ka() implements StructuredKey, RestoreAuthorityKey {}

    private record PlainKey(String id) implements StructuredKey {}

    /// The production shape (BackupRestoreValue): leader-authorized AND a restore authority.
    private record Marker(String id, boolean admits, List<Option<RestoreAuthority>> seen) implements LeaderAuthorized, RestoreAuthority {
        @Override
        public boolean admitsUnits() {
            return false;
        }

        @Override
        public boolean admitsAfter(Option<RestoreAuthority> committed) {
            seen.add(committed);
            return admits;
        }
    }

    /// A restore authority WITHOUT the leader-authorized marker, so only the key-typed arms can hold it.
    private record Bare(String id, boolean admits) implements RestoreAuthority {
        @Override
        public boolean admitsUnits() {
            return false;
        }

        @Override
        public boolean admitsAfter(Option<RestoreAuthority> committed) {
            return admits;
        }
    }

    private record Plain(String text) {}

    private static final Ka KA = new Ka();
    private static final PlainKey PLAIN_KEY = new PlainKey("elsewhere");
    private static final LeaderValue LEADER = new LeaderValue(new NodeId("core"), 1);

    private Map<StructuredKey, Object> snapshotContent = Map.of();

    private final KVStore<StructuredKey, Object> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @SuppressWarnings("unchecked")
        @Override public <T> T read(ByteBuf buffer) { return (T) snapshotContent; }
    });

    private void apply(KVCommand<StructuredKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    private boolean transaction(String id, KVCommand.Mutation<StructuredKey, Object> mutation) {
        var result = (KVCommand.TransactionResult) store.process(store.createBatch(List.of(new KVCommand.LeaderTransaction<>(mutation.key(),
                                                                                                                               id,
                                                                                                                               LEADER,
                                                                                                                               List.of(),
                                                                                                                               List.of(mutation)))))
                                                        .getFirst();
        return result.accepted();
    }

    private boolean put(String id, StructuredKey key, Option<Object> expected, Object value) {
        return transaction(id, new KVCommand.Mutation<>(key, expected, Option.some(value)));
    }

    private static Marker marker(String id, boolean admits) {
        return new Marker(id, admits, new ArrayList<>());
    }

    private void electLeader() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
    }

    // Row 11: a bare Put at K_A is dropped, whatever the value carries.

    @Test
    void row11_barePutOfBareAuthorityAtKa_isDropped() {
        apply(new KVCommand.Put<>(KA, new Bare("forged", true)));

        assertThat(store.get(KA).isEmpty()).isTrue();
    }

    /// Already held at base by the LeaderAuthorized clause of KVS:502; kept as the shape the production value has.
    @Test
    void row11_barePutOfMarkerAtKa_isDropped() {
        apply(new KVCommand.Put<>(KA, marker("forged", true)));

        assertThat(store.get(KA).isEmpty()).isTrue();
    }

    // Row 12: a transaction Put at K_A is admitted only for a RestoreAuthority value that accepts the committed one.

    @Test
    void row12_transactionPutOfNonAuthorityAtKa_isRefused() {
        electLeader();

        assertThat(put("plain", KA, Option.none(), new Plain("not-an-authority"))).isFalse();
        assertThat(store.get(KA).isEmpty()).isTrue();
    }

    @Test
    void row12_authorityIsAdmittedOnlyWhenItAdmitsTheCommittedValue() {
        electLeader();

        assertThat(put("refused", KA, Option.none(), marker("refused", false))).isFalse();
        assertThat(store.get(KA).isEmpty()).isTrue();

        var admitted = marker("admitted", true);
        assertThat(put("admitted", KA, Option.none(), admitted)).isTrue();
        assertThat(store.get(KA).unwrap()).isEqualTo(admitted);
    }

    @Test
    void row12_admitsAfterSeesTheCommittedValue() {
        electLeader();
        var first = marker("first", true);
        assertThat(put("first", KA, Option.none(), first)).isTrue();
        assertThat(first.seen()).containsExactly(Option.none());

        var second = marker("second", true);
        assertThat(put("second", KA, Option.some(first), second)).isTrue();
        assertThat(second.seen()).containsExactly(Option.some(first));
        assertThat(store.get(KA).unwrap()).isEqualTo(second);
    }

    /// Ruling (3), CTO 2026-11-20: a committed value at K_A that is not a RestoreAuthority admits nothing. Only a
    /// snapshot install (outside the fence) can place one there.
    @Test
    void row12_committedNonAuthorityAtKa_failsClosed() {
        electLeader();
        var foreign = new Plain("installed");
        snapshotContent = Map.of(LeaderKey.INSTANCE, LEADER, KA, foreign);
        assertThat(store.restoreSnapshot(new byte[0]).isSuccess()).isTrue();
        assertThat(store.get(KA).unwrap()).isEqualTo(foreign);

        var authority = marker("over-foreign", true);
        assertThat(put("over-foreign", KA, Option.some(foreign), authority)).isFalse();
        assertThat(authority.seen()).isEmpty();
        assertThat(store.get(KA).unwrap()).isEqualTo(foreign);
    }

    // F-HOME: a RestoreAuthority value lives only at a RestoreAuthorityKey key.

    @Test
    void fHome_authorityAtAPlainKey_isRefused() {
        electLeader();

        assertThat(put("away", PLAIN_KEY, Option.none(), marker("away", true))).isFalse();
        assertThat(store.get(PLAIN_KEY).isEmpty()).isTrue();

        assertThat(put("control", PLAIN_KEY, Option.none(), new Plain("ordinary"))).isTrue();
    }

    // Row 13: an authorized transaction may not delete K_A (F-NODEL).

    @Test
    void row13_transactionDeleteAtKa_isRefused() {
        electLeader();
        var committed = marker("committed", true);
        assertThat(put("commit", KA, Option.none(), committed)).isTrue();

        assertThat(transaction("delete", new KVCommand.Mutation<>(KA, Option.some(committed), Option.none()))).isFalse();
        assertThat(store.get(KA).unwrap()).isEqualTo(committed);
    }

    // Row 14: a bare Remove at K_A is refused, whatever the committed value carries.

    @Test
    void row14_bareRemoveOfBareAuthorityAtKa_isRefused() {
        electLeader();
        var committed = new Bare("committed", true);
        assertThat(put("commit", KA, Option.none(), committed)).isTrue();

        apply(new KVCommand.Remove<>(KA));
        apply(new KVCommand.Remove<>(KA, Option.some(committed)));

        assertThat(store.get(KA).unwrap()).isEqualTo(committed);
    }

    /// Already held at base by the LeaderAuthorized clause of KVS:900; kept as the shape the production value has.
    @Test
    void row14_bareRemoveOfMarkerAtKa_isRefused() {
        electLeader();
        var committed = marker("committed", true);
        assertThat(put("commit", KA, Option.none(), committed)).isTrue();

        apply(new KVCommand.Remove<>(KA));
        apply(new KVCommand.Remove<>(KA, Option.some(committed)));

        assertThat(store.get(KA).unwrap()).isEqualTo(committed);
    }
}
