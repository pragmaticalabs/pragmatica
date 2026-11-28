// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.aether.deployment.cluster.DrainGate;
import org.pragmatica.aether.deployment.cluster.DrainSink;
import org.pragmatica.aether.deployment.cluster.FenceFailure.AttemptCancelled;
import org.pragmatica.aether.deployment.cluster.FenceFailure.CalledUnderOwnerMonitor;
import org.pragmatica.aether.deployment.cluster.FencePort;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Result.unitResult;


/// The one production [DrainSink] (#3754 C1, design r6 §3): a DRAIN request is committed through the [FencePort] as a witness, and the
/// registry add and the FSM call run only in the accepted continuation, each under its own caller's [DrainGate].
///
/// A request for a target that already has a witness in flight joins that witness and gets its outcome through its own gate: the sink
/// deduplicates the witness per target, never the gate. A target already in the registry runs its gate at once and starts no witness.
/// Every caller's promise is resolved by the sink after that caller's own gate returned, so never under the owner's monitor, and
/// after the witness's intent is removed from `intents`: no caller, and no promise chained on a caller or on [#inFlight], observes an
/// outcome while the request is still in flight. The one place that does both, in that order, is [#settle].
///
/// The port is injected, so this lane's wiring passes an unfenced adapter; lane (b3) passes the CCL port.
final class FencedDrainSink implements DrainSink {
    private static final Logger LOG = LoggerFactory.getLogger(FencedDrainSink.class);

    private final FencePort port;
    private final Consumer<NodeId> registryAdd;
    private final Supplier<Set<NodeId>> drainTargets;
    private final AtomicReference<MembershipFsm> membershipFsmRef;
    private final ConcurrentMap<NodeId, Intent> intents = new ConcurrentHashMap<>();

    private FencedDrainSink(FencePort port,
                            Consumer<NodeId> registryAdd,
                            Supplier<Set<NodeId>> drainTargets,
                            AtomicReference<MembershipFsm> membershipFsmRef) {
        this.port = port;
        this.registryAdd = registryAdd;
        this.drainTargets = drainTargets;
        this.membershipFsmRef = membershipFsmRef;
    }

    /// `registryAdd` and `drainTargets` are the registry as two functions; `membershipFsmRef` defers the FSM, which is built after the
    /// sink (the boot window answers without an FSM call).
    static FencedDrainSink fencedDrainSink(FencePort port,
                                           Consumer<NodeId> registryAdd,
                                           Supplier<Set<NodeId>> drainTargets,
                                           AtomicReference<MembershipFsm> membershipFsmRef) {
        return new FencedDrainSink(port, registryAdd, drainTargets, membershipFsmRef);
    }

    /// One caller of a witness: its own gate, and the promise this sink resolves after that gate returned.
    record Joined(DrainGate gate, Promise<Unit> caller) {
        static Joined joined(DrainGate gate) {
            return new Joined(gate, Promise.promise());
        }
    }

    /// One caller's answer, held back until the intent is out of `intents`.
    private record Settlement(Joined joined, Result<Unit> answer) {
        static Settlement settlement(Joined joined, Result<Unit> answer) {
            return new Settlement(joined, answer);
        }
    }

    /// The witness in flight for one target. `outcome` resolves after every joined gate ran: success if the target is then
    /// registered, [AttemptCancelled] if not, the witness's own cause if it was refused.
    static final class Intent {
        private final Promise<Unit> outcome = Promise.promise();
        private List<Joined> joined = new ArrayList<>();
        private boolean closed;

        static Intent intent(Joined first) {
            var intent = new Intent();

            intent.joined.add(first);

            return intent;
        }

        Promise<Unit> outcome() {
            return outcome;
        }

        /// Adds a caller unless the witness has already taken its last batch; `false` sends the caller to a fresh request.
        synchronized boolean join(Joined entry) {
            return ! closed && joined.add(entry);
        }

        /// The callers joined since the last take. An empty answer closes the intent: no later caller can join it.
        synchronized List<Joined> takeOrClose() {
            var batch = joined;

            joined = new ArrayList<>();
            closed = batch.isEmpty();

            return batch;
        }

        /// Closes the intent and hands out the callers that never ran.
        synchronized List<Joined> closeAll() {
            var batch = joined;

            joined = new ArrayList<>();
            closed = true;

            return batch;
        }
    }

    @Override
    public Promise<Unit> request(NodeId target, DrainGate gate) {
        return gate.ownerMonitorHeld()
               ? Promise.resolved(refuseUnderOwnerMonitor(target))
               : admit(target, gate);
    }

    @Override
    public Option<Promise<Unit>> inFlight(NodeId target) {
        return Option.option(intents.get(target)).map(Intent::outcome);
    }

    /// The targets with a witness in flight and the targets in the registry. The intents are read first and the registry second: a move
    /// adds to the registry before it removes the intent, so a concurrent read sees the target in at least one of the two sets.
    Set<NodeId> pending() {
        var inFlightTargets = Set.copyOf(intents.keySet());

        return Stream.concat(inFlightTargets.stream(),
                             drainTargets.get().stream())
                     .collect(Collectors.toUnmodifiableSet());
    }

    private Promise<Unit> admit(NodeId target, DrainGate gate) {
        return drainTargets.get()
                           .contains(target)
               ? Promise.resolved(runGate(target, gate))
               : joinOrOpen(target, Joined.joined(gate));
    }

