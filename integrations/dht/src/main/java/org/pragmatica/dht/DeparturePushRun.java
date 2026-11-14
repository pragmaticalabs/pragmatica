/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.dht;

import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.dht.storage.KeyStamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.utility.IdGenerator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// One graceful-departure push (#427, #2034): the plan, the per-page resend loop, the windows and the events.
///
/// **Classification (S).** A key is SOLE when no node outside the leaving set can be shown to hold it:
/// `|currentSet \ leaving| - |leaving \ ring.nodes()| <= 0`, where `currentSet` is the key's responsible set on this node's ring
/// and `leaving` is self plus the co-departing nodes. A leaving node already pruned from the ring vacated a slot that a node which
/// never held the key may now fill, so each one is subtracted; a leaving node still in the ring is already removed by the set
/// difference. The bound is a lower bound on surviving holders, so it errs toward "sole". When no leaving node is pruned the
/// survivors `currentSet \ leaving` are known exactly and kept as `existingAtPlan`; otherwise `existingAtPlan` is empty.
///
/// **Resend.** Every page is resent to the SAME target until an applied ack arrives: after `resendAfter` without an answer, after
/// `resendAfter` following a refused send, and after an exponential backoff (from `min(resendAfter, nackBackoffCap)` up to
/// `nackBackoffCap`) following a nack. A target is replaced only when it has left this node's ring; its keys are then re-planned
/// with the removed node added to `leaving`. `EncodeFailed` is final for the page. A replicated page stops at `baseDeadline`, a page
/// still holding an unacknowledged sole key at `soleDeadline`.
///
/// **Re-evaluation at `baseDeadline` (F7).** A replicated key whose every `existingAtPlan` member has left the ring is promoted to
/// sole, so its page continues to `soleDeadline`. A key with an empty `existingAtPlan` (a pruned leaving node) is never promoted:
/// its survivors are unknown, which is disclosed rather than guessed. A key's death detected later than `baseDeadline` is not seen.
///
/// **Events.** `onSoleCopyAtRisk` once, at the first of: plan time (a sole key without a target, or a window already used up), the
/// re-evaluation, or an inventory not enumerated in time. `onSoleCopyDelivered` at the end iff it was raised and no sole key is left
/// unacknowledged. `onIncompleteReport` at the end iff any key is unacknowledged. Each event's promise is awaited until the window's
/// event deadline. After a halt nothing more is sent or raised.
///
/// **Concurrency.** The re-evaluation (scheduler or chain thread), the at-risk raise and `finish` (often the thread that delivered
/// the last ack) mutate shared run state. Each such transition runs under one per-run lock, `transitions`, which guards ONLY in-memory
/// state: the transition records its log lines and observer calls as effects, and they run after the lock is released, in the
/// order their transitions queued them, one thread at a time ([#runEffects]). The lock is never held across a network send, a
/// promise await or an observer callback, so a thread that waits for it (the ack thread included) waits only for another thread's
/// short in-memory transition, never for I/O. This is what makes `finish` and every losing re-evaluation see the winner's promotion
/// and its raise: an event decided before `finish` is always in `events` when `finish` collects them, and a page is never stopped at
/// `baseDeadline` while a promotion that would extend it is in flight (#2684 F1, F2).
///
/// A late nack of an earlier attempt can settle the current one, since a page keeps its correlation id across resends; it costs
/// one backoff step.
final class DeparturePushRun {
    private static final Logger log = LoggerFactory.getLogger(DeparturePushRun.class);
    private static final int SAMPLE_LIMIT = 16;
    private static final HexFormat HEX = HexFormat.of();

    enum AttemptResult {
        ACKED,
        NACKED,
        TIMED_OUT
    }

    /// One held key and its departure state, shared by every page that carries it.
    static final class DepartureKey {
        private final KeyStamp stamp;
        private final AtomicBoolean sole;
        private final Set<NodeId> existingAtPlan;
        private final AtomicBoolean delivered = new AtomicBoolean(false);
        private final Set<NodeId> plannedTargets = ConcurrentHashMap.newKeySet();

        private DepartureKey(KeyStamp stamp, boolean sole, Set<NodeId> existingAtPlan) {
            this.stamp = stamp;
            this.sole = new AtomicBoolean(sole);
            this.existingAtPlan = existingAtPlan;
        }

        boolean isSole() {
            return sole.get();
        }

