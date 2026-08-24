// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.entityforward;

import java.util.Map;
import java.util.function.BiFunction;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityCreateForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityDeleteForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityUpdateForward;
import org.pragmatica.aether.node.entityforward.EntityForwardMessage.EntityUpdateForwardResponse;
import org.pragmatica.aether.resource.entity.EntityForwardRegistry;
import org.pragmatica.aether.resource.entity.EntityOwnerForward;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
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
                        correlationId -> EntityUpdateForward.entityUpdateForward(selfNodeId,
                                                                                 correlationId,
                                                                                 keyspace,
                                                                                 key,
                                                                                 command));
    }

    @Override
    public Promise<byte[]> forwardCreate(NodeId owner, String keyspace, byte[] key, byte[] initial) {
        return dispatch(owner,
                        keyspace,
                        correlationId -> EntityCreateForward.entityCreateForward(selfNodeId,
                                                                                 correlationId,
                                                                                 keyspace,
                                                                                 key,
                                                                                 initial));
    }

    @Override
    public Promise<byte[]> forwardDelete(NodeId owner, String keyspace, byte[] key) {
        return dispatch(owner,
                        keyspace,
                        correlationId -> EntityDeleteForward.entityDeleteForward(selfNodeId,
                                                                                 correlationId,
                                                                                 keyspace,
                                                                                 key));
    }

    /// One correlation protocol for all three operations: the pending map, the timeout and the response
    /// handler are shared, so only the message built differs.
    ///
    /// The wait is capped by the ambient request budget ([Deadline]): the configured correlation
    /// timeout is this service's own ceiling, but a caller under a client deadline gets at most what
    /// remains of it — waiting longer answers nobody. A budget below [#BUDGET_FLOOR] refuses BEFORE
    /// the send: the round trip cannot complete in time, and a command whose ack has no collector
    /// widens the unknown-outcome window on a non-idempotent write for nothing (caller-retry dedup
    /// is the open S3 idempotency decision).
    private Promise<byte[]> dispatch(NodeId owner, String keyspace, Fn1<EntityForwardMessage, String> message) {
        var deadline = Deadline.current();

        if (deadline.expired(BUDGET_FLOOR)) {
            return FORWARD_BUDGET_EXHAUSTED.apply(owner.id(),
                                                  keyspace)
                                           .promise();
        }

        var correlationId = UUID.randomUUID().toString();
        Promise<byte[]> promise = Promise.promise();
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
        pending.put(correlationId, promise);
        SharedScheduler.schedule(() -> timeoutRequest(correlationId, owner, keyspace, effectiveTimeout),
                                 effectiveTimeout);
        sender.send(owner,
                    message.apply(correlationId))
              .onSuccess(outcome -> failFastOnRefusedSend(correlationId, owner, keyspace, outcome));
        log.trace("Sent entity owner-forward to {} for keyspace '{}' correlationId={}", owner, keyspace, correlationId);

        return promise;
    }

    /// A send the transport refused never left this node, so nothing can ever answer it — waiting out the
    /// correlation timeout would just convert a knowable failure into 30s of silence. The pending entry is
    /// removed first, so a raced timeout resolves nothing.
    private void failFastOnRefusedSend(String correlationId, NodeId owner, String keyspace, WriteOutcome outcome) {
        if (outcome.isSent()) {
            return;
        }

        var promise = pending.remove(correlationId);

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
    private void timeoutRequest(String correlationId, NodeId owner, String keyspace, TimeSpan waited) {
        var promise = pending.remove(correlationId);

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
                       target -> target.applyForwarded(request.key(), request.command()));
    }

    @Contract
    public void onEntityCreateForward(EntityCreateForward request) {
        applyAndAnswer(request.sender(),
                       request.correlationId(),
                       request.keyspace(),
                       target -> target.createForwarded(request.key(), request.initial()));
    }

    @Contract
    public void onEntityDeleteForward(EntityDeleteForward request) {
        applyAndAnswer(request.sender(),
                       request.correlationId(),
                       request.keyspace(),
                       target -> target.deleteForwarded(request.key()));
    }

    /// Shared by all three operations: resolve the keyspace's live entity, run the operation, answer the
    /// sender either way. An unknown keyspace is a real failure, not silence — it means the sender
    /// resolved this node as the owner of an arc whose entity is not provisioned here, and the caller
    /// must hear that rather than wait out a timeout.
    private void applyAndAnswer(NodeId requester,
                                String correlationId,
                                String keyspace,
                                Fn1<Promise<byte[]>, ForwardTarget> operation) {
        var target = targets.get(keyspace);

        if (target == null) {
            log.warn("Entity owner-forward: no target for keyspace '{}' (correlationId={})", keyspace, correlationId);
            answer(requester,
                   correlationId,
                   EntityUpdateForwardResponse.failureResponse(selfNodeId,
                                                               correlationId,
                                                               "UnknownKeyspace",
                                                               "no entity registered for keyspace " + keyspace));

            return;
        }

        operation.apply(target)
                 .onSuccess(state -> answer(requester,
                                            correlationId,
                                            EntityUpdateForwardResponse.successResponse(selfNodeId, correlationId, state)))
                 .onFailure(cause -> answer(requester,
                                            correlationId,
                                            EntityUpdateForwardResponse.failureResponse(selfNodeId,
                                                                                        correlationId,
                                                                                        cause.getClass().getSimpleName(),
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

    /// Below this much remaining budget a forward is refused instead of sent: the owner round trip
    /// cannot complete before the caller's deadline, so the send could only produce an uncollectable
    /// ack — and, on a non-idempotent write, an owner-side apply the caller cannot distinguish from
    /// a lost command.
    private static final TimeSpan BUDGET_FLOOR = TimeSpan.timeSpan(50).millis();

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
