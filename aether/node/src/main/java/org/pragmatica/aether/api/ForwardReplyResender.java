// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.stream.forward.RefusalBackoff;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// #2569 (part of #2583, P2): sends the receiver's reply to a forwarded management request and re-sends it when the
/// transport proves it did not write it.
///
/// The reply rides the FORWARD lane, whose backpressure refusal drops a frame at once. The send outcome used to be
/// ignored, so a refusal inside a few-millisecond episode lost the only reply: the entry node waited out its hop timeout and
/// answered 503 "outcome unknown" for a request this node had served.
///
/// Guarantee per reply:
/// - written on the first send ([WriteOutcome.Sent]): sent once, nothing more (the transport reports a local outcome, so a frame
///   lost after that is still the requester's to time out);
/// - refused ([WriteOutcome.BackpressureRefused], [WriteOutcome.NoPeerState], [WriteOutcome.ConnectionDead]): the same frame
///   is sent again after a hold of 250 ms doubling to a 4 s cap, +-20% jitter -- the shape ruled for #2574
///   (know 89e17399a9), the [RefusalBackoff] itself. The schedule is PER REPLY: each reply owns its backoff, which starts at
///   the first hold and is dropped with the reply (that is its reset on a `Sent`). A burst of replies refused in one episode
///   therefore all re-send after the first hold, instead of escalating one shared backoff past the requesters' windows;
/// - [WriteOutcome.EncodeFailed]: never re-sent (it cannot succeed), one WARN;
/// - the requester's budget cannot cover the next hold: not re-sent, one WARN. Past the budget the requester has completed
///   or timed out, so no correlation waits for the reply.
///
/// A re-send can reach a requester that already holds an earlier copy (a `ConnectionDead` frame may have sat in the offline
/// buffer). The requester ignores a reply for a correlation it no longer waits on (`HttpForwarder#onHttpForwardResponse`
/// removes the pending entry on the first reply), so a duplicate or late reply is harmless.
final class ForwardReplyResender {
    private static final Logger log = LoggerFactory.getLogger(ForwardReplyResender.class);

    private final BiConsumer<Runnable, TimeSpan> scheduler;
    private final LongSupplier nanoClock;
    private final Supplier<RefusalBackoff<Unit>> backoffs;

    private ForwardReplyResender(BiConsumer<Runnable, TimeSpan> scheduler,
                                 LongSupplier nanoClock,
                                 Supplier<RefusalBackoff<Unit>> backoffs) {
        this.scheduler = scheduler;
        this.nanoClock = nanoClock;
        this.backoffs = backoffs;
    }

    /// The production resender: shared scheduler, system nano clock, jittered backoff.
    static ForwardReplyResender forwardReplyResender() {
        return forwardReplyResender(ForwardReplyResender::scheduleShared,
                                    System::nanoTime,
                                    RefusalBackoff::refusalBackoff);
    }

    static ForwardReplyResender forwardReplyResender(BiConsumer<Runnable, TimeSpan> scheduler,
                                                     LongSupplier nanoClock,
                                                     Supplier<RefusalBackoff<Unit>> backoffs) {
        return new ForwardReplyResender(scheduler, nanoClock, backoffs);
    }

    /// Sends `reply` to `target` through `network`; `budget` is the requester's remaining wait, which bounds every re-send.
    /// The reply gets its own backoff.
    @Contract
    void deliver(ClusterNetwork network, NodeId target, HttpForwardResponse reply, TimeSpan budget) {
        write(network,
              target,
              reply,
              backoffs.get(),
              nanoClock.getAsLong() + budget.nanos());
    }

    @Contract
    private void write(ClusterNetwork network,
                       NodeId target,
                       HttpForwardResponse reply,
                       RefusalBackoff<Unit> holds,
                       long deadlineNanos) {
        network.send(target, reply).onSuccess(outcome -> settle(network, target, reply, outcome, holds, deadlineNanos));
    }

    @Contract
    private void settle(ClusterNetwork network,
                        NodeId target,
                        HttpForwardResponse reply,
                        WriteOutcome outcome,
                        RefusalBackoff<Unit> holds,
                        long deadlineNanos) {
        switch (outcome) {
            case WriteOutcome.Sent _ -> {}
            case WriteOutcome.EncodeFailed _ -> giveUp(target, reply, outcome, "cannot be encoded");
            case WriteOutcome.BackpressureRefused _, WriteOutcome.NoPeerState _, WriteOutcome.ConnectionDead _ -> holdThenResend(network,
                                                                                                                                 target,
                                                                                                                                 reply,
                                                                                                                                 outcome,
                                                                                                                                 holds,
                                                                                                                                 deadlineNanos);
        }
    }

    @Contract
    private void holdThenResend(ClusterNetwork network,
                                NodeId target,
                                HttpForwardResponse reply,
                                WriteOutcome outcome,
                                RefusalBackoff<Unit> holds,
                                long deadlineNanos) {
        var hold = holds.refused(Unit.unit());

        if (hold.nanos() >= deadlineNanos - nanoClock.getAsLong()) {
            giveUp(target,
                   reply,
                   outcome,
                   "the requester's budget cannot cover the next hold of " + hold.millis() + " ms");

            return;
        }

        scheduler.accept(() -> write(network, target, reply, holds, deadlineNanos), hold);
    }

    @Contract
    private static void giveUp(NodeId target, HttpForwardResponse reply, WriteOutcome outcome, String reason) {
        log.warn("Management forward reply [{}] to {} not written, last outcome {}; giving up re-sending: {}",
                 reply.requestId(),
                 target,
                 outcome,
                 reason);
    }

    @Contract
    private static void scheduleShared(Runnable task, TimeSpan delay) {
        SharedScheduler.schedule(task, delay);
    }
}