        boolean isDelivered() {
            return delivered.get();
        }

        boolean isLiveSole() {
            return isSole() && !isDelivered();
        }

        boolean isAtRisk() {
            return ! isDelivered();
        }

        String hex() {
            return HEX.formatHex(stamp.key());
        }

        @Contract
        void markDelivered() {
            delivered.set(true);
        }

        /// True when this target was not yet planned for the key (and records it).
        boolean claimTarget(NodeId target) {
            return plannedTargets.add(target);
        }

        /// F7: promotable when its plan-time survivors are known and every one has left the ring.
        boolean promoteIfSurvivorsGone(Set<NodeId> ringNodes) {
            return ! isSole()
                   && !isDelivered()
                   && !existingAtPlan.isEmpty()
                   && Collections.disjoint(existingAtPlan, ringNodes)
                   && sole.compareAndSet(false, true);
        }
    }

    /// One departure page, registered under its correlation id until an applied ack arrives.
    static final class Page {
        private final String correlationId;
        private final NodeId target;
        private final List<DepartureKey> keys;

        private final AtomicReference<Promise<AttemptResult>> attempt = new AtomicReference<>(Promise.promise());

        private Page(String correlationId, NodeId target, List<DepartureKey> keys) {
            this.correlationId = correlationId;
            this.target = target;
            this.keys = keys;
        }

        String correlationId() {
            return correlationId;
        }

        /// An ack for this page's correlation id: an applied ack delivers every key, then settles the current attempt.
        @Contract
        void settle(boolean applied) {
            if (applied) {
                keys.forEach(DepartureKey::markDelivered);
            }

            attempt.get().succeed(applied
                                  ? AttemptResult.ACKED
                                  : AttemptResult.NACKED);
        }

        /// Install the next attempt BEFORE the send: a network that answers inside `send` must find it.
        Promise<AttemptResult> nextAttempt() {
            Promise<AttemptResult> next = Promise.promise();

            attempt.set(next);

            return next;
        }

        boolean holdsLiveSoleKey() {
            return keys.stream()
                       .anyMatch(DepartureKey::isLiveSole);
        }

        List<KeyStamp> stamps() {
            return keys.stream()
                       .map(key -> key.stamp)
                       .toList();
        }
    }

    private record Dispatched(Promise<AttemptResult> attempt, WriteOutcome outcome) {}

    private record TargetedKey(NodeId target, DepartureKey key) {}

    private final DHTNode node;
    private final DHTNetwork network;
    private final ConcurrentHashMap<String, Page> registry;
    private final int replicationFactor;
    private final Set<NodeId> leaving;
    private final DeparturePushTuning tuning;
    private final DepartureWindow window;
    private final DeparturePushObserver observer;
    private final long attemptsDeadline;
    private final long baseDeadline;
    private final long soleDeadline;
    private final List<DepartureKey> keys = new CopyOnWriteArrayList<>();
    private final List<Page> pages = new CopyOnWriteArrayList<>();
    private final List<Promise<Unit>> events = new CopyOnWriteArrayList<>();
    private final AtomicBoolean atRiskRaised = new AtomicBoolean(false);
    private final AtomicInteger atRiskCount = new AtomicInteger();
    private final AtomicBoolean reevaluated = new AtomicBoolean(false);
    private final Object transitions = new Object();
    private final ConcurrentLinkedQueue<Runnable> effects = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainingEffects = new AtomicBoolean(false);

    private DeparturePushRun(DHTNode node,
                             DHTNetwork network,
                             ConcurrentHashMap<String, Page> registry,
                             int replicationFactor,
                             Set<NodeId> leaving,
                             long t0,
                             DepartureWindow window,
                             DeparturePushTuning tuning,
                             DeparturePushObserver observer) {
        this.node = node;
        this.network = network;
        this.registry = registry;
        this.replicationFactor = replicationFactor;
        this.leaving = ConcurrentHashMap.newKeySet();
        this.leaving.addAll(leaving);
        this.tuning = tuning;
        this.window = window;
        this.observer = observer;
        this.attemptsDeadline = Math.max(t0,
                                         window.deadlineNanos() - tuning.eventDeliveryWait().nanos());
        this.baseDeadline = Math.max(t0,
                                     Math.min(t0 + tuning.baseBudget().nanos(),
                                              attemptsDeadline));
        this.soleDeadline = Math.max(t0,
                                     Math.min(t0 + tuning.baseBudget().nanos() + tuning.soleExtra().nanos(),
                                              attemptsDeadline));
    }