    private Promise<Unit> joinOrOpen(NodeId target, Joined entry) {
        var fresh = Intent.intent(entry);

        return Option.option(intents.putIfAbsent(target, fresh)).fold(() -> open(target, fresh, entry),
                                                                      existing -> join(target, existing, entry));
    }

    private Promise<Unit> open(NodeId target, Intent intent, Joined entry) {
        submitWitness(target, intent);

        return entry.caller();
    }

    private Promise<Unit> join(NodeId target, Intent existing, Joined entry) {
        return existing.join(entry)
               ? entry.caller()
               : retryAfterClosed(target, existing, entry);
    }

    /// The witness took its last batch but has not removed its intent yet: help remove it, then decide again, so a target that the
    /// closed witness registered is answered as registered.
    private Promise<Unit> retryAfterClosed(NodeId target, Intent closed, Joined entry) {
        intents.remove(target, closed);

        return admit(target, entry.gate());
    }

    @Contract
    private void submitWitness(NodeId target, Intent intent) {
        port.<Unit> fenced(List.of(),
                           () -> runAccepted(target, intent))
            .onResult(result -> witnessEnded(target, intent, result));
    }

    /// A refused witness settles its callers first (the intent leaves `intents` before any of them is resolved), then the outcome that
    /// [#inFlight] hands out is resolved: one handler, so the order holds on the event thread. An accepted witness has settled in
    /// [#runAccepted] already.
    @Contract
    private void witnessEnded(NodeId target, Intent intent, Result<Unit> result) {
        intent.outcome().resolve(result.onFailure(cause -> failJoined(target, intent, cause)));
    }

    /// The accepted continuation: runs every joined gate, batch by batch, reads whether the target is registered, then settles the
    /// callers. The registry is read while the intent is closed and still present, so a request that arrives meanwhile is sent to a
    /// fresh decision by [#retryAfterClosed] against the registry this answer was read from.
    private Promise<Unit> runAccepted(NodeId target, Intent intent) {
        return settling(target, intent, runBatches(target, intent), registeredOrCancelled(target));
    }

    private Promise<Unit> registeredOrCancelled(NodeId target) {
        return drainTargets.get()
                           .contains(target)
               ? Promise.unitPromise()
               : AttemptCancelled.attemptCancelled().promise();
    }

    /// Settles the callers and hands `outcome` on: the argument is read before the intent leaves, the callers after.
    private Promise<Unit> settling(NodeId target, Intent intent, List<Settlement> answers, Promise<Unit> outcome) {
        settle(target, intent, answers);

        return outcome;
    }

    /// Runs the gates of every batch in order, until a take answers empty and closes the intent. Nothing is resolved here. The gates of a
    /// batch run before the next take: [#followedBy] receives its first argument evaluated, then asks for the rest.
    private List<Settlement> runBatches(NodeId target, Intent intent) {
        return runBatch(target, intent, intent.takeOrClose());
    }

    private List<Settlement> runBatch(NodeId target, Intent intent, List<Joined> batch) {
        return batch.isEmpty()
               ? List.of()
               : followedBy(gatesOf(target, batch), runBatches(target, intent));
    }

    private List<Settlement> gatesOf(NodeId target, List<Joined> batch) {
        return batch.stream()
                    .map(joined -> Settlement.settlement(joined,
                                                         runGate(target,
                                                                 joined.gate())))
                    .toList();
    }

    private static List<Settlement> followedBy(List<Settlement> first, List<Settlement> rest) {
        return Stream.concat(first.stream(),
                             rest.stream())
                     .toList();
    }

    @Contract
    private void failJoined(NodeId target, Intent intent, Cause cause) {
        settle(target,
               intent,
               intent.closeAll().stream().map(joined -> Settlement.settlement(joined, cause.<Unit> result())).toList());
    }

    /// The one settle path: the intent leaves `intents` first, then the callers are resolved. A caller, or a promise chained on a
    /// caller or on [#inFlight], therefore never observes its outcome while the request is still in flight.
    @Contract
    private void settle(NodeId target, Intent intent, List<Settlement> answers) {
        intents.remove(target, intent);
        answers.forEach(settlement -> settlement.joined()
                                                .caller()
                                                .resolve(settlement.answer()));
    }

    /// One caller's own gate: refused if it holds the owner's monitor, else the registry add under the gate, and the FSM call after the
    /// gate returned when the gate ran.
    private Result<Unit> runGate(NodeId target, DrainGate gate) {
        return gate.ownerMonitorHeld()
               ? refuseUnderOwnerMonitor(target)
               : runOwned(target, gate);
    }

    private Result<Unit> runOwned(NodeId target, DrainGate gate) {
        return gate.runIfOwned(() -> registryAdd.accept(target))
               ? callFsm(target)
               : AttemptCancelled.attemptCancelled().result();
    }

    private Result<Unit> callFsm(NodeId target) {
        Option.option(membershipFsmRef.get()).onPresent(fsm -> fsm.onDrainRequested(target));

        return unitResult();
    }

    private static Result<Unit> refuseUnderOwnerMonitor(NodeId target) {
        return CalledUnderOwnerMonitor.calledUnderOwnerMonitor(target)
                                      .<Unit> result()
                                      .onFailure(cause -> LOG.error("{}",
                                                                    cause.message()));
    }
}
