// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.entityforward;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityCancelTimerForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityCreateForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityDeleteForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityGetForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityGetForwardResponse;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityScheduleTimerForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityScheduleTimerForwardResponse;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityUpdateForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityUpdateForwardResponse;
import org.pragmatica.aether.resource.entity.EntityForwardRegistry;
import org.pragmatica.aether.resource.entity.EntityOwnerForward;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Deadline;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Both halves of entity owner-forwarding (#596) on the node: the SENDER that ships a command to the
/// committed owner, and the RECEIVER that applies an arriving one.
///
/// They live together because they are two ends of one correlation-id protocol, and splitting them
/// invites the request and response shapes drifting apart.
///
/// ## What this must not do
/// On any failure — unreachable owner, timeout, unknown keyspace, refusal by the owner's fence — the
/// caller sees a FAILURE. There is deliberately no local-apply fallback: applying the command here
/// after failing to reach the owner would put a second writer on the key, which is exactly the
/// split-brain the ownership fence exists to prevent. A refused write is a correct outcome; a
/// double-applied one is not.
public final class EntityForwardService implements EntityOwnerForward, EntityForwardRegistry {
    private static final Logger log = LoggerFactory.getLogger(EntityForwardService.class);

    private final NodeId selfNodeId;
    private final Sender sender;
    private final TimeSpan timeout;
    private final Map<String, ForwardTarget> targets = new ConcurrentHashMap<>();
    private final Map<String, Promise<byte[]>> pending = new ConcurrentHashMap<>();
    /// The read half's correlation map, distinct from [#pending] because a read's answer is an
    /// EXPLICIT `Option<byte[]>` — absence is a flag on the wire, never a byte-length convention.
    private final Map<String, Promise<Option<byte[]>>> pendingGets = new ConcurrentHashMap<>();
    /// The schedule half's correlation map (#345 I4), distinct from [#pending] because a schedule's answer
    /// is the owner's echo of the applied token as a `String`. Cancel has no map of its own — it answers
    /// through [#pending] with [EntityUpdateForwardResponse], the same Unit-shaped carrier delete uses.
    private final Map<String, Promise<String>> pendingSchedules = new ConcurrentHashMap<>();

    /// Sending is a one-line seam so the node can supply its `ClusterNetwork` without this class
    /// depending on the whole network surface — and so a test can drive both halves in-process.
    ///
    /// Returns the send's LOCAL outcome (`WriteOutcome`) so a request that cannot leave this node fails
    /// its caller NOW instead of burning the full correlation timeout: a dead link, an unknown peer or a
    /// backpressure refusal is knowable synchronously, and 02w measured what ignoring that costs — every
    /// forward to an unreachable owner blocked its caller for the whole 30s.
    public interface Sender {
        Promise<WriteOutcome> send(NodeId target, EntityForwardMessage message);
    }

    private EntityForwardService(NodeId selfNodeId, Sender sender, TimeSpan timeout) {
        this.selfNodeId = selfNodeId;
        this.sender = sender;
        this.timeout = timeout;
    }

    public static EntityForwardService entityForwardService(NodeId selfNodeId, Sender sender, TimeSpan timeout) {
        return new EntityForwardService(selfNodeId, sender, timeout);
    }

    // --- registry (receiving side) ---
    @Override
    @Contract
    public void register(String keyspace, ForwardTarget target) {
        targets.put(keyspace, target);
        log.info("Entity owner-forward: keyspace '{}' registered as a forward target", keyspace);
    }

    @Override
    @Contract
    public void unregister(String keyspace) {
        Option.option(targets.remove(keyspace)).onPresent(_ -> log.info("Entity owner-forward: keyspace '{}' unregistered as a forward target",
                                                                        keyspace));
    }

    // --- sender ---
    @Override
    public Promise<byte[]> forwardUpdate(NodeId owner, String keyspace, byte[] key, byte[] command) {
        return dispatch(owner,
                        keyspace,
                        (correlationId, wireBudget) -> EntityUpdateForward.entityUpdateForward(selfNodeId,
                                                                                               correlationId,
                                                                                               keyspace,
                                                                                               key,
                                                                                               command,
                                                                                               wireBudget));
    }

    @Override
    public Promise<byte[]> forwardCreate(NodeId owner, String keyspace, byte[] key, byte[] initial) {
        return dispatch(owner,
                        keyspace,
                        (correlationId, wireBudget) -> EntityCreateForward.entityCreateForward(selfNodeId,
                                                                                               correlationId,
                                                                                               keyspace,
                                                                                               key,
                                                                                               initial,
                                                                                               wireBudget));
    }

    @Override
    public Promise<byte[]> forwardDelete(NodeId owner, String keyspace, byte[] key) {
        return dispatch(owner,
                        keyspace,
                        (correlationId, wireBudget) -> EntityDeleteForward.entityDeleteForward(selfNodeId,
                                                                                               correlationId,
                                                                                               keyspace,
                                                                                               key,
                                                                                               wireBudget));
    }

    /// The read half (#596): same protocol, its own correlation map — the answer is an explicit
    /// `Option<byte[]>`, resolved by [#onEntityGetForwardResponse] from the wire's `present` flag.
    @Override
    public Promise<Option<byte[]>> forwardGet(NodeId owner, String keyspace, byte[] key) {
        return dispatchInto(pendingGets,
                            owner,
                            keyspace,
                            (correlationId, wireBudget) -> EntityGetForward.entityGetForward(selfNodeId,
                                                                                             correlationId,
                                                                                             keyspace,
                                                                                             key,
                                                                                             wireBudget));
    }

    /// The schedule half (#345 I4): its own correlation map, because the answer is a token `String` rather
    /// than encoded state. The DELAY is what travels — the owner stamps the fire instant from its own
    /// clock, so no sender/owner skew enters the timer. The TOKEN travels too, minted by the caller before
    /// this hop, so the owner applies the caller's identity for the timer and a re-sent schedule is
    /// recognised as the same one.
    @Override
    public Promise<String> forwardScheduleTimer(NodeId owner,
                                                String keyspace,
                                                byte[] key,
                                                long delayMillis,
                                                byte[] onFire,
                                                String token) {
        return dispatchInto(pendingSchedules,
                            owner,
                            keyspace,
                            (correlationId, wireBudget) -> EntityScheduleTimerForward.entityScheduleTimerForward(selfNodeId,
                                                                                                                 correlationId,
                                                                                                                 keyspace,
                                                                                                                 key,
                                                                                                                 token,
                                                                                                                 delayMillis,
                                                                                                                 onFire,
                                                                                                                 wireBudget));
    }

    /// The cancel half (#345 I4). Rides [#pending] and [EntityUpdateForwardResponse] rather than a carrier
    /// of its own — a cancel's outcome is the success or failure itself, exactly as delete's is, so the
    /// answered state bytes are empty by contract and discarded here.
    @Override
    public Promise<Unit> forwardCancelTimer(NodeId owner, String keyspace, byte[] key, String token) {
        return dispatch(owner,
                        keyspace,
                        (correlationId, wireBudget) -> EntityCancelTimerForward.entityCancelTimerForward(selfNodeId,
                                                                                                         correlationId,
                                                                                                         keyspace,
                                                                                                         key,
                                                                                                         token,
                                                                                                         wireBudget)).mapToUnit();
    }

    /// One correlation protocol for every operation whose answer is encoded bytes — update, create, delete
    /// and timer-cancel: the pending map, the timeout and the response handler are shared, so only the
    /// message built differs.
    ///
    /// The wait is capped by the ambient request budget ([Deadline]): the configured correlation
    /// timeout is this service's own ceiling, but a caller under a client deadline gets at most what
    /// remains of it — waiting longer answers nobody. A budget below [#BUDGET_FLOOR] refuses BEFORE
    /// the send: the round trip cannot complete in time, and a command whose ack has no collector
    /// widens the unknown-outcome window on a non-idempotent write for nothing (caller-retry dedup
    /// is the open S3 idempotency decision).
    private Promise<byte[]> dispatch(NodeId owner, String keyspace, Fn2<EntityForwardMessage, String, Long> message) {
        return dispatchInto(pending, owner, keyspace, message);
    }

    /// The ONE correlation protocol, generic over the answer type: update, create, delete and
    /// timer-cancel correlate `byte[]` through [#pending], the read half an explicit `Option<byte[]>`
    /// through [#pendingGets], and the schedule half the owner's echoed token `String` through
    /// [#pendingSchedules]. A single implementation so the halves cannot drift — the same reason
    /// sender and receiver share this class.
    private <R> Promise<R> dispatchInto(Map<String, Promise<R>> pendingMap,
                                        NodeId owner,
                                        String keyspace,
                                        Fn2<EntityForwardMessage, String, Long> message) {
        var deadline = Deadline.current();

        if (deadline.expired(BUDGET_FLOOR)) {
            return FORWARD_BUDGET_EXHAUSTED.apply(owner.id(),
                                                  keyspace)
                                           .promise();
        }

        var correlationId = UUID.randomUUID().toString();
        Promise<R> promise = Promise.promise();
        var effectiveTimeout = deadline.bounded(timeout);
        // Debug, not trace: run5 burned 48 minutes on forwards whose waits left NO log line at all,
        // making "bounded to the client budget" and "unbounded 30s constant" indistinguishable
        // post-hoc. One line per forward names which one this is.
        log.debug("Entity owner-forward to {} keyspace '{}' waits {} (budget bounded={}) correlationId={}",
                  owner,
                  keyspace,
                  effectiveTimeout,
                  deadline.isBounded(),
                  correlationId);
        pendingMap.put(correlationId, promise);
        SharedScheduler.schedule(() -> timeoutRequest(pendingMap, correlationId, owner, keyspace, effectiveTimeout),
                                 effectiveTimeout);
        // #634 follow-up, the entity half of stage-2 propagation: stamp the remaining budget onto the
        // wire so the OWNER can refuse an arrived-expired command instead of applying work whose ack
        // nobody collects.
        sender.send(owner,
                    message.apply(correlationId,
                                  deadline.toWireMillis()))
              .onSuccess(outcome -> failFastOnRefusedSend(pendingMap, correlationId, owner, keyspace, outcome));
        log.trace("Sent entity owner-forward to {} for keyspace '{}' correlationId={}", owner, keyspace, correlationId);

        return promise;
    }

    /// A send the transport refused never left this node, so nothing can ever answer it — waiting out the
    /// correlation timeout would just convert a knowable failure into 30s of silence. The pending entry is
    /// removed first, so a raced timeout resolves nothing.
    private <R> void failFastOnRefusedSend(Map<String, Promise<R>> pendingMap,
                                           String correlationId,
                                           NodeId owner,
                                           String keyspace,
                                           WriteOutcome outcome) {
        if (outcome.isSent()) {
            return;
        }

        var promise = pendingMap.remove(correlationId);

        if (promise == null) {
            return;
        }

        log.warn("Entity owner-forward to {} for keyspace '{}' refused at send: {} (correlationId={})",
                 owner,
                 keyspace,
                 outcome,
                 correlationId);
        promise.resolve(FORWARD_SEND_REFUSED.apply(owner.id(), outcome).result());
    }

    /// A fired timeout WARNS with the wait it enforced: run5's forwards timed out for 48 minutes in
    /// total silence, leaving "bounded wait" vs "unbounded 30s constant" undecidable from the logs.
    private <R> void timeoutRequest(Map<String, Promise<R>> pendingMap,
                                    String correlationId,
                                    NodeId owner,
                                    String keyspace,
                                    TimeSpan waited) {
        var promise = pendingMap.remove(correlationId);

        if (promise != null) {
            log.warn("Entity owner-forward to {} for keyspace '{}' timed out after {} (correlationId={})",
                     owner,
                     keyspace,
                     waited,
                     correlationId);
            promise.resolve(FORWARD_TIMED_OUT.apply(owner.id(), keyspace).result());
        }
    }

    // --- receiver ---
    /// Apply an arriving command through the keyspace's live entity, then answer the sender.
    ///
    /// An unknown keyspace is a real failure, not silence: it means the sender resolved this node as the
    /// owner of an arc whose entity is not provisioned here, and the caller must hear that rather than
    /// wait out a timeout.
    @Contract
    public void onEntityUpdateForward(EntityUpdateForward request) {
        applyAndAnswer(request.sender(),
                       request.correlationId(),
                       request.keyspace(),
                       request.remainingMillis(),
                       target -> target.applyForwarded(request.key(), request.command()));
    }

    @Contract
    public void onEntityCreateForward(EntityCreateForward request) {
        applyAndAnswer(request.sender(),
                       request.correlationId(),
                       request.keyspace(),
                       request.remainingMillis(),
                       target -> target.createForwarded(request.key(), request.initial()));
    }

    @Contract
    public void onEntityDeleteForward(EntityDeleteForward request) {
        applyAndAnswer(request.sender(),
                       request.correlationId(),
                       request.keyspace(),
                       request.remainingMillis(),
                       target -> target.deleteForwarded(request.key()));
    }

    /// The read half's receiver (#596). Same budget discipline as the mutation trio — an
    /// arrived-expired read is refused without touching the entity: serving an answer nobody
    /// collects is wasted fold work, and the refusal keeps read and write symmetric. Answers with
    /// [EntityGetForwardResponse], whose `present` flag is the explicit absence carrier.
    @Contract
    public void onEntityGetForward(EntityGetForward request) {
        serveAndAnswer(request.sender(),
                       request.correlationId(),
                       request.keyspace(),
                       request.remainingMillis(),
                       target -> target.getForwarded(request.key()),
                       state -> getAnswer(request.correlationId(), state),
                       (failureType, message) -> EntityGetForwardResponse.failureResponse(selfNodeId,
                                                                                          request.correlationId(),
                                                                                          failureType,
                                                                                          message));
    }

    /// Absence is a FLAG on the wire, never a zero-length payload — reconstructing it from state length is
    /// this ticket's original defect, one layer down.
    private EntityForwardMessage getAnswer(String correlationId, Option<byte[]> state) {
        return state.fold(() -> EntityGetForwardResponse.absentResponse(selfNodeId, correlationId),
                          bytes -> EntityGetForwardResponse.presentResponse(selfNodeId, correlationId, bytes));
    }

    /// The timer-cancel receiver (#345 I4). Rides the mutation trio's [EntityUpdateForwardResponse]: a
    /// cancel has no post-state, so the carrier's `state` field is filled with the empty payload HERE, at
    /// the wire boundary that requires one, rather than by a target made to manufacture bytes it has no
    /// answer for.
    @Contract
    public void onEntityCancelTimerForward(EntityCancelTimerForward request) {
        serveAndAnswer(request.sender(),
                       request.correlationId(),
                       request.keyspace(),
                       request.remainingMillis(),
                       target -> target.cancelTimerForwarded(request.key(), request.token()),
                       _ -> EntityUpdateForwardResponse.successResponse(selfNodeId, request.correlationId(), NO_PAYLOAD),
                       (failureType, message) -> EntityUpdateForwardResponse.failureResponse(selfNodeId,
                                                                                             request.correlationId(),
                                                                                             failureType,
                                                                                             message));
    }

    /// The timer-schedule receiver (#345 I4). Answers a token `String`, which
    /// [EntityUpdateForwardResponse] has no field for — so, like the read half, it supplies its own
    /// response factories rather than repeating the preamble.
    /// An arrived-expired schedule is refused without touching the entity, since planting a durable timer
    /// whose ack nobody collects spends a fenced append on work the caller has already stopped waiting for.
    /// The caller does still hold the token it minted, so the refusal costs it nothing it cannot recover:
    /// it re-sends the same token, or cancels it.
    @Contract
    public void onEntityScheduleTimerForward(EntityScheduleTimerForward request) {
        serveAndAnswer(request.sender(),
                       request.correlationId(),
                       request.keyspace(),
                       request.remainingMillis(),
                       target -> target.scheduleTimerForwarded(request.key(),
                                                               request.delayMillis(),
                                                               request.onFire(),
                                                               request.token()),
                       token -> EntityScheduleTimerForwardResponse.successResponse(selfNodeId,
                                                                                   request.correlationId(),
                                                                                   token),
                       (failureType, message) -> EntityScheduleTimerForwardResponse.failureResponse(selfNodeId,
                                                                                                    request.correlationId(),
                                                                                                    failureType,
                                                                                                    message));
    }

    /// Shared by the three operations that answer POST-STATE bytes through [EntityUpdateForwardResponse] —
    /// update, create and delete. Timer-cancel rides the same carrier but not this helper: it has no
    /// post-state, so [#onEntityCancelTimerForward] supplies [#NO_PAYLOAD] itself.
    private void applyAndAnswer(NodeId requester,
                                String correlationId,
                                String keyspace,
                                long remainingMillis,
                                Fn1<Promise<byte[]>, ForwardTarget> operation) {
        serveAndAnswer(requester,
                       correlationId,
                       keyspace,
                       remainingMillis,
                       operation,
                       state -> EntityUpdateForwardResponse.successResponse(selfNodeId, correlationId, state),
                       (failureType, message) -> EntityUpdateForwardResponse.failureResponse(selfNodeId,
                                                                                             correlationId,
                                                                                             failureType,
                                                                                             message));
    }

    /// The ONE receiving protocol, generic over the answer type — the mirror of [#dispatchInto] on the
    /// sending side, and unified for the same reason: the arrived-budget refusal, the unknown-keyspace
    /// refusal and the answer-either-way tail are identical for every verb, and three copies of them meant
    /// a change to the budget discipline could land on one and read as correct on the others.
    ///
    /// Only the response CARRIER differs, so it arrives as two factories: `success` renders the operation's
    /// answer, `failure` renders a refusal type and message. The wire carriers are not interchangeable — a
    /// read carries an explicit `present` flag, a schedule carries a token — and this is the seam that lets
    /// them stay that way without duplicating everything around them.
    ///
    /// An unknown keyspace is a real failure, not silence: it means the sender resolved this node as the
    /// owner of an arc whose entity is not provisioned here, and the caller must hear that rather than
    /// wait out a timeout.
    private <R> void serveAndAnswer(NodeId requester,
                                    String correlationId,
                                    String keyspace,
                                    long remainingMillis,
                                    Fn1<Promise<R>, ForwardTarget> operation,
                                    Fn1<EntityForwardMessage, R> success,
                                    Fn2<EntityForwardMessage, String, String> failure) {
        // Stage 2 of deadline propagation (#634 follow-up), mirroring AppHttpServer's receiver: rebind
        // the sender's wire budget, and REFUSE a command that arrives with at most BUDGET_FLOOR left —
        // the round trip cannot finish inside what the sender is still waiting for, so applying would be
        // a non-idempotent write whose ack nobody collects (the zombie-dispatch amplification 02w
        // measured). A sender under no deadline carries an unbounded budget and is never refused here.
        var deadline = Deadline.fromWireMillis(remainingMillis);

        if (deadline.expired(BUDGET_FLOOR)) {
            log.warn("Entity owner-forward arrived with {} budget remaining for keyspace '{}' — refusing"
                    + " without dispatch (correlationId={})",
                     deadline.remaining(),
                     keyspace,
                     correlationId);
            answer(requester,
                   correlationId,
                   failure.apply("ForwardBudgetExhausted",
                                 "arrived with " + deadline.remaining()
                                + " remaining — the sender has already timed out"));

            return;
        }

        Deadline.runWith(deadline,
                         () -> serveWithinBudget(requester, correlationId, keyspace, operation, success, failure));
    }

    private <R> void serveWithinBudget(NodeId requester,
                                       String correlationId,
                                       String keyspace,
                                       Fn1<Promise<R>, ForwardTarget> operation,
                                       Fn1<EntityForwardMessage, R> success,
                                       Fn2<EntityForwardMessage, String, String> failure) {
        var target = targets.get(keyspace);

        if (target == null) {
            log.warn("Entity owner-forward: no target for keyspace '{}' (correlationId={})", keyspace, correlationId);
            answer(requester,
                   correlationId,
                   failure.apply("UnknownKeyspace", "no entity registered for keyspace " + keyspace));

            return;
        }

        operation.apply(target)
                 .onSuccess(value -> answer(requester,
                                            correlationId,
                                            success.apply(value)))
                 .onFailure(cause -> answer(requester,
                                            correlationId,
                                            failure.apply(cause.getClass().getSimpleName(),
                                                          cause.message())));
    }

    /// A refused RESPONSE send is logged and nothing more: the requester's own timeout (or its own
    /// fast-fail, if the link died symmetrically) is the recovery, and there is no one else to tell.
    private void answer(NodeId requester, String correlationId, EntityForwardMessage response) {
        sender.send(requester, response).onSuccess(outcome -> logRefusedAnswer(requester, correlationId, outcome));
    }

    private static void logRefusedAnswer(NodeId requester, String correlationId, WriteOutcome outcome) {
        if (!outcome.isSent()) {
            log.warn("Entity owner-forward response to {} refused at send: {} (correlationId={})",
                     requester,
                     correlationId,
                     outcome);
        }
    }

    /// Resolve the waiting promise. A response whose correlation id is unknown is DROPPED with a trace:
    /// it is a late answer to a request already timed out, and resolving nothing is the correct outcome.
    @Contract
    public void onEntityUpdateForwardResponse(EntityUpdateForwardResponse response) {
        var promise = pending.remove(response.correlationId());

        if (promise == null) {
            log.trace("Entity owner-forward: response for unknown correlationId={} (already timed out?)",
                      response.correlationId());

            return;
        }

        promise.resolve(response.success()
                        ? Result.success(response.state())
                        : new EntityOwnerForward.ForwardRefused(response.failureType(), response.errorMessage()).result());
    }

    /// The read half's [#onEntityUpdateForwardResponse]: same late-answer drop, its own pending map,
    /// and absence reconstructed from the EXPLICIT `present` flag — never from state length.
    @Contract
    public void onEntityGetForwardResponse(EntityGetForwardResponse response) {
        var promise = pendingGets.remove(response.correlationId());

        if (promise == null) {
            log.trace("Entity owner-forward: read response for unknown correlationId={} (already timed out?)",
                      response.correlationId());

            return;
        }

        promise.resolve(response.success()
                        ? Result.success(response.present()
                                         ? Option.some(response.state())
                                         : Option.none())
                        : new EntityOwnerForward.ForwardRefused(response.failureType(), response.errorMessage()).result());
    }

    /// The schedule half's [#onEntityUpdateForwardResponse] (#345 I4): same late-answer drop, its own
    /// pending map, and the owner's echo of the applied token carried as a plain `String` — the entity
    /// module re-wraps it into its own token type, which never becomes a wire type, and checks it against
    /// the token it sent.
    @Contract
    public void onEntityScheduleTimerForwardResponse(EntityScheduleTimerForwardResponse response) {
        var promise = pendingSchedules.remove(response.correlationId());

        if (promise == null) {
            log.trace("Entity owner-forward: timer schedule response for unknown correlationId={} (already timed out?)",
                      response.correlationId());

            return;
        }

        promise.resolve(response.success()
                        ? Result.success(response.token())
                        : new EntityOwnerForward.ForwardRefused(response.failureType(), response.errorMessage()).result());
    }

    /// Below this much remaining budget a forward is refused instead of sent: the owner round trip
    /// cannot complete before the caller's deadline, so the send could only produce an uncollectable
    /// ack — and, on a non-idempotent write, an owner-side apply the caller cannot distinguish from
    /// a lost command.
    private static final TimeSpan BUDGET_FLOOR = TimeSpan.timeSpan(50).millis();
    /// The `state` field [EntityUpdateForwardResponse] requires from an operation that HAS no post-state —
    /// delete and timer-cancel. The sender discards it. `PartitionFencedDurableEntity.deleteForwarded`
    /// mints the same empty payload one layer down, for the delete that answers through the target
    /// interface rather than through this receiver.
    private static final byte[] NO_PAYLOAD = new byte[0];

    private static final BiFunction<String, WriteOutcome, Cause> FORWARD_SEND_REFUSED = (owner, outcome) -> Causes.cause("entity owner-forward to " + owner
                                                                                                                        + " refused at send (" + outcome
                                                                                                                        + ") — the owner was never reached");

    private static final BiFunction<String, String, Cause> FORWARD_TIMED_OUT = (owner, keyspace) -> Causes.cause("entity owner-forward to " + owner
                                                                                                                + " for keyspace " + keyspace
                                                                                                                + " timed out — NOT applied on this node; the owner may or may not have"
                                                                                                                + " applied it (outcome unknown, a blind retry can double-apply)");

    private static final BiFunction<String, String, Cause> FORWARD_BUDGET_EXHAUSTED = (owner, keyspace) -> Causes.cause("entity owner-forward to " + owner
                                                                                                                       + " for keyspace " + keyspace
                                                                                                                       + " refused: request budget exhausted — the command was never sent");
}