    /// `t0` is taken by the caller on entry, before the inventory is enumerated (F2): a slow `stamps()` eats the window.
    static DeparturePushRun departurePushRun(DHTNode node,
                                             DHTNetwork network,
                                             ConcurrentHashMap<String, Page> registry,
                                             int replicationFactor,
                                             Set<NodeId> leaving,
                                             long t0,
                                             DepartureWindow window,
                                             DeparturePushTuning tuning,
                                             DeparturePushObserver observer) {
        return new DeparturePushRun(node, network, registry, replicationFactor, leaving, t0, window, tuning, observer);
    }

    /// Enumerate, plan, push, report. Always succeeds.
    Promise<DeparturePushReport> run() {
        return node.storage()
                   .stamps()
                   .timeout(remainingUntil(attemptsDeadline))
                   .fold(enumerated -> enumerated.fold(this::notEnumerated, this::execute));
    }

    // --- enumeration and plan ---
    /// The inventory could not be listed before the attempts deadline (or listing failed): the at-risk set is unknown. FER: the
    /// push degrades forward to an at-risk report with count 0, because it must never gate the halt.
    private Promise<DeparturePushReport> notEnumerated(Cause cause) {
        log.warn("Departure push: inventory not enumerated before the drain deadline ({}): at-risk set unknown",
                 cause.message());
        if (!window.halted().getAsBoolean()) {
            transition(() -> raiseAtRisk(List.of()));
        }

        return awaitEvents(DeparturePushReport.NOT_ENUMERATED);
    }

    private Promise<DeparturePushReport> execute(List<KeyStamp> stamps) {
        var chains = plan(stamps);

        raiseAtPlan();
        var reevaluation = SharedScheduler.schedule(this::reevaluate, remainingUntil(baseDeadline));

        return Promise.allOf(chains)
                      .timeout(remainingUntil(backstopDeadline()))
                      .withResult(_ -> reevaluation.cancel(false))
                      .fold(_ -> finish());
    }

    /// Classify every key, then group its targets into two chains per target (sole pages, replicated pages), so a stuck page of
    /// one kind cannot starve the other (N2).
    /// ```tla DHTDepartureDrain:Plan
    /// \* The push is built (DHTRebalancer.pushOnDeparture: rf at the call, leaving = the drain set d received + self) and planned:
    /// \* one page per initial target. A key with no target sends nothing (delivered when replicated, at risk when sole).
    /// Plan(d) ==
    ///     /\ phase[d] = "draining"
    ///     /\ LET excl == (IF Fix("codrain") THEN known[d] ELSE {}) \cup {d}
    ///            rf   == RFOf(Ring(d))
    ///            tgts == IF store[d] THEN DepartureTargets(d, excl, rf) ELSE {}
    ///        IN /\ leaving' = [leaving EXCEPT ![d] = excl]
    ///           /\ rfAt' = [rfAt EXCEPT ![d] = rf]
    ///           /\ claimed' = [claimed EXCEPT ![d] = tgts]
    ///           /\ pages' = pages \cup {Page(d, t) : t \in tgts}
    ///     /\ phase' = [phase EXCEPT ![d] = "pushing"]
    ///     /\ UNCHANGED <<drainers, held0, stale, drainSet, known, pruned, flapped, store, strayNack>>
    /// ```
    private List<Promise<Unit>> plan(List<KeyStamp> stamps) {
        var ringNodes = Set.copyOf(node.ring().nodes());
        var targeted = stamps.stream().map(stamp -> classify(stamp, ringNodes)).flatMap(this::initialTargets).toList();

        return Stream.of(true, false)
                     .flatMap(sole -> chainsOfKind(targeted, sole))
                     .toList();
    }

    private DepartureKey classify(KeyStamp stamp, Set<NodeId> ringNodes) {
        var currentSet = node.ring().nodesFor(stamp.key(), replicationFactor);
        var survivors = new HashSet<>(currentSet);

        survivors.removeAll(leaving);
        var pruned = leaving.stream().filter(member -> !ringNodes.contains(member)).count();
        var key = new DepartureKey(stamp,
                                   survivors.size() - pruned <= 0,
                                   pruned == 0
                                   ? Set.copyOf(survivors)
                                   : Set.of());

        keys.add(key);

        return key;
    }

