// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.drain;

import org.pragmatica.aether.deployment.cluster.LifecycleWriter;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Real, consensus-backed drain coordinator.
///
/// State machine (RC1 spec §D.5):
///
/// ```text
///        prepareDrain()
///           │
///           ▼
///   ┌──────────────────┐         ack-timeout         ┌──────────────────┐
///   │   write DRAINING │────────────────────────────▶│   FAILED_DRAIN   │
///   │  via consensus   │                             │ (operator review)│
///   └──────────────────┘                             └──────────────────┘
///           │
///           │ awaitDrainAck()
///           ▼
///   ┌──────────────────┐
///   │ poll inflight==0 │
///   │ + lifecycle      │
///   │ ==DRAINING in KV │
///   └──────────────────┘
///           │ both true (within deadline)
///           ▼
///   ┌──────────────────┐
///   │ markDrainComplete│  → LifecycleWriter.requestDecommission(node)
///   └──────────────────┘
/// ```
///
/// Routing convergence is achieved via the lifecycle KV-Store: every node filters
/// out DRAINING owners when computing routing decisions (route table, stream leaders,
/// forward targets). The DRAINING atom is consensus-replicated, so once
/// `lifecycleReader(node) == DRAINING` is observable here on the coordinator's node,
/// it is observable cluster-wide.
///
/// Failure mode: if the in-flight counter does not reach zero within the supplied
/// timeout, `awaitDrainAck` fails with a timeout cause. The caller (CTM or the
/// `/api/node/drain` route) records `FAILED_DRAIN` via `LifecycleWriter.requestFailedDrain`
/// and returns 503 to the operator. The node remains addressable for emergency ops
/// (its task groups have already been reassigned via the lifecycle filter).
public final class ConsensusDrainCoordinator implements DrainCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ConsensusDrainCoordinator.class);

    private static final TimeSpan POLL_INTERVAL = TimeSpan.timeSpan(250).millis();

    private final LifecycleWriter lifecycleWriter;
    private final Fn1<Option<NodeLifecycleValue>, NodeId> lifecycleReader;
    private final Fn1<Promise<Integer>, NodeId> inFlightProbe;

    private ConsensusDrainCoordinator(LifecycleWriter lifecycleWriter,
                                       Fn1<Option<NodeLifecycleValue>, NodeId> lifecycleReader,
                                       Fn1<Promise<Integer>, NodeId> inFlightProbe) {
        this.lifecycleWriter = lifecycleWriter;
        this.lifecycleReader = lifecycleReader;
        this.inFlightProbe = inFlightProbe;
    }

    public static ConsensusDrainCoordinator consensusDrainCoordinator(LifecycleWriter lifecycleWriter,
                                                                       Fn1<Option<NodeLifecycleValue>, NodeId> lifecycleReader,
                                                                       Fn1<Promise<Integer>, NodeId> inFlightProbe) {
        return new ConsensusDrainCoordinator(lifecycleWriter, lifecycleReader, inFlightProbe);
    }

    /// Step 1: write the DRAINING lifecycle atom via consensus. Idempotent — if the
    /// route handler already wrote it, the underlying HealthReconciler.requestDrain
    /// is a no-op CAS.
    @Override public Promise<Unit> prepareDrain(NodeId nodeId, DrainReason reason) {
        log.info("Drain: prepareDrain {} (reason={})", nodeId, reason);
        return lifecycleWriter.requestDrain(nodeId)
                              .onFailure(cause -> log.warn("Drain: prepareDrain failed for {}: {}",
                                                            nodeId,
                                                            cause.message()));
    }

    /// Step 2: wait for in-flight requests to complete AND the DRAINING atom to be
    /// observable. Polls every {@link #POLL_INTERVAL} until the deadline elapses.
    @Override public Promise<Unit> awaitDrainAck(NodeId nodeId, TimeSpan timeout) {
        log.info("Drain: awaitDrainAck {} (timeout={}ms)", nodeId, timeout.millis());
        var deadlineMs = System.currentTimeMillis() + timeout.millis();
        return pollUntilQuiesced(nodeId, deadlineMs).timeout(timeout)
                                                    .onFailure(cause -> log.warn(
                                                            "Drain: awaitDrainAck {} did not converge within {}ms: {}",
                                                            nodeId,
                                                            timeout.millis(),
                                                            cause.message()))
                                                    .onSuccess(_ -> log.info("Drain: {} fully quiesced",
                                                                              nodeId));
    }

    private Promise<Unit> pollUntilQuiesced(NodeId nodeId, long deadlineMs) {
        return checkQuiescenceOnce(nodeId).flatMap(quiesced -> handlePollResult(nodeId, quiesced, deadlineMs));
    }

    private Promise<Unit> handlePollResult(NodeId nodeId, boolean quiesced, long deadlineMs) {
        if (quiesced) {return Promise.unitPromise();}
        if (System.currentTimeMillis() >= deadlineMs) {
            return Causes.cause("Drain ack deadline exceeded for " + nodeId.id())
                          .promise();
        }
        Promise<Unit> next = Promise.promise();
        SharedScheduler.schedule(() -> chainPoll(next, nodeId, deadlineMs), POLL_INTERVAL);
        return next;
    }

    @Contract private void chainPoll(Promise<Unit> sink, NodeId nodeId, long deadlineMs) {
        pollUntilQuiesced(nodeId, deadlineMs).onResult(sink::resolve);
    }

    /// One round of quiescence check: lifecycle observable as DRAINING AND in-flight count is zero.
    private Promise<Boolean> checkQuiescenceOnce(NodeId nodeId) {
        if (!lifecycleObservableAsDraining(nodeId)) {return Promise.success(false);}
        return inFlightProbe.apply(nodeId).map(count -> count <= 0)
                                          .recover(cause -> recoverProbeFailure(nodeId, cause));
    }

    private boolean lifecycleObservableAsDraining(NodeId nodeId) {
        return lifecycleReader.apply(nodeId).map(NodeLifecycleValue::state)
                                            .map(state -> state == NodeLifecycleState.DRAINING)
                                            .or(false);
    }

    private static boolean recoverProbeFailure(NodeId nodeId, Cause cause) {
        log.debug("Drain: inflight probe for {} failed: {} — treating as not-quiesced",
                  nodeId,
                  cause.message());
        return false;
    }

    /// Step 3: write the DECOMMISSIONED lifecycle atom via consensus. Best-effort
    /// fire-and-forget; the caller has already learned drain completed via the
    /// `Promise<Unit>` returned from `awaitDrainAck`.
    @Contract@Override public void markDrainComplete(NodeId nodeId) {
        log.info("Drain: markDrainComplete {}", nodeId);
        lifecycleWriter.requestDecommission(nodeId)
                       .onFailure(cause -> log.warn("Drain: markDrainComplete failed for {}: {}",
                                                     nodeId,
                                                     cause.message()));
    }
}
