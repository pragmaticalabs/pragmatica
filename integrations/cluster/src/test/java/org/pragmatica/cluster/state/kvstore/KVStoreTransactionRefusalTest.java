package org.pragmatica.cluster.state.kvstore;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.cluster.state.kvstore.KVCommand.Mutation;
import org.pragmatica.cluster.state.kvstore.KVCommand.ReadWitness;
import org.pragmatica.cluster.state.kvstore.KVCommand.RefusalKind;
import org.pragmatica.cluster.state.kvstore.KVCommand.TransactionResult;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The refusal a [TransactionResult] carries (#3303 RA-2): the kind is the FIRST failing check in the applier's order
/// LEADER, WITNESS, EXPECTED, DISTINCT, ARM, and names the key the check failed on. Each kind is shown alone, then every
/// adjacent pair is shown together so the order itself is pinned. A refused transaction applies nothing, whatever its kind.
class KVStoreTransactionRefusalTest {
    private record Key(String id) implements StructuredKey {}

    private record Ka() implements StructuredKey, RestoreAuthorityKey {}

    private static final Key GUARDED = new Key("guarded");
    private static final Key OTHER_GUARDED = new Key("other-guarded");
    private static final Key TARGET = new Key("target");
    private static final Key DUPLICATED = new Key("duplicated");
    private static final Ka KA = new Ka();
    private static final LeaderValue LEADER = new LeaderValue(new NodeId("core"), 1);
    private static final LeaderValue STALE_LEADER = new LeaderValue(new NodeId("deposed"), 0);

    private final KVStore<StructuredKey, Object> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });

    private void apply(KVCommand<StructuredKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    private void electLeaderAndCommitGuard() {
        apply(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER));
        apply(new KVCommand.Put<>(GUARDED, "committed"));
        apply(new KVCommand.Put<>(OTHER_GUARDED, "committed"));
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

    private static ReadWitness<StructuredKey> currentGuard(StructuredKey key) {
        return new ReadWitness<>(key, Option.some("committed"));
    }

    private static ReadWitness<StructuredKey> staleGuard(StructuredKey key) {
        return new ReadWitness<>(key, Option.some("stale"));
    }

    private static Mutation<StructuredKey, Object> create(StructuredKey key, Object value) {
        return new Mutation<>(key, Option.none(), Option.some(value));
    }

    private static Mutation<StructuredKey, Object> staleExpected(StructuredKey key) {
        return new Mutation<>(key, Option.some("never-committed"), Option.some("value"));
    }

    /// RA-1's key-typed arm: a non-authority value written to the restore-authority cell.
    private static Mutation<StructuredKey, Object> raOneArm() {
        return create(KA, "not-an-authority");
    }

    private void assertRefused(TransactionResult result, RefusalKind kind, Option<Object> key) {
        assertThat(result.refusal().map(KVCommand.Refusal::kind)).as("refusal kind").isEqualTo(Option.some(kind));
        assertThat(result.refusal().flatMap(KVCommand.Refusal::key)).as("refusal key").isEqualTo(key);
        assertThat(result.accepted()).as("accepted == refusal.isEmpty()").isEqualTo(result.refusal().isEmpty());
        assertThat(result.accepted()).as("refused").isFalse();
        assertNothingApplied();
    }

    private void assertNothingApplied() {
        assertThat(store.get(TARGET).isEmpty()).as("TARGET untouched").isTrue();
        assertThat(store.get(DUPLICATED).isEmpty()).as("DUPLICATED untouched").isTrue();
        assertThat(store.get(KA).isEmpty()).as("KA untouched").isTrue();
        assertThat(store.get(GUARDED)).as("GUARDED untouched").isEqualTo(Option.some("committed"));
        assertThat(store.get(LeaderKey.INSTANCE)).as("leader untouched").isEqualTo(Option.some(LEADER));
    }

    // Each kind alone.

    @Test
    void leader_aloneIsLeader_withoutKey() {
        electLeaderAndCommitGuard();

        assertRefused(transaction(STALE_LEADER, List.of(), List.of(create(TARGET, "value"))), RefusalKind.LEADER, Option.none());
    }

    @Test
    void witness_aloneIsWitness_namingTheFirstUnequalGuard() {
        electLeaderAndCommitGuard();

        assertRefused(transaction(LEADER,
                                  List.of(currentGuard(GUARDED), staleGuard(OTHER_GUARDED), staleGuard(GUARDED)),
                                  List.of(create(TARGET, "value"))),
                      RefusalKind.WITNESS,
                      Option.some(OTHER_GUARDED));
    }

    @Test
    void expected_aloneIsExpected_namingItsKey() {
        electLeaderAndCommitGuard();

        assertRefused(transaction(LEADER, List.of(currentGuard(GUARDED)), List.of(create(DUPLICATED, "value"), staleExpected(TARGET))),
                      RefusalKind.EXPECTED,
                      Option.some(TARGET));
    }

    @Test
    void distinct_aloneIsDistinct_namingTheRepeatedKey() {
        electLeaderAndCommitGuard();

        assertRefused(transaction(LEADER, List.of(), List.of(create(TARGET, "a"), create(DUPLICATED, "b"), create(DUPLICATED, "c"))),
                      RefusalKind.DISTINCT,
                      Option.some(DUPLICATED));
    }

    @Test
    void arm_raOneArmAloneIsArm_namingItsKey() {
        electLeaderAndCommitGuard();

        assertRefused(transaction(LEADER, List.of(), List.of(create(TARGET, "value"), raOneArm())), RefusalKind.ARM, Option.some(KA));
    }

    @Test
    void arm_leaderKeyMutationIsArm_namingTheLeaderKey() {
        electLeaderAndCommitGuard();

        assertRefused(transaction(LEADER, List.of(), List.of(new Mutation<>(LeaderKey.INSTANCE, Option.some(LEADER), Option.none()))),
                      RefusalKind.ARM,
                      Option.some(LeaderKey.INSTANCE));
    }

    @Test
    void arm_removalArmIsArm_namingItsKey() {
        electLeaderAndCommitGuard();

        assertRefused(transaction(LEADER, List.of(), List.of(new Mutation<>(KA, Option.none(), Option.none()))),
                      RefusalKind.ARM,
                      Option.some(KA));
    }

    // Order: each pair reports the earlier check.

    @Test
    void order_staleLeaderAndDuplicate_isLeader() {
        electLeaderAndCommitGuard();

        assertRefused(transaction(STALE_LEADER, List.of(), List.of(create(DUPLICATED, "a"), create(DUPLICATED, "b"))),
                      RefusalKind.LEADER,
                      Option.none());
    }

    /// T-RA5's half: a stale guard is reported before RA-1's arm.
    @Test
    void order_staleGuardAndRaOneArm_isWitness() {
        electLeaderAndCommitGuard();

        assertRefused(transaction(LEADER, List.of(staleGuard(GUARDED)), List.of(raOneArm())), RefusalKind.WITNESS, Option.some(GUARDED));
    }

    /// EXPECTED precedes DISTINCT: the reverse of the order the accept decision used to evaluate them in.
    @Test
    void order_staleExpectedAndDuplicate_isExpected() {
        electLeaderAndCommitGuard();

        assertRefused(transaction(LEADER,
                                  List.of(),
                                  List.of(create(DUPLICATED, "a"), create(DUPLICATED, "b"), staleExpected(TARGET))),
                      RefusalKind.EXPECTED,
                      Option.some(TARGET));
    }

    @Test
    void order_duplicateAndArm_isDistinct() {
        electLeaderAndCommitGuard();

        assertRefused(transaction(LEADER, List.of(), List.of(raOneArm(), create(DUPLICATED, "a"), create(DUPLICATED, "b"))),
                      RefusalKind.DISTINCT,
                      Option.some(DUPLICATED));
    }

    // Accepted.

    @Test
    void accepted_carriesNoRefusal_andApplies() {
        electLeaderAndCommitGuard();

        var result = transaction(LEADER, List.of(currentGuard(GUARDED)), List.of(create(TARGET, "value")));

        assertThat(result.refusal().isEmpty()).as("no refusal").isTrue();
        assertThat(result.accepted()).as("accepted == refusal.isEmpty()").isTrue();
        assertThat(store.get(TARGET)).isEqualTo(Option.some("value"));
    }

    @Test
    void twoArgumentResult_carriesNoRefusal() {
        assertThat(new TransactionResult("accepted", true).refusal().isEmpty()).isTrue();
        assertThat(new TransactionResult("refused", false).refusal().isEmpty()).isTrue();
    }
}