    /// A replicated key with no target already has every post-departure replica (a copy self held outside its responsible set):
    /// nothing is owed. A sole key with no target is unpushable and stays at risk.
    private Stream<TargetedKey> initialTargets(DepartureKey key) {
        var targets = departureTargets(key.stamp.key(), Set.copyOf(leaving));

        if (targets.isEmpty() && !key.isSole()) {
            key.markDelivered();
        }

        return targets.stream()
                      .filter(key::claimTarget)
                      .map(target -> new TargetedKey(target, key));
    }

    private Stream<Promise<Unit>> chainsOfKind(List<TargetedKey> targeted, boolean sole) {
        return targeted.stream()
                       .filter(entry -> entry.key()
                                             .isSole() == sole)
                       .collect(Collectors.groupingBy(TargetedKey::target,
                                                      Collectors.mapping(TargetedKey::key,
                                                                         Collectors.toList())))
                       .entrySet()
                       .stream()
                       .map(entry -> runChain(registerPages(entry.getKey(),
                                                            entry.getValue()),
                                              0));
    }

    /// Post-departure delta target set for one key (issue #427, D3). `newSet` is the responsible set with every `leaving` node
    /// excluded — self and each co-departing node (issue #1818), plus any target that left the ring mid-push (#2034) — via the
    /// filtered ring overload, robust whether or not they are still in the ring. When self is still a ring member (the designed
    /// drain ordering), the nodes already holding the key are the members of the current responsible set that are NOT leaving, so
    /// only the genuine newcomers (`newSet \ existing`) are targeted. When self has ALREADY been pruned (the ring can no longer
    /// identify the newcomer), fall back to the whole `newSet`: the versioned puts are idempotent, so re-sending to an existing
    /// replica is harmless, and this guarantees no loss regardless of the prune-vs-drain ordering. The same fallback applies when
    /// the exclusion exhausts the ring (`newSet` shorter than the replication factor): no node lies beyond the vacated slots to
    /// absorb the push, so a node wrongly in `leaving` would otherwise remove the only newcomer and leave the copy resting on
    /// survivors that may not hold it. The fallback applies too when any excluded node has already left the ring: the node now in
    /// its slot may never have held the key, the same rule by which classification counts the key sole (#2684 F3).
    private List<NodeId> departureTargets(byte[] key, Set<NodeId> excluded) {
        var newSet = node.ring().nodesFor(key, replicationFactor, candidate -> !excluded.contains(candidate));
        var currentSet = node.ring().nodesFor(key, replicationFactor);
        var ringNodes = Set.copyOf(node.ring().nodes());

        return currentSet.contains(node.nodeId()) && newSet.size() >= replicationFactor && ringNodes.containsAll(excluded)
               ? excludeExistingReplicas(newSet, currentSet, excluded)
               : newSet;
    }

    private static List<NodeId> excludeExistingReplicas(List<NodeId> newSet,
                                                        List<NodeId> currentSet,
                                                        Set<NodeId> excluded) {
        var existing = new HashSet<>(currentSet);

        existing.removeAll(excluded);

        return newSet.stream()
                     .filter(candidate -> !existing.contains(candidate))
                     .toList();
    }

    /// Every page of a chain is registered before the first leaves, so a window that ends mid-push reports the unsent pages too.
    private List<Page> registerPages(NodeId target, List<DepartureKey> chainKeys) {
        var byStamp = new IdentityHashMap<KeyStamp, DepartureKey>();

        chainKeys.forEach(key -> byStamp.put(key.stamp, key));

        return node.repair()
                   .pageBudget()
                   .pages(chainKeys.stream().map(key -> key.stamp).toList())
                   .stream()
                   .map(page -> registerPage(target,
                                             page.stream().map(byStamp::get).toList()))
                   .toList();
    }

    private Page registerPage(NodeId target, List<DepartureKey> pageKeys) {
        var page = new Page(IdGenerator.generate(), target, pageKeys);

        registry.put(page.correlationId(), page);
        pages.add(page);

        return page;
    }

    // --- chains and the attempt loop ---
    private Promise<Unit> runChain(List<Page> chain, int index) {
        return index >= chain.size()
               ? Promise.unitPromise()
               : runPage(chain.get(index)).flatMap(_ -> runChain(chain, index + 1));
    }

