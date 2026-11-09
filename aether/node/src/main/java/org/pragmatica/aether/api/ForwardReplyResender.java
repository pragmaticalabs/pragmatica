// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

import org.pragmatica.aether.http.forward.HttpForwardMessage.HttpForwardResponse;
import org.pragmatica.aether.stream.forward.RefusalBackoff;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Promise;
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
///   is sent again after a hold of 250 ms doubling to a 4 s cap, +-20% jitter, reset by a `Sent` -- the shape ruled for
///   #2574 (know 89e17399a9), the [RefusalBackoff] itself, held per peer so that every reply to one congested peer paces
///   together;
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
    private final RefusalBackoff<NodeId> holds;

    private ForwardReplyResender(BiConsumer<Runnable, TimeSpan> scheduler,
                                 LongSupplier nanoClock,
                                 RefusalBackoff<NodeId> holds) {
        this.scheduler = scheduler;
        this.nanoClock = nanoClock;
        this.holds = holds;
    }

    /// The production resender: shared scheduler, system nano clock, jittered backoff.
    static ForwardReplyResender forwardReplyResender() {
        return forwardReplyResender(ForwardReplyResender::scheduleShared, System::nanoTime, RefusalBackoff.refusalBackoff());
    }

    static ForwardReplyResender forwardReplyResender(BiConsumer<Runnable, TimeSpan> scheduler,
                                                     LongSupplier nanoClock,
                                                     RefusalBackoff<NodeId> holds) {
        return new ForwardReplyResender(scheduler, nanoClock, holds);
    }

    /// Sends `reply` to `target` through `transport`; `budget` is the requester's remaining wait, which bounds every re-send.
    @Contract
    void send(BiFunction<NodeId, HttpForwardResponse, Promise<WriteOutcome>> transport,
              NodeId target,
              HttpForwardResponse reply,
              TimeSpan budget) {
        write(transport, target, reply, nanoClock.getAsLong() + budget.nanos());
    }

    @Contract
    private void write(BiFunction<NodeId, HttpForwardResponse, Promise<WriteOutcome>> transport,
                       NodeId target,
                       HttpForwardResponse reply,
                       long deadlineNanos) {
        transport.apply(target, reply).onSuccess(outcome -> settle(transport, target, reply, outcome, deadlineNanos));
    }

    @Contract
    private void settle(BiFunction<NodeId, HttpForwardResponse, Promise<WriteOutcome>> transport,
                        NodeId target,
                        HttpForwardResponse reply,
                        WriteOutcome outcome,
                        long deadlineNanos) {
        switch (outcome) {
            case WriteOutcome.Sent _ -> holds.sent(target);
            case WriteOutcome.EncodeFailed _ -> giveUp(target, reply, outcome, "cannot be encoded");
            case WriteOutcome.BackpressureRefused _, WriteOutcome.NoPeerState _, WriteOutcome.ConnectionDead _ ->
            holdThenResend(transport, target, reply, outcome, deadlineNanos);
        }
    }

    @Contract
    private void holdThenResend(BiFunction<NodeId, HttpForwardResponse, Promise<WriteOutcome>> transport,
                                NodeId target,
                                HttpForwardResponse reply,
                                WriteOutcome outcome,
                                long deadlineNanos) {
        var hold = holds.refused(target);

        if (hold.nanos() >= deadlineNanos - nanoClock.getAsLong()) {
            giveUp(target, reply, outcome, "the requester's budget cannot cover the next hold of " + hold.millis() + " ms");

            return;
        }

        scheduler.accept(() -> write(transport, target, reply, deadlineNanos), hold);
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
