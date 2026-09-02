// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.entity;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource;
import org.pragmatica.aether.dht.CommittedPartitionOwnerSource.CommittedOwner;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Deadline;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// Owner-forwarding for durable entity writes (#596).
///
/// The property under test is not "a message was sent" — it is that a non-owner STOPS REFUSING and the
/// caller gets the owner's post-mutation state, while every way this could go wrong still fails closed.
class EntityOwnerForwardTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITIONS = 8;
    private static final NodeId SELF = new NodeId("self-node");
    private static final NodeId OTHER = new NodeId("other-node");
    private static final Duration TIMER_DELAY = Duration.ofSeconds(10);

    /// The handle the cancel tests present. A literal, so what arrives at the transport is compared against
    /// a value production never produced.
    private static final DurableEntity.TimerToken CANCELLED_TOKEN = DurableEntity.TimerToken.timerToken("tok-7");

    private RecordingSubstrate substrate;
    private EntityPartitionArc arc;
    private RecordingForward transport;

    @BeforeEach
    void setUp() {
        substrate = new RecordingSubstrate();
        arc = EntityPartitionArc.entityPartitionArc(KEYSPACE, PARTITIONS);
        transport = new RecordingForward();
    }

    @Test
    void update_forwardsToTheCommittedOwner_whenSelfIsNotOwner() {
        var entity = entityAs(SELF, OTHER, Option.some(transport));

        var result = entity.update("k1", new IntOp.Add(7)).await();

        assertThat(result.isSuccess()).isTrue();

        int state = result.fold(cause -> fail(cause.message()), value -> value);

        assertThat(state).isEqualTo(107);
        assertThat(transport.calls).hasSize(1);
        assertThat(transport.calls.getFirst().owner()).isEqualTo(OTHER);
        assertThat(transport.calls.getFirst().keyspace()).isEqualTo(KEYSPACE);
    }

    /// The command must arrive as DATA the owner can reconstruct — that is the whole reason `Mutator`
    /// replaced `Fn1`. Asserting on the decoded payload rather than "something was sent".
    @Test
    void update_sendsTheEncodedKeyAndCommand_soTheOwnerCanReconstructBoth() {
        entityAs(SELF, OTHER, Option.some(transport)).update("k9", new IntOp.Add(3)).await();

        var call = transport.calls.getFirst();

        assertThat(new String(call.key(), StandardCharsets.UTF_8)).isEqualTo("k9");
        assertThat(new String(call.command(), StandardCharsets.UTF_8)).isEqualTo("Add:3");
    }

    /// Unwired must be INERT, not permissive: with no transport a non-owner refuses exactly as it did
    /// before forwarding existed. A half-wired deployment must not silently start writing elsewhere.
    @Test
    void update_refusesAsBefore_whenNoTransportIsWired() {
        var result = entityAs(SELF, OTHER, Option.none()).update("k1", new IntOp.Add(7)).await();

        assertThat(refusalOf(result)).contains("reached a non-owner");
        assertThat(transport.calls).isEmpty();
    }

    /// THE safety property. A failed forward must surface as a failure and must NOT fall back to
    /// applying the command here — that would put a second writer on the key, which is precisely the
    /// split-brain the ownership fence exists to prevent.
    @Test
    void update_failsWithoutApplyingLocally_whenTheForwardFails() {
        transport.failWith("owner unreachable");

        var entity = entityAs(SELF, OTHER, Option.some(transport));
        var result = entity.update("k1", new IntOp.Add(7)).await();

        assertThat(result.isFailure()).isTrue();
        assertThat(substrate.appended).isEmpty();
    }

    /// A node that IS the owner never forwards, even with a transport wired — otherwise a stale
    /// ownership view could bounce a command between two nodes indefinitely.
    @Test
    void update_appliesLocally_whenSelfIsTheCommittedOwner() {
        entityAs(SELF, SELF, Option.some(transport)).create("k1", 1).await();
        var result = entityAs(SELF, SELF, Option.some(transport)).update("k1", new IntOp.Add(4)).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(transport.calls).isEmpty();
    }

    /// The receiving side still runs the owner's OWN admission. A command forwarded to a node that is
    /// not (or is no longer) the owner is refused, so the hop cannot land a write the fence would block.
    @Test
    void applyForwarded_refuses_whenTheReceiverIsNotTheOwner() {
        var receiver = fencedEntityAs(SELF, OTHER, Option.none());

        var result = receiver.applyForwarded("k1".getBytes(StandardCharsets.UTF_8),
                                             "Add:5".getBytes(StandardCharsets.UTF_8))
                             .await();

        assertThat(refusalOf(result))
            .as("must be refused BY THE FENCE — on a fresh substrate 'key not found' fails too, so a "
                + "bare isFailure() stays green with admission removed entirely")
            .contains("reached a non-owner");
        assertThat(substrate.appended).isEmpty();
    }

    /// #596 was filed on CREATES failing (4 of 40 acked), and `create` carries an initial STATE rather
    /// than a `Mutator` — so it needs its own hop and cannot ride the update one.
    @Test
    void create_forwardsToTheCommittedOwner_whenSelfIsNotOwner() {
        var result = entityAs(SELF, OTHER, Option.some(transport)).create("k1", 5).await();

        assertThat(result.isSuccess()).isTrue();

        int state = result.fold(cause -> fail(cause.message()), value -> value);

        assertThat(state).isEqualTo(107);
        assertThat(transport.calls).hasSize(1);
        assertThat(transport.calls.getFirst().op()).isEqualTo("create");
        assertThat(transport.calls.getFirst().owner()).isEqualTo(OTHER);
    }

    @Test
    void create_sendsTheEncodedKeyAndInitialState_soTheOwnerCanReconstructBoth() {
        entityAs(SELF, OTHER, Option.some(transport)).create("k9", 42).await();

        var call = transport.calls.getFirst();

        assertThat(new String(call.key(), StandardCharsets.UTF_8)).isEqualTo("k9");
        assertThat(new String(call.command(), StandardCharsets.UTF_8)).isEqualTo("42");
    }

    @Test
    void create_refusesAsBefore_whenNoTransportIsWired() {
        var result = entityAs(SELF, OTHER, Option.none()).create("k1", 5).await();

        assertThat(refusalOf(result))
            .as("unwired must refuse by ADMISSION, exactly as before forwarding existed — not by "
                + "reporting an absent transport, which is a different (and misleading) failure")
            .contains("reached a non-owner");
        assertThat(transport.calls).isEmpty();
    }

    /// The same safety property the update path has: a failed forward must not fall back to creating
    /// the key here, which would put a second writer on it.
    @Test
    void create_failsWithoutApplyingLocally_whenTheForwardFails() {
        transport.failWith("owner unreachable");

        var result = entityAs(SELF, OTHER, Option.some(transport)).create("k1", 5).await();

        assertThat(result.isFailure()).isTrue();
        assertThat(substrate.appended).isEmpty();
    }

    @Test
    void create_appliesLocally_whenSelfIsTheCommittedOwner() {
        var result = entityAs(SELF, SELF, Option.some(transport)).create("k1", 5).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void delete_forwardsToTheCommittedOwner_whenSelfIsNotOwner() {
        var result = entityAs(SELF, OTHER, Option.some(transport)).delete("k1").await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(transport.calls).hasSize(1);
        assertThat(transport.calls.getFirst().op()).isEqualTo("delete");
        assertThat(transport.calls.getFirst().owner()).isEqualTo(OTHER);
        assertThat(new String(transport.calls.getFirst().key(), StandardCharsets.UTF_8)).isEqualTo("k1");
    }

    @Test
    void delete_refusesAsBefore_whenNoTransportIsWired() {
        var result = entityAs(SELF, OTHER, Option.none()).delete("k1").await();

        assertThat(refusalOf(result)).contains("reached a non-owner");
        assertThat(transport.calls).isEmpty();
    }

    @Test
    void delete_failsWithoutRemovingLocally_whenTheForwardFails() {
        transport.failWith("owner unreachable");

        var result = entityAs(SELF, OTHER, Option.some(transport)).delete("k1").await();

        assertThat(result.isFailure()).isTrue();
        assertThat(substrate.appended).isEmpty();
    }

    @Test
    void createForwarded_refuses_whenTheReceiverIsNotTheOwner() {
        var receiver = fencedEntityAs(SELF, OTHER, Option.none());

        var result = receiver.createForwarded("k1".getBytes(StandardCharsets.UTF_8),
                                              "5".getBytes(StandardCharsets.UTF_8))
                             .await();

        assertThat(refusalOf(result)).contains("reached a non-owner");
        assertThat(substrate.appended).isEmpty();
    }

    @Test
    void deleteForwarded_refuses_whenTheReceiverIsNotTheOwner() {
        var receiver = fencedEntityAs(SELF, OTHER, Option.none());

        var result = receiver.deleteForwarded("k1".getBytes(StandardCharsets.UTF_8)).await();

        assertThat(refusalOf(result)).contains("reached a non-owner");
        assertThat(substrate.appended).isEmpty();
    }

    /// A delete has no post-state, so the owner answers EMPTY bytes and the sender discards them. If this
    /// ever returned an encoded state the sender would try to decode it as an `S` — and an empty payload
    /// decodes to a NumberFormatException here, turning a successful delete into a failure.
    @Test
    void deleteForwarded_answersEmptyBytes_soTheSenderNeverDecodesAState() {
        var owner = fencedEntityAs(SELF, SELF, Option.none());

        owner.create("k1", 5).await();

        var result = owner.deleteForwarded("k1".getBytes(StandardCharsets.UTF_8)).await();

        assertThat(result.isSuccess()).isTrue();

        byte[] answer = result.fold(cause -> fail(cause.message()), value -> value);

        assertThat(answer).isEmpty();
    }

    /// The failure's own message. A bare `isFailure()` cannot tell an admission refusal from an
    /// absent-transport error, and those are different bugs.
    private static <T> String refusalOf(Result<T> result) {
        return result.fold(Cause::message, value -> "unexpectedly succeeded with " + value);
    }

    // --- fixtures ---

    private DurableEntity<String, Integer, IntOp> entityAs(NodeId self,
                                                           NodeId owner,
                                                           Option<EntityOwnerForward> forward) {
        return fencedEntityAs(self, owner, forward);
    }

    private PartitionFencedDurableEntity<String, Integer, IntOp> fencedEntityAs(NodeId self,
                                                                                NodeId owner,
                                                                                Option<EntityOwnerForward> forward) {
        var entity = (PartitionFencedDurableEntity<String, Integer, IntOp>)
            PartitionFencedDurableEntity.<String, Integer, IntOp> partitionFencedDurableEntity(KEYSPACE,
                                                                                               substrate,
                                                                                               arc,
                                                                                               new TestSerializer(),
                                                                                               new TestDeserializer(),
                                                                                               self,
                                                                                               fixedOwner(owner),
                                                                                               Option.none(),
                                                                                               Option.some(noOpBarrier()));

        forward.onPresent(entity::withOwnerForward);

        return entity;
    }

    private static EntityLinearizableBarrier noOpBarrier() {
        return (_, _) -> Promise.success(Unit.unit());
    }

    private static CommittedPartitionOwnerSource fixedOwner(NodeId owner) {
        return (_, _) -> Option.some(new CommittedOwner(owner, Epoch.ZERO));
    }

    /// `op` distinguishes which operation crossed the seam — without it a test asserting "something was
    /// forwarded" would pass when the wrong operation was sent.
    /// The ambient request budget must survive the [PerKeySerialExecutor] hop: it is captured at the
    /// public API on the caller's thread and re-bound inside the queued task — a ScopedValue does not
    /// cross threads by itself, and an unbounded read at the forward would hand it the full configured
    /// timeout regardless of the client's deadline.
    @Test
    void update_underAmbientDeadline_forwardObservesTheBoundedBudget() {
        var entity = entityAs(SELF, OTHER, Option.some(transport));

        var result = Deadline.runWith(Deadline.fromWireMillis(5_000),
                                      () -> entity.update("k1", new IntOp.Add(7)))
                             .await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(transport.boundedAtCall)
            .as("the forward must see the caller's bounded budget across the executor hop")
            .containsExactly(true);
    }

    @Test
    void update_withoutAmbientDeadline_forwardObservesUnbounded() {
        entityAs(SELF, OTHER, Option.some(transport)).update("k1", new IntOp.Add(7)).await();

        assertThat(transport.boundedAtCall)
            .as("no caller budget -> the forward keeps its full configured timeout")
            .containsExactly(false);
    }

    /// The wire flattens causes to strings; the typed refusal must be RECONSTRUCTED from the
    /// carrier's failureType, or a forwarded duplicate-create reads as an unexplained generic
    /// failure to every consumer matching on the cause type (02w counts acked creates that way).
    @Test
    void create_ownerRefusesAsAlreadyExists_surfacesTheTypedCauseNotTheCarrier() {
        transport.refuseWith(new EntityOwnerForward.ForwardRefused("EntityAlreadyExists", "entity already exists: k1"));

        var result = entityAs(SELF, OTHER, Option.some(transport)).create("k1", 100).await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause)
            .as("the sender must see the owner's TYPED refusal, not a string-flattened carrier")
            .isInstanceOf(EntityError.EntityAlreadyExists.class));
    }

    /// An unknown failureType keeps the carrier — its message names the owner's reason verbatim,
    /// and minting a wrong typed cause would be worse than a generic one.
    @Test
    void create_ownerRefusesWithUnknownType_keepsTheCarrierCause() {
        transport.refuseWith(new EntityOwnerForward.ForwardRefused("SomethingNovel", "owner said no"));

        var result = entityAs(SELF, OTHER, Option.some(transport)).create("k1", 100).await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(EntityOwnerForward.ForwardRefused.class);
            assertThat(cause.message()).contains("owner said no");
        });
    }

    // === #345 I4 timer schedule: the caller-minted token across the hop ===

    /// The token the caller minted is what crosses the seam and what comes back. A hop that minted its own
    /// would hand the caller a handle to a timer it did not plant — and, when the answer is lost, no handle
    /// at all for a timer that WAS planted, since cancel takes a token and there is no cancel-by-key verb.
    ///
    /// Driven through the token-taking overload with a LITERAL token, and asserted against that literal
    /// rather than against the answered one. The transport fake echoes what it is handed, so comparing the
    /// arrived token to the RESULT would hold even if the hop replaced the caller's token with one of its
    /// own — the fake would echo the replacement and both sides would agree. Only a value fixed outside the
    /// production path can tell those apart.
    @Test
    void scheduleTimer_sendsTheCallerMintedToken_andAnswersThatSameToken() {
        var minted = DurableEntity.TimerToken.timerToken("caller-minted-token");

        var result = entityAs(SELF, OTHER, Option.some(transport)).scheduleTimer("k1", TIMER_DELAY, new IntOp.Add(7), minted)
                                                                  .await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(transport.arrivedToken)
            .as("the owner must be asked to apply the token the CALLER minted, not one minted at the hop")
            .isEqualTo("caller-minted-token");

        String token = result.fold(cause -> fail(cause.message()), value -> value.value());

        assertThat(token).as("and the caller gets its own token back as the handle").isEqualTo("caller-minted-token");
    }

    /// The echo is CHECKED, not trusted. An owner answering a different token would leave the caller
    /// holding a handle to nothing while a durable timer runs under a name it never saw — the very defect
    /// caller-side minting removes, reappearing one layer down. So the mismatch fails loudly, naming both.
    @Test
    void scheduleTimer_failsWithTokenMismatch_whenTheOwnerEchoesADifferentToken() {
        transport.echoedToken = "a-token-the-caller-never-minted";

        var result = entityAs(SELF, OTHER, Option.some(transport)).scheduleTimer("k1", TIMER_DELAY, new IntOp.Add(7))
                                                                  .await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> {
            assertThat(cause).isInstanceOf(EntityError.TimerTokenMismatch.class);
            assertThat(cause.message()).contains("a-token-the-caller-never-minted");
        });
    }

    // === #345 I4 timer cancel: the same hop, in the other direction ===

    /// Cancel forwards on the same terms schedule does (#596): a non-owner relays to the committed owner
    /// instead of refusing. The TOKEN is what crosses — there is no cancel-by-key verb, so a hop that
    /// dropped or replaced it would cancel nothing while answering success.
    @Test
    void cancelTimer_forwardsToTheCommittedOwner_whenSelfIsNotOwner() {
        var result = entityAs(SELF, OTHER, Option.some(transport)).cancelTimer("k1", CANCELLED_TOKEN).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(transport.calls).hasSize(1);
        assertThat(transport.calls.getFirst().op()).isEqualTo("cancelTimer");
        assertThat(transport.calls.getFirst().owner()).isEqualTo(OTHER);
        assertThat(new String(transport.calls.getFirst().key(), StandardCharsets.UTF_8)).isEqualTo("k1");
        assertThat(new String(transport.calls.getFirst().command(), StandardCharsets.UTF_8))
            .as("the token names WHICH timer to cancel — a hop that lost it would cancel nothing and say so"
                + " with a success")
            .isEqualTo("tok-7");
    }

    /// Unwired is INERT for cancel too: no transport means the honest local admission refusal, not a
    /// silently-successful no-op.
    @Test
    void cancelTimer_refusesAsBefore_whenNoTransportIsWired() {
        var result = entityAs(SELF, OTHER, Option.none()).cancelTimer("k1", CANCELLED_TOKEN).await();

        assertThat(refusalOf(result)).contains("reached a non-owner");
        assertThat(transport.calls).isEmpty();
    }

    /// Cancel is idempotent ON THE OWNER — a token that names nothing pending is success with no record
    /// appended — and that is exactly why a failed forward must not fall back to the local path. This
    /// node's fold holds no timer for the key, so a fallback would find nothing pending, answer SUCCESS,
    /// and leave the caller believing a timer still armed on the owner had been cancelled.
    @Test
    void cancelTimer_failsWithoutFallingBackToTheLocalIdempotentSuccess_whenTheForwardFails() {
        transport.failWith("owner unreachable");

        var result = entityAs(SELF, OTHER, Option.some(transport)).cancelTimer("k1", CANCELLED_TOKEN).await();

        assertThat(result.isFailure())
            .as("the local path would answer success here, which is why silence about a failed forward is"
                + " worse for cancel than for any other verb")
            .isTrue();
        assertThat(substrate.appended).isEmpty();
    }

    // === #596 read half: BOUNDED_STALE ===

    /// A read on a node that HOLDS the partition is served locally — owner or NOT. Serving replicas
    /// locally is BOUNDED_STALE's whole contract (the ready gates bound staleness in offsets);
    /// forwarding them would turn every replica read into a hop and the owner into a read bottleneck.
    @Test
    void boundedStaleGet_servesLocally_whenHoldingThePartition_evenAsNonOwner() {
        entityAs(SELF, SELF, Option.some(transport)).create("k1", 41).await();

        var holdingNonOwner = entityAs(SELF, OTHER, Option.some(transport));
        var result = holdingNonOwner.get("k1", ReadConsistency.BOUNDED_STALE).await();

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(state -> assertThat(state).isEqualTo(Option.some(41)));
        assertThat(transport.calls).as("a holding node never forwards a bounded-stale read").isEmpty();
    }

    /// The read half's core: a node with NO local log for the partition forwards to the committed
    /// owner instead of refusing (and instead of the pre-hosting-set ABSENT-from-a-void).
    @Test
    void boundedStaleGet_forwardsToOwner_whenNotHoldingThePartition() {
        substrate.holds = false;

        var result = entityAs(SELF, OTHER, Option.some(transport)).get("k1", ReadConsistency.BOUNDED_STALE).await();

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(state -> assertThat(state).isEqualTo(Option.some(107)));
        assertThat(transport.calls).hasSize(1);
        assertThat(transport.calls.getFirst().op()).isEqualTo("get");
        assertThat(transport.calls.getFirst().owner()).isEqualTo(OTHER);
    }

    /// ABSENT crosses the wire as an explicit empty Option and stays a SUCCESS — the caller can tell
    /// "the owner says the key does not exist" from every failure mode. Silently conflating the two
    /// is this ticket's original defect.
    @Test
    void boundedStaleGet_absentOnTheOwner_readsAsEmptySuccess() {
        substrate.holds = false;
        transport.absentGets = true;

        var result = entityAs(SELF, OTHER, Option.some(transport)).get("k1", ReadConsistency.BOUNDED_STALE).await();

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(state -> assertThat(state).isEqualTo(Option.none()));
    }

    /// Unwired must stay INERT for reads exactly as for writes: no transport means the honest TYPED
    /// local refusal — pinned by cause text, because a bare isFailure() stays green if the refusal
    /// mutates into a codec fault or an admission refusal (the trap this file documents above).
    @Test
    void boundedStaleGet_keepsLocalRefusal_whenNotHoldingAndNoTransport() {
        substrate.holds = false;

        var result = entityAs(SELF, OTHER, Option.none()).get("k1", ReadConsistency.BOUNDED_STALE).await();

        assertThat(refusalOf(result)).contains("is not held by this node");
        assertThat(transport.calls).isEmpty();
    }

    /// The owner side serves WITHOUT write admission — load-bearing per the ForwardTarget contract:
    /// this instance's admission would REFUSE (committed owner is OTHER), yet the forwarded read is
    /// served. Adding admitWrite to getForwarded turns this red.
    @Test
    void getForwarded_servesWithoutWriteAdmission() {
        entityAs(SELF, SELF, Option.some(transport)).create("k1", 41).await();

        var admissionWouldRefuse = entityAs(SELF, OTHER, Option.some(transport));
        var result = ((EntityForwardRegistry.ForwardTarget) admissionWouldRefuse)
                            .getForwarded("k1".getBytes(StandardCharsets.UTF_8))
                            .await();

        assertThat(result.isSuccess()).isTrue();
        result.onSuccess(bytes -> assertThat(bytes.isPresent()).isTrue());
    }

    /// Loop safety + the serve-time holds re-check (review MAJOR): a forwarded read ARRIVING at a
    /// node that does not hold the partition must FAIL with the typed refusal and must NOT bounce
    /// onward — the receiver reads through the LOCAL path only. Also arms the ready()-level holds
    /// check: routing-time holds is not enough once the fold has a memoized rebuild.
    @Test
    void getForwarded_onNonHoldingReceiver_refusesTyped_andNeverBouncesOnward() {
        substrate.holds = false;

        var receiver = entityAs(SELF, OTHER, Option.some(transport));
        var result = ((EntityForwardRegistry.ForwardTarget) receiver)
                            .getForwarded("k1".getBytes(StandardCharsets.UTF_8))
                            .await();

        assertThat(refusalOf(result)).contains("is not held by this node");
        assertThat(transport.calls).as("the receiving side must never re-enter the forwarding decision").isEmpty();
    }

    /// The owner side of the hop: present state answers as bytes, a missing key as an explicit empty
    /// Option — decoded with the same serializer the entity commits with.
    @Test
    void getForwarded_answersPresentBytes_andAbsentAsEmptyOption() {
        var owner = entityAs(SELF, SELF, Option.some(transport));

        owner.create("k1", 41).await();

        var target = (EntityForwardRegistry.ForwardTarget) owner;
        var present = target.getForwarded("k1".getBytes(StandardCharsets.UTF_8)).await();
        var absent = target.getForwarded("missing".getBytes(StandardCharsets.UTF_8)).await();

        assertThat(present.isSuccess()).isTrue();
        present.onSuccess(bytes -> assertThat(bytes.isPresent()).as("existing key answers with state bytes").isTrue());
        assertThat(absent.isSuccess()).isTrue();
        absent.onSuccess(bytes -> assertThat(bytes.isEmpty()).as("missing key is an explicit empty Option, not a failure").isTrue());
    }

    private record ForwardCall(String op, NodeId owner, String keyspace, byte[] key, byte[] command) {}
    /// Answers every forward with state 107, so a successful result proves the value came back ACROSS
    /// the seam rather than from local state (which starts empty here).
    private static final class RecordingForward implements EntityOwnerForward {
        private final List<ForwardCall> calls = new ArrayList<>();
        private final List<Boolean> boundedAtCall = new ArrayList<>();
        private String failure;
        private Cause refusal;

        void failWith(String message) {
            this.failure = message;
        }

        void refuseWith(Cause cause) {
            this.refusal = cause;
        }

        @Override
        public Promise<byte[]> forwardUpdate(NodeId owner, String keyspace, byte[] key, byte[] command) {
            return record("update", owner, keyspace, key, command);
        }

        @Override
        public Promise<byte[]> forwardCreate(NodeId owner, String keyspace, byte[] key, byte[] initial) {
            return record("create", owner, keyspace, key, initial);
        }

        @Override
        public Promise<byte[]> forwardDelete(NodeId owner, String keyspace, byte[] key) {
            return record("delete", owner, keyspace, key, new byte[0]);
        }

        @Override
        public Promise<Option<byte[]>> forwardGet(NodeId owner, String keyspace, byte[] key) {
            return record("get", owner, keyspace, key, new byte[0]).map(bytes -> absentGets
                                                                                 ? Option.none()
                                                                                 : Option.some(bytes));
        }

        /// When set, forwarded reads answer ABSENT — arming the explicit-Option path end to end.
        boolean absentGets;

        /// Echoes the token it was handed, which is what a correct owner does — [#echoedToken] overrides it
        /// so the mismatch path can be driven.
        @Override
        public Promise<String> forwardScheduleTimer(NodeId owner,
                                                    String keyspace,
                                                    byte[] key,
                                                    long delayMillis,
                                                    byte[] onFire,
                                                    String token) {
            arrivedToken = token;

            return record("scheduleTimer", owner, keyspace, key, onFire).map(_ -> echoedToken == null ? token : echoedToken);
        }

        /// The token the last forwarded schedule ARRIVED with, so a test can assert that what crossed the
        /// seam is the caller's own token rather than one minted at the hop.
        String arrivedToken;

        /// When set, forwarded schedules answer with THIS token instead of the one they were handed —
        /// modelling an owner that applied a different identity than the caller minted.
        String echoedToken;

        /// The token rides in the recorded `command` slot — cancel carries no body of its own, and a test
        /// asserting only "a cancel was forwarded" would pass with the handle dropped on the way.
        @Override
        public Promise<Unit> forwardCancelTimer(NodeId owner, String keyspace, byte[] key, String token) {
            return record("cancelTimer", owner, keyspace, key, token.getBytes(StandardCharsets.UTF_8)).mapToUnit();
        }

        private Promise<byte[]> record(String op, NodeId owner, String keyspace, byte[] key, byte[] body) {
            calls.add(new ForwardCall(op, owner, keyspace, key, body));
            boundedAtCall.add(Deadline.current().isBounded());

            if (refusal != null) {
                return refusal.promise();
            }

            return failure == null
                   ? Promise.success("107".getBytes(StandardCharsets.UTF_8))
                   : Causes.cause(failure).promise();
        }
    }

    /// Minimal in-memory log. Records appends so a test can assert that a FAILED forward wrote nothing
    /// locally — the property that separates "refused" from "applied twice".
    private static final class RecordingSubstrate implements EntityLogSubstrate {
        private final List<String> appended = new ArrayList<>();
        private final Map<Integer, List<byte[]>> log = new ConcurrentHashMap<>();

        @Override
        public Result<Unit> ensureLog(String keyspace, int partitionCount, int replicationFactor, int minSyncReplicas) {
            return Result.unitResult();
        }

        @Override
        public Promise<Long> append(String keyspace, int partition, byte[] record) {
            appended.add(keyspace + "/" + partition);

            var records = log.computeIfAbsent(partition, _ -> new ArrayList<>());

            records.add(record);

            return Promise.success((long) records.size() - 1);
        }

        @Override
        public Promise<List<byte[]>> read(String keyspace, int partition, long fromOffset, int maxRecords) {
            var records = log.getOrDefault(partition, List.of());
            var start = (int) fromOffset;

            return Promise.success(start >= records.size() ? List.of() : List.copyOf(records.subList(start, records.size())));
        }

        @Override
        public long headOffset(String keyspace, int partition) {
            return log.getOrDefault(partition, List.of()).size() - 1L;
        }

        @Override
        public long earliestRetainedOffset(String keyspace, int partition) {
            return 0L;
        }

        @Override
        public boolean localLogComplete(String keyspace, int partition) {
            return true;
        }

        @Override
        public boolean holdsPartition(String keyspace, int partition) {
            return holds;
        }

        /// #596 read half: controllable so the read tests can model a node OUTSIDE the replica set.
        /// Defaults to true — every write test runs in the holding configuration, as before.
        boolean holds = true;

        @Override
        public Promise<Unit> saveCheckpoint(String keyspace, int partition, long throughOffset, byte[] snapshot) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Option<EntityCheckpoint>> loadCheckpoint(String keyspace, int partition) {
            return Promise.success(Option.none());
        }
    }

    /// Round-trips the three things forwarding puts on the wire: the String key, the IntOp command and
    /// the Integer state. The existing fence-test codecs only handle state.
    private static final class TestSerializer implements Serializer {
        @Override
        public byte[] encode(Object value) {
            return switch (value) {
                case IntOp.Add add -> ("Add:" + add.delta()).getBytes(StandardCharsets.UTF_8);
                case IntOp.Multiply mul -> ("Mul:" + mul.factor()).getBytes(StandardCharsets.UTF_8);
                case IntOp.Identity _ -> "Id".getBytes(StandardCharsets.UTF_8);
                case null, default -> String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            };
        }

        @Override
        public <T> void write(ByteBuf byteBuf, T object) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class TestDeserializer implements Deserializer {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T decode(byte[] bytes) {
            var text = new String(bytes, StandardCharsets.UTF_8);

            if (text.startsWith("Add:")) {
                return (T) new IntOp.Add(Integer.parseInt(text.substring(4)));
            }
            if (text.startsWith("Mul:")) {
                return (T) new IntOp.Multiply(Integer.parseInt(text.substring(4)));
            }
            if ("Id".equals(text)) {
                return (T) new IntOp.Identity();
            }

            return (T) (text.chars().allMatch(Character::isDigit) ? Integer.valueOf(text) : text);
        }

        @Override
        public <T> T read(ByteBuf byteBuf) {
            throw new UnsupportedOperationException("not used by this test");
        }
    }
}