    /// The page's entries are read once and the same list is resent (N5): every apply is versioned, tombstones included, so a stale
    /// resend never overwrites a newer copy. A read that fails or outlives the page's window leaves the page pending (FER: it is
    /// reported at risk instead of gating the halt).
    private Promise<Unit> runPage(Page page) {
        return shouldStop(page)
               ? Promise.unitPromise()
               : node.readEntries(page.stamps())
                     .timeout(remainingUntil(deadlineOf(page)))
                     .fold(read -> read.fold(cause -> unreadable(page, cause),
                                             entries -> sendRead(page, entries)));
    }

    private Promise<Unit> unreadable(Page page, Cause cause) {
        log.warn("Departure push: page {} for {} not read ({}); its keys stay at risk",
                 page.correlationId(),
                 page.target.id(),
                 cause.message());

        return Promise.unitPromise();
    }

    private Promise<Unit> sendRead(Page page, List<DHTMessage.KeyValue> entries) {
        return entries.isEmpty()
               ? Promise.success(settleEmptyPage(page))
               : attempt(page, entries, initialNackBackoff());
    }

    /// Every key of the page was removed meanwhile: nothing is owed, so nothing is at risk.
    private Unit settleEmptyPage(Page page) {
        registry.remove(page.correlationId());
        page.keys.forEach(DepartureKey::markDelivered);

        return Unit.unit();
    }

    private Promise<Unit> attempt(Page page, List<DHTMessage.KeyValue> entries, TimeSpan nackBackoff) {
        if (shouldStop(page)) {
            return Promise.unitPromise();
        }

        return node.ring()
                   .nodes()
                   .contains(page.target)
               ? dispatch(page, entries).flatMap(dispatched -> afterSend(page, entries, dispatched, nackBackoff))
               : retarget(page);
    }

    /// The push carries `leaving` (plus every target removed mid-push), the set excluded when choosing its target (#1818 L1), so the
    /// receiver checks placement against the same view rather than its own.
    private Promise<Dispatched> dispatch(Page page, List<DHTMessage.KeyValue> entries) {
        var attempt = page.nextAttempt();

        log.debug("Departure-pushing a page of {} chunk(s) to {} (ack {})",
                  entries.size(),
                  page.target.id(),
                  page.correlationId());
        node.repair().notePageSent(PageBudget.transferBytes(entries));

        return network.send(page.target,
                            new DHTMessage.MigrationDataResponse(page.correlationId(),
                                                                 node.nodeId(),
                                                                 entries,
                                                                 true,
                                                                 List.copyOf(leaving),
                                                                 List.copyOf(node.ring().nodes())))
                      .recover(_ -> new WriteOutcome.ConnectionDead(page.target))
                      .map(outcome -> new Dispatched(attempt, outcome));
    }

    /// Per `WriteOutcome`: `Sent` waits for the attempt; a refusal proves nothing about the ring, so the SAME target is retried
    /// after `resendAfter` (H2); `EncodeFailed` cannot be helped by a retry, so the page stays pending and is reported.
    private Promise<Unit> afterSend(Page page,
                                    List<DHTMessage.KeyValue> entries,
                                    Dispatched dispatched,
                                    TimeSpan nackBackoff) {
        return switch (dispatched.outcome()) {
            case WriteOutcome.Sent _ -> dispatched.attempt().timeout(attemptWait(page)).recover(_ -> AttemptResult.TIMED_OUT).flatMap(result -> afterAttempt(page,
                                                                                                                                                             entries,
                                                                                                                                                             result,
                                                                                                                                                             nackBackoff));
            case WriteOutcome.EncodeFailed failed -> encodeFailed(page, failed);
            case WriteOutcome.BackpressureRefused _, WriteOutcome.ConnectionDead _, WriteOutcome.NoPeerState _ -> pause(page,
                                                                                                                        tuning.resendAfter()).flatMap(_ -> attempt(page,
                                                                                                                                                                   entries,
                                                                                                                                                                   nackBackoff));
        };
    }

    private Promise<Unit> afterAttempt(Page page,
                                       List<DHTMessage.KeyValue> entries,
                                       AttemptResult result,
                                       TimeSpan nackBackoff) {
        return switch (result) {
            case ACKED -> Promise.unitPromise();
            case NACKED -> pause(page, nackBackoff).flatMap(_ -> attempt(page, entries, nextNackBackoff(nackBackoff)));
            case TIMED_OUT -> attempt(page, entries, nackBackoff);
        };
    }

    private Promise<Unit> encodeFailed(Page page, WriteOutcome.EncodeFailed failed) {
        log.error("Departure push: page {} for {} cannot be encoded ({}); its keys stay at risk",
                  page.correlationId(),
                  page.target.id(),
                  failed.messageType());

        return Promise.unitPromise();
    }

    /// Ring removal is the only retarget (H2): a target still in the ring is never replaced, because a replacement would be a stray.
    /// The removed node joins `leaving`, so the receiver computes the same post-departure view (#1818 L1: an extra copy, never a
    /// missing one). A key with no new target stays at risk.
    private Promise<Unit> retarget(Page page) {
        return Option.option(registry.remove(page.correlationId())).fold(Promise::unitPromise, this::replan);
    }

    /// ```tla DHTDepartureDrain:Retarget
    /// \* An attempt finds its target gone from d's ring: the page is dropped, the target joins leaving, and the key is re-planned
    /// \* to targets it has not claimed yet (claimTarget).
    /// Retarget(d, t) ==
    ///     /\ Pending(d, t)
    ///     /\ t \notin Ring(d)
    ///     /\ LET lv  == leaving[d] \cup {t}
    ///            new == DepartureTargets(d, lv, rfAt[d]) \ claimed[d]
    ///        IN /\ leaving' = [leaving EXCEPT ![d] = lv]
    ///           /\ claimed' = [claimed EXCEPT ![d] = @ \cup new]
    ///           /\ pages' = (pages \ {Page(d, t)}) \cup {Page(d, n) : n \in new}
    ///     /\ UNCHANGED <<drainers, held0, stale, drainSet, known, pruned, flapped, store, phase, rfAt, strayNack>>
    /// ```
    private Promise<Unit> replan(Page page) {
        leaving.add(page.target);
        log.info("Departure push: target {} left the ring; re-planning {} chunk(s)", page.target.id(), page.keys.size());
        var excluded = Set.copyOf(leaving);
        var regrouped = page.keys.stream()
                                 .flatMap(key -> retargets(key, excluded))
                                 .collect(Collectors.groupingBy(TargetedKey::target,
                                                                Collectors.mapping(TargetedKey::key,
                                                                                   Collectors.toList())));

        return Promise.allOf(regrouped.entrySet()
                                      .stream()
                                      .map(entry -> runChain(registerPages(entry.getKey(),
                                                                           entry.getValue()),
                                                             0))
                                      .toList()).mapToUnit();
    }

    private Stream<TargetedKey> retargets(DepartureKey key, Set<NodeId> excluded) {
        return departureTargets(key.stamp.key(),
                                excluded).stream()
                               .filter(key::claimTarget)
                               .map(target -> new TargetedKey(target, key));
    }

    /// ```tla DHTDepartureDrain:Halt
    /// \* d halts with its in-memory store: once every page is acknowledged, or (Deadline) with pages pending when a window is
    /// \* used up (window.halted / pastDeadline end every attempt).
    /// Halt(d) ==
    ///     /\ phase[d] = "pushing"
    ///     /\ Deadline \/ \A p \in pages : p.from # d
    ///     /\ phase' = [phase EXCEPT ![d] = "halted"]
    ///     /\ store' = [store EXCEPT ![d] = FALSE]
    ///     /\ pages' = {p \in pages : p.from # d}
    ///     \* A halted node's local state is gone; resetting it keeps equivalent states equal (nothing reads it again).
    ///     /\ known' = [known EXCEPT ![d] = {}]
    ///     /\ pruned' = [pruned EXCEPT ![d] = {}]
    ///     /\ flapped' = [flapped EXCEPT ![d] = {}]
    ///     /\ leaving' = [leaving EXCEPT ![d] = {}]
    ///     /\ rfAt' = [rfAt EXCEPT ![d] = 0]
    ///     /\ claimed' = [claimed EXCEPT ![d] = {}]
    ///     /\ UNCHANGED <<drainers, held0, stale, drainSet, strayNack>>
    /// ```
    private boolean shouldStop(Page page) {
        return window.halted()
                     .getAsBoolean() || !registry.containsKey(page.correlationId()) || pastDeadline(page);
    }

    /// Past `baseDeadline` the F7 re-evaluation runs (once) before a replicated page gives up, so a promotion is never missed by a
    /// page that reaches its deadline before the timer fires.
    private boolean pastDeadline(Page page) {
        if (System.nanoTime() >= baseDeadline) {
            reevaluate();
        }

        return System.nanoTime() >= deadlineOf(page);
    }

    private long deadlineOf(Page page) {
        return page.holdsLiveSoleKey()
               ? soleDeadline
               : baseDeadline;
    }

    private TimeSpan attemptWait(Page page) {
        return timeSpan(Math.min(tuning.resendAfter().nanos(),
                                 remainingNanos(deadlineOf(page)))).nanos();
    }

    private Promise<Unit> pause(Page page, TimeSpan delay) {
        return Promise.promise(timeSpan(Math.min(delay.nanos(), remainingNanos(deadlineOf(page)))).nanos(),
                               Result::unitResult);
    }

    private TimeSpan initialNackBackoff() {
        return timeSpan(Math.min(tuning.resendAfter().nanos(),
                                 tuning.nackBackoffCap().nanos())).nanos();
    }

    private TimeSpan nextNackBackoff(TimeSpan current) {
        return timeSpan(Math.min(current.nanos() * 2,
                                 tuning.nackBackoffCap().nanos())).nanos();
    }

    /// E3: the backstop must not cut off a page promoted at `baseDeadline`, so it is `soleDeadline` whenever a sole key exists or a
    /// replicated key could be promoted.
    private long backstopDeadline() {
        return keys.stream()
                   .anyMatch(key -> key.isSole() || !key.existingAtPlan.isEmpty())
               ? soleDeadline
               : baseDeadline;
    }

    // --- events ---
    /// Plan time: a sole key without a target can never be delivered, and a window already used up delivers nothing.
    private void raiseAtPlan() {
        var unpushable = keys.stream().anyMatch(key -> key.isSole() && key.plannedTargets.isEmpty());

        if (!window.halted().getAsBoolean() && (unpushable || System.nanoTime() >= attemptsDeadline)) {
            transition(this::raiseIfSoleAtRisk);
        }
    }

    /// F7 re-evaluation, once, at `baseDeadline` (or at the end, whichever comes first): promote, then raise if sole keys remain.
    /// A caller that loses the race waits on `transitions` until the winner's promotion and raise are complete (#2684 F2).
    @Contract
    private void reevaluate() {
        if (!window.halted().getAsBoolean()) {
            transition(this::reevaluateOnce);
        }
    }

    /// Under `transitions`.
    private Unit reevaluateOnce() {
        return reevaluated.compareAndSet(false, true)
               ? promoteThenRaise()
               : Unit.unit();
    }

    /// Under `transitions`. The ring read takes only the ring's own in-memory lock.
    private Unit promoteThenRaise() {
        var ringNodes = Set.copyOf(node.ring().nodes());
        var promoted = keys.stream().filter(key -> key.promoteIfSurvivorsGone(ringNodes)).count();

        if (promoted > 0) {
            effects.add(() -> log.warn("Departure push: {} replicated chunk(s) lost every plan-time survivor; treated as sole copies",
                                       promoted));
        }

        return raiseIfSoleAtRisk();
    }

    /// Under `transitions`.
    private Unit raiseIfSoleAtRisk() {
        var atRisk = soleAtRisk();

        return atRisk.isEmpty()
               ? Unit.unit()
               : raiseAtRisk(atRisk);
    }

    /// Once per push, under `transitions`; the caller has checked `halted`. An empty list means the inventory is unknown (count 0).
    private Unit raiseAtRisk(List<DepartureKey> atRisk) {
        if (atRiskRaised.compareAndSet(false, true)) {
            atRiskCount.set(atRisk.size());
            effects.add(() -> log.warn("Departure push: {} sole-copy chunk(s) not yet acknowledged by any target: {}",
                                       atRisk.size(),
                                       hexOf(atRisk)));
            observe(() -> observer.onSoleCopyAtRisk(atRisk.size(), sample(atRisk)));
        }

        return Unit.unit();
    }

    /// The closing transition waits for any re-evaluation in flight, so an at-risk raise decided by another thread is always
    /// followed by its delivered event and is in `events` before they are awaited (#2684 F1).
    private Promise<DeparturePushReport> finish() {
        var halted = window.halted().getAsBoolean();
        var report = transition(() -> close(halted));

        pages.forEach(page -> registry.remove(page.correlationId()));

        return awaitEvents(report);
    }

    /// Under `transitions`.
    private DeparturePushReport close(boolean halted) {
        if (!halted) {
            reevaluateOnce();
        }

        var sole = soleAtRisk();
        var replicated = keys.stream().filter(key -> !key.isSole() && key.isAtRisk()).toList();

        if (!halted) {
            reportOutcome(sole, replicated);
        }

        return new DeparturePushReport(sole.size(), replicated.size(), true);
    }

    /// Under `transitions`.
    private void reportOutcome(List<DepartureKey> sole, List<DepartureKey> replicated) {
        if (atRiskRaised.get() && sole.isEmpty()) {
            effects.add(() -> log.info("Departure push: every sole-copy chunk was acknowledged"));
            observe(() -> observer.onSoleCopyDelivered(atRiskCount.get()));
        }

        if (!sole.isEmpty() || !replicated.isEmpty()) {
            var atRisk = Stream.concat(sole.stream(), replicated.stream()).toList();

            effects.add(() -> log.warn("Departure push incomplete: {} chunk(s) unacknowledged ({} sole-copy): {}",
                                       atRisk.size(),
                                       sole.size(),
                                       hexOf(atRisk)));
            observe(() -> observer.onIncompleteReport(atRisk.size(), sole.size(), sample(atRisk)));
        }
    }

    /// One state transition: `change` runs under `transitions` and touches in-memory state only; the effects it queued run after
    /// the lock is released.
    private <T> T transition(Supplier<T> change) {
        var result = underTransitions(change);

        runEffects();

        return result;
    }

    private <T> T underTransitions(Supplier<T> change) {
        synchronized (transitions) {
            return change.get();
        }
    }

    /// Runs the queued effects in queue order on one thread at a time, never under `transitions`. A thread that finds another
    /// draining leaves its effects to it; the drainer re-checks the queue after clearing its flag, so no effect is stranded.
    @Contract
    private void runEffects() {
        if (drainingEffects.compareAndSet(false, true)) {
            Stream.generate(effects::poll).takeWhile(Objects::nonNull).forEach(Runnable::run);
            drainingEffects.set(false);
            if (!effects.isEmpty()) {
                runEffects();
            }
        }
    }

    /// The events are awaited until the window's deadline, or for `eventDeliveryWait` when that leaves less (a late quiesce, E4),
    /// but never past `eventFloorNanos`. FER: an event still in flight then is not awaited further; the halt must not wait on it.
    private Promise<DeparturePushReport> awaitEvents(DeparturePushReport report) {
        var now = System.nanoTime();
        var eventsDeadline = Math.max(window.deadlineNanos(),
                                      Math.min(now + tuning.eventDeliveryWait().nanos(),
                                               window.eventFloorNanos()));

        return window.halted()
                     .getAsBoolean()
               ? Promise.success(report)
               : Promise.allOf(List.copyOf(events))
                        .timeout(remainingUntil(eventsDeadline))
                        .fold(_ -> Promise.success(report));
    }

    /// Under `transitions`: the event's promise joins `events` now, so a later `finish` awaits it; the observer is called later, as
    /// an effect. An observer that throws is isolated: its event is lost, never the push.
    private void observe(Supplier<Promise<Unit>> raise) {
        Promise<Unit> event = Promise.promise();

        events.add(event);
        effects.add(() -> callObserver(raise, event));
    }

    private static void callObserver(Supplier<Promise<Unit>> raise, Promise<Unit> event) {
        Result.lift(Causes::fromThrowable, raise::get)
              .fold(DeparturePushRun::observerFailed, promise -> promise)
              .onResult(event::resolve);
    }

    private static Promise<Unit> observerFailed(Cause cause) {
        log.warn("Departure push: observer failed: {}", cause.message());

        return Promise.unitPromise();
    }

    private List<DepartureKey> soleAtRisk() {
        return keys.stream()
                   .filter(DepartureKey::isLiveSole)
                   .toList();
    }

    private static List<String> sample(List<DepartureKey> atRisk) {
        return atRisk.stream()
                     .limit(SAMPLE_LIMIT)
                     .map(DepartureKey::hex)
                     .toList();
    }

    private static String hexOf(List<DepartureKey> atRisk) {
        return atRisk.stream()
                     .map(DepartureKey::hex)
                     .collect(Collectors.joining(","));
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private static TimeSpan remainingUntil(long deadline) {
        return timeSpan(remainingNanos(deadline)).nanos();
    }
}
