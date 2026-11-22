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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.dht.storage.KeyStamp;
import org.pragmatica.hlc.HlcTimestamp;
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


/// One graceful-departure push (#427, #2034, #3345), restated from one definition: WHO IS A HOLDER.
///
/// **(I) Holder.** Node x holds key k for this drain iff this run has an applied ack for a page carrying k that (i) answered a
/// page registered in x's CURRENT tenure (a tenure is one stay of x in this node's ring view V; every entry of x into V starts a
/// new one, and a page carries the tenure it was registered in), (ii) arrived while x was in V in that tenure, and (iii) carries
/// the boot token x's first counted ack recorded. Safe is per (key, stamp): an ack counts only for the stamps its page carried, so
/// a newer local stamp of an acked key is owed again. `Safe(k)` = some holder of k at k's current stamp is one of k's deliverers
/// in V ([#drainTargets]); at a close (a passed exit or the deadline) that holder must also have passed a probe sent after the ack.
/// Every decision asks this ONE predicate ([#safe], I2): the re-plan, the send, resend and split of a page, the exit and the close.
/// `Owed = inventory - Safe`. Nothing else counts: not responsible-set membership, not a plan-time survivor,
/// not a target without an ack. A page of x's previous tenure is dead from the next re-plan on (never sent, split or acked, its keys
/// are paged again in the current tenure); within one tenure a late ack (its page cancelled by a re-plan) still counts.
///
/// **Owed targets (P1).** [#owedTargets] is the ONE definition of whom a key is owed to: the nodes that gain it in V, less its
/// confirmed holders. The first plan and every re-plan desire exactly those pairs, and a re-plan cancels a page only when its target
/// is owed none of the keys it carries. Safe at one deliverer is a close condition (the exit and the deadline), never a reason to
/// cancel: a planned page to a target still owed its key is sent, and no exit starts before its chain ends.
///
/// **May act (P2).** One predicate, [#mayAct(Page)] (registered, not cancelled, current: its target in V in the page's tenure),
/// is asked by send, resend, split and the count of live chains alike, read from V and never from the live ring, so a page a
/// re-plan cancelled is dead like a page of an earlier tenure: never sent, resent or split, and it holds no exit.
///
/// **Probe.** A probe that gets no answer is sent again while its window lasts and is never a lost holder (no re-push follows);
/// only a refusal in its answer (a "draining" nack or a token mismatch) is.
///
/// **Re-plan.** One serial [#reconcile] on exactly three triggers: a change of V ([#onViewChange], called after each ring change),
/// a holder lost (a token mismatch, a "draining" nack, a refused probe), and the start and each exit attempt. Acks and nacks never
/// trigger it: an ack records a holder and settles its page, a nack splits a page of several keys in halves after a backoff and
/// resends a single-key page after a growing one, and an attempt timed out splits likewise without the backoff (P1); a split, a
/// send and a resend carry only the keys not yet safe at the target, and a page with none left is not sent. A reconcile observes
/// the ring ONCE (I1: one snapshot gives V, `L = self + drainSet`, the tenures and every target of the re-plan), computes the
/// desired pages {(t, k): t in [#owedTargets](k)}, cancels every live page whose target is owed none of its keys (it stays
/// registered, so its late ack still counts), and registers the missing ones.
///
/// **Inventory and the fence (#3345 R4, marked guess G3).** Owed is frozen at the fence: owed keys are the keys of the first
/// inventory plus keys a later re-read finds whose stamp's HLC wall time is before the window's `drainStartMillis`. Anything else a
/// re-read finds came from a current holder through anti-entropy and is not owed. A key a page read no longer finds is ABSENT: it
/// does not block the exit attempt, whose re-read drops it (a removed key is never reported delivered) or finds it again.
///
/// **Exit.** When nothing sendable is owed: re-read the inventory, reconcile, and if `Owed` is empty send an EMPTY page to every
/// distinct holder Safe relies on. The exit passes iff each answers (ack or plain nack) within `resendAfter`, in its tenure, with
/// its recorded token, and no holder was lost meanwhile; a failure clears that holder and the run re-plans.
///
/// **Undeliverable.** A page that cannot be encoded is split once into single-key pages; a key whose own page still fails is
/// UNDELIVERABLE: it stays owed (it is in the owed digest) and is counted apart.
///
/// **Deadline and events.** At the window deadline less `eventDeliveryWait` (the attempts deadline) no data page or exit starts;
/// unless the node halted (then it sends nothing), the run sends ONE probe round to every holder whose counted ack for a key owed
/// at the close no passed probe covers yet, waits at most `resendAfter` (a silent probe is sent again while that time lasts), and
/// resolves with its owed set: a key whose holder passed that probe is delivered, any other is owed; a probe the deadline cuts
/// short is no lost holder and covers nothing ([#onDeadline]). The cost: a deadline close may resolve up to one `resendAfter`
/// after the attempts deadline. This is the one resolve point of this step besides a passed exit. Classification (S) of a key as SOLE (`|currentSet \ L| - |L \ ring| <= 0` at
/// the time it entered the inventory) only shapes the events: `onSoleCopyAtRisk` once, at plan time when a sole key has no
/// deliverer or the window is already used up (or the inventory was not enumerated), else at the deadline when a sole key is
/// owed; `onSoleCopyDelivered` at the end iff it was raised and no sole key is owed, `onIncompleteReport` at the end iff anything
/// is owed. After a halt nothing more is sent or raised, and the run resolves within one `resendAfter` of it.
///
/// **Concurrency.** Every read and write of run state happens under `transitions`, which is never held across a send, an await or
/// an observer call; log lines and observer calls queued under it run after it is released, in queue order ([#runEffects]).
final class DeparturePushRun {
    private static final Logger log = LoggerFactory.getLogger(DeparturePushRun.class);
    private static final int SAMPLE_LIMIT = 16;
    private static final long MIN_HALT_WATCH_NANOS = 1_000_000L;
    private static final HexFormat HEX = HexFormat.of();
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    enum AttemptResult {
        ACKED,
        NACKED,
        FAILED,
        IGNORED,
        TIMED_OUT
    }

    /// One copy's identity: the key and the stamp it carried.
    record StampId(String key, long version, long epochIncarnation, long epochTerm, long epochCounter) {
        static StampId stampId(KeyStamp stamp) {
            return new StampId(HEX.formatHex(stamp.key()),
                               stamp.version(),
                               stamp.epochIncarnation(),
                               stamp.epochTerm(),
                               stamp.epochCounter());
        }

        String digestText() {
            return key + ":" + version + ":" + epochIncarnation + ":" + epochTerm + ":" + epochCounter;
        }
    }

    /// One inventory key. Guarded by `transitions`.
    static final class DepartureKey {
        private KeyStamp stamp;
        private final boolean sole;
        private boolean absent;
        private boolean undeliverable;

        private DepartureKey(KeyStamp stamp, boolean sole) {
            this.stamp = stamp;
            this.sole = sole;
        }

        String hex() {
            return HEX.formatHex(stamp.key());
        }

        StampId stampId() {
            return StampId.stampId(stamp);
        }
    }

    /// A holder's record in one tenure, guarded by `transitions`: the tenure its first counted ack answered, that ack's token, each
    /// counted stamp with the sequence number of its ack, and the mark of the last probe it passed.
    private static final class Holder {
        private final long token;
        private final int tenure;
        private final Map<StampId, Long> copies = new HashMap<>();
        private long probedAt;

        private Holder(long token, int tenure) {
            this.token = token;
            this.tenure = tenure;
        }

        static Holder holder(long token, int tenure) {
            return new Holder(token, tenure);
        }
    }

    /// The targets of one key in the current view, and those whose holding makes it safe.
    private record Targets(List<NodeId> all, List<NodeId> deliverers) {}

    /// The outcome of one reconcile: the chains to start once `transitions` is released.
    private record Plan(List<List<Page>> chains) {}

    /// One departure page, registered under its correlation id until the end of the run; a page that is no longer current never counts.
    static final class Page {
        private final DeparturePushRun run;
        private final String correlationId;
        private final NodeId target;
        private final int tenure;
        private final List<DepartureKey> keys;
        private final boolean split;
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean finished = new AtomicBoolean(false);

        private final AtomicReference<Promise<AttemptResult>> attempt = new AtomicReference<>(Promise.promise());

        private final AtomicReference<List<StampId>> sent = new AtomicReference<>(List.of());

        private Page(DeparturePushRun run, NodeId target, int tenure, List<DepartureKey> keys, boolean split) {
            this.run = run;
            this.correlationId = IdGenerator.generate();
            this.target = target;
            this.tenure = tenure;
            this.keys = keys;
            this.split = split;
        }

        String correlationId() {
            return correlationId;
        }

        NodeId target() {
            return target;
        }

        boolean isProbe() {
            return keys.isEmpty();
        }

        /// An ack for this page's correlation id: the run decides whether it counts.
        @Contract
        void onAck(DHTMessage.MigrationDataAck ack) {
            run.onAck(this, ack);
        }

        @Contract
        void settle(AttemptResult result) {
            attempt.get().succeed(result);
        }

        /// Install the next attempt BEFORE the send: a network that answers inside `send` must find it.
        Promise<AttemptResult> nextAttempt() {
            Promise<AttemptResult> next = Promise.promise();

            attempt.set(next);

            return next;
        }

        List<KeyStamp> stamps() {
            return run.underTransitions(() -> keys.stream()
                                                  .map(key -> key.stamp)
                                                  .toList());
        }
    }

    private record Dispatched(Promise<AttemptResult> attempt, WriteOutcome outcome) {}

    private final DHTNode node;
    private final DHTNetwork network;
    private final ConcurrentHashMap<String, Page> registry;
    private final int replicationFactor;
    private final Supplier<Set<NodeId>> drainSet;
    private final DeparturePushTuning tuning;
    private final DepartureWindow window;
    private final DeparturePushObserver observer;
    private final long attemptsDeadline;
    private final Promise<DeparturePushReport> result = Promise.promise();
    // --- run state, guarded by `transitions` ---
    private final Map<String, DepartureKey> inventory = new HashMap<>();
    private final Map<NodeId, Holder> holders = new HashMap<>();
    private final Map<NodeId, Integer> tenures = new HashMap<>();
    private final List<Page> pages = new ArrayList<>();
    // The targets lost to a "draining" nack to a data page (ac517fb077): terminal within the run, so they leave every owed set ([#owedTargets]).
    private final Set<NodeId> lost = new HashSet<>();
    // The one observation of the last re-plan (I1): the ring, its members V and L, read together; every target, tenure tag and
    // page of a re-plan, and every Safe decision until the next one, come from it.
    private ConsistentHashRing<NodeId> observed = ConsistentHashRing.consistentHashRing();
    private Set<NodeId> view = Set.of();
    private Set<NodeId> observedLeaving = Set.of();
    // Orders counted acks against probes: a probe passed with mark m covers the acks numbered below m.
    private long sequence;
    private boolean closed;
    // The attempts deadline passed (P3): no exit starts, and the deadline probe round owns the close.
    private boolean closing;
    private boolean exitInFlight;
    private int enumeratedKeys;
    private boolean atRiskRaised;
    private int atRiskCount;

    // The deadline task, cancelled when the run resolves.
    private final AtomicReference<Option<ScheduledFuture<?>>> deadlineTimer = new AtomicReference<>(Option.none());

    // The halt watch (#3345 P2), cancelled when the run resolves.
    private final AtomicReference<Option<ScheduledFuture<?>>> haltWatch = new AtomicReference<>(Option.none());

    // --- counters (tests and logs) ---
    private final AtomicInteger reconciles = new AtomicInteger();
    private final AtomicInteger viewChanges = new AtomicInteger();
    private final AtomicInteger holderLosses = new AtomicInteger();
    private final AtomicInteger exitAttempts = new AtomicInteger();
    private final List<Promise<Unit>> events = new CopyOnWriteArrayList<>();
    private final Object transitions = new Object();
    private final ConcurrentLinkedQueue<Runnable> effects = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean drainingEffects = new AtomicBoolean(false);

    private DeparturePushRun(DHTNode node,
                             DHTNetwork network,
                             ConcurrentHashMap<String, Page> registry,
                             int replicationFactor,
                             Supplier<Set<NodeId>> drainSet,
                             long t0,
                             DepartureWindow window,
                             DeparturePushTuning tuning,
                             DeparturePushObserver observer) {
        this.node = node;
        this.network = network;
        this.registry = registry;
        this.replicationFactor = replicationFactor;
        this.drainSet = drainSet;
        this.tuning = tuning;
        this.window = window;
        this.observer = observer;
        this.attemptsDeadline = Math.max(t0,
                                         window.deadlineNanos() - tuning.eventDeliveryWait().nanos());
    }

    /// `t0` is taken by the caller on entry, before the inventory is enumerated (F2): a slow `stamps()` eats the window.
    static DeparturePushRun departurePushRun(DHTNode node,
                                             DHTNetwork network,
                                             ConcurrentHashMap<String, Page> registry,
                                             int replicationFactor,
                                             Supplier<Set<NodeId>> drainSet,
                                             long t0,
                                             DepartureWindow window,
                                             DeparturePushTuning tuning,
                                             DeparturePushObserver observer) {
        return new DeparturePushRun(node, network, registry, replicationFactor, drainSet, t0, window, tuning, observer);
    }

    /// Enumerate, plan, push, report. Always succeeds.
    Promise<DeparturePushReport> run() {
        node.storage()
            .stamps()
            .timeout(remainingUntil(attemptsDeadline))
            .onResult(enumerated -> enumerated.fold(this::notEnumerated, this::start));

        return result.onResultRun(this::cancelTimers);
    }

    /// A resolved run releases its deadline task and its halt watch at once, so nothing the run references (its observer) stays
    /// reachable until the deadline.
    @Contract
    private void cancelTimers() {
        deadlineTimer.get().onPresent(timer -> timer.cancel(false));
        haltWatch.get().onPresent(timer -> timer.cancel(false));
    }

    int reconciles() {
        return reconciles.get();
    }

    int viewChanges() {
        return viewChanges.get();
    }

    int holderLosses() {
        return holderLosses.get();
    }

    int exitAttempts() {
        return exitAttempts.get();
    }

    // --- enumeration and start ---
    /// The inventory could not be listed before the attempts deadline (or listing failed): the owed set is unknown. FER: the push
    /// degrades forward to an at-risk report with count 0, because it must never gate the halt.
    private Unit notEnumerated(Cause cause) {
        log.warn("Departure push: inventory not enumerated before the drain deadline ({}): at-risk set unknown",
                 cause.message());
        if (!window.halted().getAsBoolean()) {
            transition(() -> raiseAtRisk(List.of()));
        }

        succeedAfterEvents(DeparturePushReport.NOT_ENUMERATED);

        return Unit.unit();
    }

    /// Resolves the run with `report` once every event already decided has been delivered; the run's promise carries the outcome.
    @Contract
    private void succeedAfterEvents(DeparturePushReport report) {
        awaitEvents(report).onSuccess(result::succeed);
    }

    private Unit start(List<KeyStamp> stamps) {
        transition(() -> armThenEnumerate(stamps));
        reconcile();
        transition(this::raiseAtPlan);
        if (result.isResolved()) {
            cancelTimers();
        }

        maybeExit();

        return Unit.unit();
    }

    /// Under `transitions`: the deadline and the halt watch are armed before anything else of the run can fail, so a run always
    /// resolves; both take `transitions`, so neither runs before the inventory below is complete.
    private Unit armThenEnumerate(List<KeyStamp> stamps) {
        deadlineTimer.set(Option.some(SharedScheduler.schedule(this::onDeadline, remainingUntil(attemptsDeadline))));
        haltWatch.set(Option.some(SharedScheduler.scheduleAtFixedRate(this::onHaltWatch, haltWatchPeriod())));

        return enumerate(stamps);
    }

    /// Under `transitions`: the first inventory, every key owed.
    private Unit enumerate(List<KeyStamp> stamps) {
        var ringNodes = Set.copyOf(node.ring().nodes());
        var leaving = leavingSet();

        stamps.forEach(stamp -> inventory.put(HEX.formatHex(stamp.key()),
                                              classify(stamp, ringNodes, leaving)));
        enumeratedKeys = inventory.size();

        return Unit.unit();
    }

    /// Classification (S): SOLE when no node outside L can be shown to hold the key. A leaving node already gone from the ring
    /// vacated a slot that a node which never held the key may now fill, so each one is subtracted; the bound errs toward "sole".
    private DepartureKey classify(KeyStamp stamp, Set<NodeId> ringNodes, Set<NodeId> leaving) {
        var survivors = new HashSet<>(node.ring().nodesFor(stamp.key(), replicationFactor));

        survivors.removeAll(leaving);
        var pruned = leaving.stream().filter(member -> !ringNodes.contains(member)).count();

        return new DepartureKey(stamp, survivors.size() - pruned <= 0);
    }

    /// Under `transitions`: a re-read at an exit attempt (R4, G3). A known key takes the stamp read now (a newer stamp is owed again)
    /// and is no longer absent; a key the re-read misses was removed meanwhile and leaves the inventory, never delivered; a new key is
    /// owed only when its stamp predates the fence.
    private Unit reread(List<KeyStamp> stamps) {
        var ringNodes = Set.copyOf(node.ring().nodes());
        var leaving = leavingSet();
        var read = new HashMap<String, KeyStamp>();

        stamps.forEach(stamp -> read.put(HEX.formatHex(stamp.key()),
                                         stamp));
        inventory.keySet().retainAll(read.keySet());
        read.forEach((hex, stamp) -> mergeRead(hex, stamp, ringNodes, leaving));

        return Unit.unit();
    }

    private void mergeRead(String hex, KeyStamp stamp, Set<NodeId> ringNodes, Set<NodeId> leaving) {
        var known = inventory.get(hex);

        if (known != null) {
            known.stamp = stamp;
            known.absent = false;
        } else if (beforeFence(stamp)) {
            inventory.put(hex, classify(stamp, ringNodes, leaving));
            enumeratedKeys++;
        }
    }

    private boolean beforeFence(KeyStamp stamp) {
        return HlcTimestamp.physicalMillis(stamp.version()) < window.drainStartMillis();
    }

    private Set<NodeId> leavingSet() {
        var leaving = new HashSet<>(drainSet.get());

        leaving.add(node.nodeId());

        return Set.copyOf(leaving);
    }

    // --- triggers ---
    /// Trigger 1: V changed. Called after each ring change.
    @Contract
    void onViewChange() {
        viewChanges.incrementAndGet();
        reconcile();
    }

    /// Trigger 2: a holder lost. Its record is cleared and the run re-plans.
    @Contract
    private void holderLost(NodeId holder, String why) {
        var lost = underTransitions(() -> holders.remove(holder) != null);

        holderLosses.incrementAndGet();
        log.info("Departure push: holder {} lost ({}){}",
                 holder.id(),
                 why,
                 lost
                 ? ""
                 : " before it held anything");
        reconcile();
    }

    /// The one serial re-plan. Pages are registered under `transitions`; their chains start after it is released.
    @Contract
    private void reconcile() {
        var plan = transition(this::replan);

        plan.chains().forEach(this::startChain);
    }

    /// Under `transitions`.
    /// ```tla DHTDepartureDrain:Replan
    /// \* The one serial re-plan (#3576), on three triggers: the run's start (phase "draining"), a view change (onViewChange,
    /// \* called by the topology listener after each ring change: d's ring is no longer the V it observed) and an exit attempt
    /// \* (claimExit: no live page; the exit re-reads the inventory, afterReread). A holder lost to a "draining" nack or a token
    /// \* mismatch is the fourth trigger; the model's receivers never send either, so it is not modelled.
    /// \* observeView takes V (the ring) and L (the drain set d received, and self) together; every target, deliverer and Safe
    /// \* decision until the next re-plan comes from them. A holder outside V counts no more (Held). The key is owed to every
    /// \* target that is not a holder (owedTargets): a live page to such a target stays, any other page of d is cancelled
    /// \* (keepOrCancel; a cancelled page's late ack is not modelled: it only adds a holder whose copy is real), and an owed target
    /// \* without a page gets one. rf is fixed when the run is built (DHTRebalancer.pushOnDeparture).
    /// Replanned(d, start, exit) ==
    ///     LET R    == Ring(d)
    ///         excl == Excl(d)
    ///         rf   == IF start THEN RFOf(R) ELSE rfAt[d]
    ///         own  == IF start \/ exit THEN store[d] ELSE inv[d]
    ///         held == Held(d, R)
    ///         tgts == IF own THEN DrainTargets(R, d, excl, rf) ELSE {}
    ///     IN /\ view' = [view EXCEPT ![d] = R]
    ///        /\ leaving' = [leaving EXCEPT ![d] = excl]
    ///        /\ rfAt' = [rfAt EXCEPT ![d] = rf]
    ///        /\ inv' = [inv EXCEPT ![d] = own]
    ///        /\ acked' = [acked EXCEPT ![d] = held]
    ///        /\ claimed' = IF Fix("replan") THEN claimed ELSE [claimed EXCEPT ![d] = tgts]
    ///        /\ pages' = {p \in pages : p.from # d} \cup {Page(d, t) : t \in tgts \ held}
    ///        /\ deliverers' = [deliverers EXCEPT ![d] = IF own THEN Deliverers(R, d, excl, rf) ELSE {}]
    ///        \* Without `exit` the plan reports a key with no target delivered when it is replicated (before #3576).
    ///        /\ delivered' = IF start /\ ~Fix("exit")
    ///                        THEN [delivered EXCEPT ![d] = own /\ tgts = {} /\ ~Sole(R, d, excl, rf)]
    ///                        ELSE delivered
    /// \* Without `replan` (the code before #3576) a view change is the old Retarget: every pending page whose target left the
    /// \* ring is dropped, the target joins the frozen leaving set, the key is paged to the targets not claimed before, and the
    /// \* new deliverers join the old (one that already acked delivers the key at once, deliverIfAcked). Batched per view change.
    /// Retargeted(d) ==
    ///     LET R    == Ring(d)
    ///         gone == {t \in Nodes : Pending(d, t) /\ t \notin R}
    ///         lv   == leaving[d] \cup gone
    ///         new  == DrainTargets(R, d, lv, rfAt[d]) \ claimed[d]
    ///         dl   == deliverers[d] \cup Deliverers(R, d, lv, rfAt[d])
    ///     IN /\ view' = [view EXCEPT ![d] = R]
    ///        /\ acked' = [acked EXCEPT ![d] = Held(d, R)]
    ///        /\ IF gone = {}
    ///           THEN UNCHANGED <<leaving, claimed, pages, deliverers, delivered>>
    ///           ELSE /\ leaving' = [leaving EXCEPT ![d] = lv]
    ///                /\ claimed' = [claimed EXCEPT ![d] = @ \cup new]
    ///                /\ pages' = (pages \ {Page(d, t) : t \in gone}) \cup {Page(d, n) : n \in new}
    ///                /\ deliverers' = [deliverers EXCEPT ![d] = dl]
    ///                /\ delivered' = IF Fix("exit") THEN delivered
    ///                                ELSE [delivered EXCEPT ![d] = @ \/ dl \cap acked[d] # {}]
    ///        /\ UNCHANGED <<rfAt, inv>>
    /// Replan(d) ==
    ///     /\ phase[d] \in {"draining", "pushing"}
    ///     /\ LET start == phase[d] = "draining"
    ///            exit  == Fix("exit") /\ ~start /\ \A p \in pages : p.from # d
    ///        IN /\ start \/ exit \/ view[d] # Ring(d)
    ///           /\ IF start \/ Fix("replan") THEN Replanned(d, start, exit) ELSE Retargeted(d)
    ///     /\ phase' = [phase EXCEPT ![d] = "pushing"]
    ///     /\ UNCHANGED <<drainers, held0, stale, drainSet, known, pruned, flapped, store, strayNack>>
    /// ```
    private Plan replan() {
        if (closed || window.halted().getAsBoolean()) {
            return new Plan(List.of());
        }

        reconciles.incrementAndGet();
        observeView();
        var desired = new HashMap<NodeId, List<DepartureKey>>();

        inventory.values()
                 .stream()
                 .filter(key -> !key.absent && !key.undeliverable)
                 .forEach(key -> desire(key, desired));
        pages.stream().filter(this::liveInChain).forEach(page -> keepOrCancel(page, desired));

        return new Plan(desired.entrySet()
                               .stream()
                               .filter(entry -> !entry.getValue()
                                                      .isEmpty())
                               .map(entry -> registerPages(entry.getKey(),
                                                           entry.getValue()))
                               .toList());
    }

    /// Under `transitions`, I1: the ring is read ONCE per re-plan, as one snapshot; V, L, the tenures and every target of the
    /// re-plan come from it, so a node that enters the ring after the snapshot gets no page (and no tenure tag) until the next
    /// re-plan observes it. Tenures start at entry into V; a page of an earlier tenure, or of a node no longer in V, is not
    /// [#current(Page)], so it never counts again.
    private void observeView() {
        var ring = node.ring().snapshot();
        var now = Set.copyOf(ring.nodes());

        now.stream().filter(member -> !view.contains(member)).forEach(member -> tenures.merge(member, 1, Integer::sum));
        observed = ring;
        view = now;
        observedLeaving = leavingSet();
    }

    /// Under `transitions`, P4: a page lives in ONE tenure of its target. It is current iff its target is in V, the last
    /// observation, and its tenure tag is the target's tenure there. [#mayAct(Page)] asks it, and so does the ack (`inTenure`).
    /// A page that is not current is dead from the observation on: never counted live, sent, split or acked, so its keys are
    /// desired again and paged in the current tenure.
    private boolean current(Page page) {
        return view.contains(page.target) && tenures.getOrDefault(page.target, 0) == page.tenure;
    }

    private void drop(Page page) {
        page.cancelled.set(true);
        registry.remove(page.correlationId());
    }

    /// Under `transitions`: a key wants a page on every target it is owed to ([#owedTargets]).
    private void desire(DepartureKey key, Map<NodeId, List<DepartureKey>> desired) {
        owedTargets(key).forEach(target -> desired.computeIfAbsent(target,
                                                                   _ -> new ArrayList<>())
                                                  .add(key));
    }

    /// Under `transitions`, P1, the ONE owed-to definition: planned minus delivered minus lost. Planned: the nodes that gain `key`
    /// in V ([#drainTargets], read from the last observation); delivered: its confirmed holders ([#safe] at that target); lost: the
    /// targets that refused with a "draining" nack, terminal within the run, so no re-plan (the exit's re-read re-plan included)
    /// pages them again. The plan, every re-plan and the cancel of a page compute from it alone; no caller filters again.
    private List<NodeId> owedTargets(DepartureKey key) {
        return drainTargets(key.stamp.key()).all()
                           .stream()
                           .filter(target -> !safe(key, target, false))
                           .filter(target -> !lost.contains(target))
                           .toList();
    }

    /// Under `transitions`, P2: the ONE answer to "may this page send or resend": registered, not cancelled and [#current(Page)],
    /// read from V (the last observation), never from the live ring. A page a re-plan cancelled is dead exactly like a page of an
    /// earlier tenure. Send and resend (`probeStopped`), split (`halvePage`, `splitPage`) and the count of live chains
    /// (`liveInChain`) ask only this; a late ack of a cancelled page still counts (`inTenure` asks only [#current(Page)]).
    private boolean mayAct(Page page) {
        return registry.containsKey(page.correlationId())
               && !page.cancelled.get()
               && current(page);
    }

    /// Under `transitions`: a data page of a running chain, not yet run to its end, that is [#mayAct(Page)]. The re-plan keeps or
    /// cancels exactly these, and no exit starts while one exists.
    private boolean liveInChain(Page page) {
        return ! page.isProbe()
               && !page.finished.get()
               && mayAct(page);
    }

    /// Under `transitions`: a live page stays when its target is still owed one of its keys ([#owedTargets]), and those pairs need
    /// no new page; otherwise it is cancelled and stays registered, so a late ack still counts. No other reason cancels it. Only a
    /// [#mayAct(Page)] page gets here (`liveInChain`): one that is not covers nothing, so its keys are desired again.
    private void keepOrCancel(Page page, Map<NodeId, List<DepartureKey>> desired) {
        var covered = page.keys.stream().filter(key -> owedTargets(key).contains(page.target)).toList();

        if (covered.isEmpty()) {
            page.cancelled.set(true);
        } else {
            Option.option(desired.get(page.target)).onPresent(wanted -> wanted.removeAll(covered));
        }
    }

    /// Under `transitions`: Safe(k) = k is safe at one of its deliverers. A close condition only (the exit, the deadline): never a
    /// reason to cancel a page ([#owedTargets] decides that).
    private boolean isSafe(DepartureKey key, Targets targets, boolean atClose) {
        return targets.deliverers()
                      .stream()
                      .anyMatch(target -> safe(key, target, atClose));
    }

    /// Under `transitions`, I2, the ONE safe predicate: `target` is in the observed V, its holder record belongs to its current
    /// tenure, and it has a counted ack for `key` at the key's current stamp; at a close (`atClose`) that holder must also have
    /// passed a probe sent after that ack. The re-plan, the resend and the split of a page, the skip of a page, the exit
    /// decision and every close ask only this.
    private boolean safe(DepartureKey key, NodeId target, boolean atClose) {
        return view.contains(target) && Option.option(holders.get(target))
                                              .filter(holder -> holder.tenure == tenures.getOrDefault(target, 0))
                                              .flatMap(holder -> Option.option(holder.copies.get(key.stampId())).filter(ackedAt -> !atClose || ackedAt < holder.probedAt))
                                              .isPresent();
    }

    /// Under `transitions`: every key not Safe (at a close: not Safe through a probe passed since its ack).
    private List<DepartureKey> owed(boolean atClose) {
        return inventory.values()
                        .stream()
                        .filter(key -> !isSafe(key,
                                               drainTargets(key.stamp.key()),
                                               atClose))
                        .toList();
    }

    // --- targets ---
    /// Every target of one key: the delta targets ([#departureTargets]) plus, when a node of L lies ahead in the key's walk, the
    /// named prefix and the first node outside L ([#finalPrimaryCandidates]). The deliverers, whose holding makes the key safe, are
    /// the delta targets when no candidates exist, else only the first node outside L: a node of L may halt with the copy (#3208),
    /// and a delta target may lie outside the final replica set (#3209).
    /// Under `transitions`; read from the last observation only (I1).
    private Targets drainTargets(byte[] key) {
        var excluded = observedLeaving;
        var delta = departureTargets(key, excluded);
        var candidates = finalPrimaryCandidates(key, excluded);
        var deliverers = candidates.isEmpty()
                         ? delta
                         : candidates.stream().filter(candidate -> !excluded.contains(candidate)).toList();

        return new Targets(Stream.concat(delta.stream(), candidates.stream()).distinct().toList(),
                           deliverers);
    }

    /// The primary of the key's FINAL replica set, whichever L entries turn out stale (#3208, #3209): the named prefix of the walk
    /// without self, plus the first node outside L. Empty when no node of L lies within the first `replicationFactor` nodes.
    private List<NodeId> finalPrimaryCandidates(byte[] key, Set<NodeId> excluded) {
        var walk = observed.nodesFor(key,
                                     observed.nodeCount(),
                                     candidate -> !candidate.equals(node.nodeId()));
        var namedAhead = walk.stream().limit(replicationFactor).anyMatch(excluded::contains);

        return namedAhead
               ? namedPrefixWithFirstUnnamed(walk, excluded)
               : List.of();
    }

    private static List<NodeId> namedPrefixWithFirstUnnamed(List<NodeId> walk, Set<NodeId> excluded) {
        var named = walk.stream().takeWhile(excluded::contains).toList();

        return Stream.concat(named.stream(),
                             walk.stream().skip(named.size()).limit(1))
                     .toList();
    }

    /// Post-departure delta targets (issue #427, D3): the responsible set without L, less the current replicas outside L when self
    /// is still a ring member, the set is full and every node of L is still in the ring; otherwise the whole new set (idempotent
    /// versioned puts make a resend to an existing replica harmless).
    private List<NodeId> departureTargets(byte[] key, Set<NodeId> excluded) {
        var newSet = observed.nodesFor(key, replicationFactor, candidate -> !excluded.contains(candidate));
        var currentSet = observed.nodesFor(key, replicationFactor);

        return currentSet.contains(node.nodeId()) && newSet.size() >= replicationFactor && view.containsAll(excluded)
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

    // --- pages ---
    /// Under `transitions`: one target's keys split by the page budget, each page tagged with the target's current tenure.
    private List<Page> registerPages(NodeId target, List<DepartureKey> keys) {
        var byStamp = new IdentityHashMap<KeyStamp, DepartureKey>();

        keys.forEach(key -> byStamp.put(key.stamp, key));

        return node.repair()
                   .pageBudget()
                   .pages(keys.stream().map(key -> key.stamp).toList())
                   .stream()
                   .map(page -> registerPage(target,
                                             page.stream().map(byStamp::get).toList(),
                                             false))
                   .toList();
    }

    /// Under `transitions`.
    private Page registerPage(NodeId target, List<DepartureKey> keys, boolean split) {
        return registerPage(target, tenures.getOrDefault(target, 0), keys, split);
    }

    /// Under `transitions`.
    private Page registerPage(NodeId target, int tenure, List<DepartureKey> keys, boolean split) {
        var page = new Page(this, target, tenure, keys, split);

        registry.put(page.correlationId(), page);
        pages.add(page);

        return page;
    }

    /// The end of a chain is an exit trigger; whether a chain still counts is [#liveInChain(Page)]'s answer, not a counter.
    @Contract
    private void startChain(List<Page> chain) {
        runChain(chain, 0).onResultRun(this::maybeExit);
    }

    private Promise<Unit> runChain(List<Page> chain, int index) {
        return index >= chain.size()
               ? Promise.unitPromise()
               : runPage(chain.get(index)).fold(result -> finished(chain.get(index),
                                                                   result))
                        .flatMap(_ -> runChain(chain, index + 1));
    }

    /// A page is finished BEFORE the chain's result completes: the exit trigger at the chain's end must see it (a side effect
    /// attached to the same promise is not ordered against the dependent steps that complete the chain).
    private static Promise<Unit> finished(Page page, Result<Unit> result) {
        return Promise.resolved(result.onResultRun(() -> page.finished.set(true)));
    }

    /// The page's entries are read once and the same list is resent (N5): every apply is versioned, so a stale resend never
    /// overwrites a newer copy. A key the read no longer finds is marked absent; a read that fails leaves its keys owed.
    private Promise<Unit> runPage(Page page) {
        return shouldStop(page)
               ? Promise.unitPromise()
               : node.readEntries(page.stamps())
                     .timeout(remainingUntil(attemptsDeadline))
                     .fold(read -> read.fold(cause -> unreadable(page, cause),
                                             entries -> sendRead(page, entries)));
    }

    private Promise<Unit> unreadable(Page page, Cause cause) {
        log.warn("Departure push: page {} for {} not read ({}); its keys stay owed",
                 page.correlationId(),
                 page.target.id(),
                 cause.message());

        return Promise.unitPromise();
    }

    /// No send of an empty read: its keys are absent, and the exit attempt's re-read settles them (never as delivered).
    private Promise<Unit> sendRead(Page page, List<DHTMessage.KeyValue> entries) {
        transition(() -> markAbsent(page, entries));

        return entries.isEmpty()
               ? Promise.unitPromise()
               : attempt(page, entries, initialNackBackoff());
    }

    /// Under `transitions`.
    private Unit markAbsent(Page page, List<DHTMessage.KeyValue> entries) {
        var found = entries.stream().map(entry -> HEX.formatHex(entry.key())).collect(Collectors.toSet());

        page.keys.stream().filter(key -> !found.contains(key.hex())).forEach(key -> key.absent = true);

        return Unit.unit();
    }

    private Promise<Unit> attempt(Page page, List<DHTMessage.KeyValue> entries, TimeSpan nackBackoff) {
        return shouldStop(page)
               ? Promise.unitPromise()
               : send(page, transition(() -> openEntries(page, entries)), nackBackoff);
    }

    /// I2: each send and resend carries only the entries whose keys are not yet safe at the target (a late ack of an earlier page
    /// may have made some safe), only their stamps count for its ack, and nothing goes out when none is left.
    private Promise<Unit> send(Page page, List<DHTMessage.KeyValue> open, TimeSpan nackBackoff) {
        page.sent.set(open.stream().map(KeyStamp::keyStamp).map(StampId::stampId).toList());

        return open.isEmpty()
               ? Promise.unitPromise()
               : dispatch(page, open, attemptsDeadline).flatMap(dispatched -> afterSend(page,
                                                                                        open,
                                                                                        dispatched,
                                                                                        nackBackoff));
    }

    /// Under `transitions`.
    private List<DHTMessage.KeyValue> openEntries(Page page, List<DHTMessage.KeyValue> entries) {
        var open = page.keys.stream()
                            .filter(key -> !safe(key, page.target, false))
                            .map(DepartureKey::hex)
                            .collect(Collectors.toSet());

        return entries.stream()
                      .filter(entry -> open.contains(HEX.formatHex(entry.key())))
                      .toList();
    }

    /// The push carries L, the set excluded when choosing its target (#1818 L1), and V, both from the run's last observation (I1),
    /// so the receiver checks placement against the same view rather than its own. The send itself is bounded by `until` (every wait
    /// of the run is): FER, a send that fails or is not resolved by then is a dead connection, waited out like one (the data path
    /// pauses and retries while the window lasts; a probe counts as unanswered), so no chain or probe round outlives its deadline.
    private Promise<Dispatched> dispatch(Page page, List<DHTMessage.KeyValue> entries, long until) {
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
                                                                 List.copyOf(underTransitions(() -> observedLeaving)),
                                                                 List.copyOf(underTransitions(() -> view))))
                      .timeout(remainingUntil(until))
                      .recover(_ -> new WriteOutcome.ConnectionDead(page.target))
                      .map(outcome -> new Dispatched(attempt, outcome));
    }

    /// Per `WriteOutcome`: `Sent` waits for the attempt; a refusal proves nothing, so the SAME target is retried after
    /// `resendAfter` (H2); `EncodeFailed` is split once, then undeliverable.
    private Promise<Unit> afterSend(Page page,
                                    List<DHTMessage.KeyValue> entries,
                                    Dispatched dispatched,
                                    TimeSpan nackBackoff) {
        return switch (dispatched.outcome()) {
            case WriteOutcome.Sent _ -> awaitAttempt(dispatched).flatMap(result -> afterAttempt(page,
                                                                                                entries,
                                                                                                result,
                                                                                                nackBackoff));
            case WriteOutcome.EncodeFailed failed -> encodeFailed(page, failed);
            case WriteOutcome.BackpressureRefused _, WriteOutcome.ConnectionDead _, WriteOutcome.NoPeerState _ -> pause(tuning.resendAfter()).flatMap(_ -> attempt(page,
                                                                                                                                                                   entries,
                                                                                                                                                                   nackBackoff));
        };
    }

    private Promise<AttemptResult> awaitAttempt(Dispatched dispatched) {
        return awaitAttempt(dispatched, attemptsDeadline);
    }

    /// FER: an attempt unanswered within `resendAfter` or by `until` is TIMED_OUT (a page split, a probe failed), never awaited on.
    private Promise<AttemptResult> awaitAttempt(Dispatched dispatched, long until) {
        return dispatched.attempt()
                         .timeout(timeSpan(Math.min(tuning.resendAfter().nanos(),
                                                    remainingNanos(until))).nanos())
                         .recover(_ -> AttemptResult.TIMED_OUT);
    }

    private Promise<Unit> afterAttempt(Page page,
                                       List<DHTMessage.KeyValue> entries,
                                       AttemptResult result,
                                       TimeSpan nackBackoff) {
        return switch (result) {
            case ACKED, FAILED, IGNORED -> Promise.unitPromise();
            case NACKED -> pause(nackBackoff).flatMap(_ -> afterNackPause(page, entries, nackBackoff));
            case TIMED_OUT -> afterSilence(page, entries, nackBackoff);
        };
    }

    /// #3345 P1, silence: a page whose ack does not come within `resendAfter` is split like a nacked one (a receiver that cannot
    /// decode one entry drops the whole message, so silence can be one key's as well); a single key is resent unchanged. Cost on
    /// a silent target: N - 1 sends of inner pages, then each key resent once per `resendAfter`, all ending at the deadline.
    private Promise<Unit> afterSilence(Page page, List<DHTMessage.KeyValue> entries, TimeSpan nackBackoff) {
        return page.keys.size() > 1
               ? runHalves(transition(() -> halvePage(page)))
               : attempt(page, entries, nackBackoff);
    }

    /// #3345 P1: a receiver nacks a whole page when one entry fails its check, and the nack names no key, so a nacked page of
    /// several keys is split in halves that run at once, down to single keys: one key the receiver cannot take never keeps another
    /// key from becoming safe, at most log2(page size) extra rounds per refused page. A single key still nacked is resent with
    /// the growing pause, owed until acked and reported at the deadline. A nack that clears on the resend costs one split.
    private Promise<Unit> afterNackPause(Page page, List<DHTMessage.KeyValue> entries, TimeSpan nackBackoff) {
        return page.keys.size() > 1
               ? runHalves(transition(() -> halvePage(page)))
               : attempt(page, entries, nextNackBackoff(nackBackoff));
    }

    private Promise<Unit> runHalves(List<Page> halves) {
        return Promise.allOf(halves.stream().map(half -> runChain(List.of(half), 0)).toList()).mapToUnit();
    }

    /// Under `transitions`: the page is cancelled and its keys not yet safe at its target (I2: a late ack of an earlier page may
    /// have made some safe) go to at most two pages on the same target, in the page's tenure; none when every key is safe or the
    /// page is not [#mayAct(Page)] (a page cancelled by a re-plan or of an earlier tenure is never split).
    private List<Page> halvePage(Page page) {
        var open = mayAct(page)
                   ? page.keys.stream().filter(key -> !safe(key, page.target, false)).toList()
                   : List.<DepartureKey> of();

        page.cancelled.set(true);
        var middle = open.size() / 2;

        return Stream.of(open.subList(0, middle),
                         open.subList(middle,
                                      open.size()))
                     .filter(half -> !half.isEmpty())
                     .map(half -> registerPage(page.target,
                                               page.tenure,
                                               List.copyOf(half),
                                               false))
                     .toList();
    }

    /// A page that cannot be encoded is split once into single-key pages on the same target and tenure; a key whose own page still
    /// fails is undeliverable: it stays owed and is counted apart.
    private Promise<Unit> encodeFailed(Page page, WriteOutcome.EncodeFailed failed) {
        log.error("Departure push: page {} for {} cannot be encoded ({})",
                  page.correlationId(),
                  page.target.id(),
                  failed.messageType());

        return page.split || page.keys.size() == 1
               ? Promise.success(transition(() -> undeliverable(page)))
               : runChain(transition(() -> splitPage(page)), 0);
    }

    /// Under `transitions`.
    private Unit undeliverable(Page page) {
        page.keys.forEach(key -> key.undeliverable = true);
        page.cancelled.set(true);

        return Unit.unit();
    }

    /// Under `transitions`: single-key pages in the page's tenure; none when the page is not [#mayAct(Page)].
    private List<Page> splitPage(Page page) {
        var singles = mayAct(page)
                      ? page.keys.stream()
                                 .map(key -> registerPage(page.target,
                                                          page.tenure,
                                                          List.of(key),
                                                          true))
                                 .toList()
                      : List.<Page> of();

        page.cancelled.set(true);

        return singles;
    }

    /// No data page or exit probe starts once the attempts window is used up: the deadline resolves the run (P3: after its own
    /// probe round), so a send then is never counted.
    private boolean shouldStop(Page page) {
        return probeStopped(page, attemptsDeadline) || isSettled(page);
    }

    /// A page (a probe: any page) is not sent once the node halted, the page is not [#mayAct(Page)], the run closed, or `until`
    /// passed.
    private boolean probeStopped(Page page, long until) {
        if (window.halted().getAsBoolean()) {
            onHalt();
        }

        return ! underTransitions(() -> mayAct(page)) || isClosed() || System.nanoTime() >= until;
    }

    /// I2: a data page every key of which is already safe at its target is not sent (nor resent).
    private boolean isSettled(Page page) {
        return ! page.isProbe() && underTransitions(() -> page.keys.stream()
                                                                   .allMatch(key -> safe(key, page.target, false)));
    }

    private boolean isClosed() {
        return underTransitions(() -> closed);
    }

    private Promise<Unit> pause(TimeSpan delay) {
        return pause(delay, attemptsDeadline);
    }

    private Promise<Unit> pause(TimeSpan delay, long until) {
        return Promise.promise(timeSpan(Math.min(delay.nanos(), remainingNanos(until))).nanos(),
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

    // --- acks ---
    /// Under `transitions`: a "draining" nack, on a data page or on a probe alike (the class comes from the nack's cause, never
    /// from the page type, ac517fb077), refuses the target for the rest of the run; it leaves the owed set ([#owedTargets]).
    private AttemptResult drained(NodeId target) {
        lost.add(target);

        return AttemptResult.FAILED;
    }

    /// An ack for one of this run's pages. It counts (records a holder of the stamps its page carried) only when the page belongs
    /// to the target's current tenure, the target is in V, and the token matches the one recorded in that tenure. A "draining" nack
    /// or a token mismatch is a holder lost. Never a re-plan by itself.
    @Contract
    void onAck(Page page, DHTMessage.MigrationDataAck ack) {
        var outcome = transition(() -> recordAck(page, ack));

        if (outcome != AttemptResult.IGNORED) {
            page.settle(outcome);
        }

        if (outcome == AttemptResult.FAILED && !page.isProbe()) {
            holderLost(page.target,
                       ack.draining()
                       ? "draining"
                       : "token mismatch");
        }
    }

    /// Under `transitions`.
    private AttemptResult recordAck(Page page, DHTMessage.MigrationDataAck ack) {
        if (!inTenure(page)) {
            return AttemptResult.IGNORED;
        }

        if (ack.draining()) {
            return drained(page.target);
        }

        var holder = holders.compute(page.target, (_, known) -> currentHolder(known, page, ack));

        if (holder.token != ack.bootToken()) {
            return AttemptResult.FAILED;
        }

        if (ack.applied()) {
            var ackedAt = ++sequence;

            page.sent.get().forEach(stamp -> holder.copies.put(stamp, ackedAt));
        }

        return ack.applied()
               ? AttemptResult.ACKED
               : AttemptResult.NACKED;
    }

    /// Under `transitions`: a record of an earlier tenure is replaced by a new one, which records this ack's token.
    private static Holder currentHolder(Holder known, Page page, DHTMessage.MigrationDataAck ack) {
        return Option.option(known)
                     .filter(holder -> holder.tenure == page.tenure)
                     .or(() -> Holder.holder(ack.bootToken(),
                                             page.tenure));
    }

    /// Under `transitions`: registered and [#current(Page)] (P4), read from V like [#mayAct(Page)], never from the live ring; a
    /// cancelled page's late ack still counts.
    private boolean inTenure(Page page) {
        return registry.containsKey(page.correlationId()) && current(page);
    }

    // --- exit ---
    /// Trigger 3: when nothing sendable is owed and no chain runs, one exit attempt at a time.
    @Contract
    private void maybeExit() {
        if (transition(this::claimExit)) {
            exitAttempt();
        }
    }

    /// Under `transitions`.
    private boolean claimExit() {
        var ready = !closed && !closing && !exitInFlight && pages.stream().noneMatch(this::liveInChain) && owed(false).stream()
                                                                                                               .allMatch(key -> key.absent);

        exitInFlight = exitInFlight || ready;

        return ready;
    }

    @Contract
    private void exitAttempt() {
        exitAttempts.incrementAndGet();
        node.storage()
            .stamps()
            .timeout(remainingUntil(attemptsDeadline))
            .onResult(read -> read.fold(this::rereadFailed, this::afterReread));
    }

    /// FER: a failed re-read leaves the run to its deadline, which reports what is still owed.
    private Unit rereadFailed(Cause cause) {
        log.warn("Departure push: inventory re-read failed ({}); the run ends at its deadline", cause.message());

        return Unit.unit();
    }

    private Unit afterReread(List<KeyStamp> stamps) {
        transition(() -> reread(stamps));
        reconcile();

        return transition(this::probeTargets).fold(this::reopen, this::probe);
    }

    /// Under `transitions`: empty when something is still owed (the exit fails and the re-plan's chains carry on), else the distinct
    /// holders Safe relies on.
    private Option<Set<NodeId>> probeTargets() {
        return owed(false).isEmpty()
               ? Option.some(inventory.values()
                                      .stream()
                                      .flatMap(key -> drainTargets(key.stamp.key()).deliverers()
                                                                  .stream()
                                                                  .filter(target -> safe(key, target, false)))
                                      .collect(Collectors.toSet()))
               : Option.none();
    }

    private Unit reopen() {
        transition(this::releaseExit);
        maybeExit();

        return Unit.unit();
    }

    /// Under `transitions`.
    private Unit releaseExit() {
        exitInFlight = false;

        return Unit.unit();
    }

    /// Send an EMPTY page to every holder Safe relies on; pass iff each answers in its tenure with its token before the attempts
    /// deadline, and no holder was lost meanwhile (a loss between a passed probe and the close re-opens the run).
    private Unit probe(Set<NodeId> reliedOn) {
        var lossesAtStart = holderLosses.get();
        var mark = transition(this::nextMark);
        var probes = transition(() -> registerProbes(reliedOn));

        sendProbes(probes, attemptsDeadline).onSuccess(results -> afterProbe(probes, results, lossesAtStart, mark));

        return Unit.unit();
    }

    /// Under `transitions`: one EMPTY page per holder, each in the holder's current tenure.
    private List<Page> registerProbes(Set<NodeId> reliedOn) {
        return reliedOn.stream()
                       .map(target -> registerPage(target,
                                                   List.of(),
                                                   false))
                       .toList();
    }

    /// Each probe ends answered (ACKED or NACKED), refused (FAILED: a draining nack or a token mismatch) or unanswered (TIMED_OUT,
    /// once it can no longer be sent: `until` passed, the run closed, or the probe is no longer [#mayAct(Page)]).
    private Promise<List<Result<AttemptResult>>> sendProbes(List<Page> probes, long until) {
        return Promise.allOf(probes.stream().map(page -> probeOne(page, until)).toList());
    }

    /// A probe not answered within `resendAfter` is sent again, on the same page, while it may be sent; a late answer
    /// to an earlier send counts. Silence is never a loss.
    private Promise<AttemptResult> probeOne(Page page, long until) {
        return probeStopped(page, until)
               ? Promise.success(AttemptResult.TIMED_OUT)
               : dispatch(page,
                          List.of(),
                          until).flatMap(dispatched -> probeAnswer(dispatched, until))
                         .flatMap(result -> resendIfSilent(page, result, until));
    }

    /// A refusal to take the probe proves nothing: it is waited out like silence.
    private Promise<AttemptResult> probeAnswer(Dispatched dispatched, long until) {
        return dispatched.outcome() instanceof WriteOutcome.Sent
               ? awaitAttempt(dispatched, until)
               : pause(tuning.resendAfter(), until).map(_ -> AttemptResult.TIMED_OUT);
    }

    private Promise<AttemptResult> resendIfSilent(Page page, AttemptResult result, long until) {
        return result == AttemptResult.TIMED_OUT
               ? probeOne(page, until)
               : Promise.success(result);
    }

    /// Under `transitions`: every ack counted from here on is numbered above the mark, so a probe registered after it covers only
    /// the acks counted before.
    private long nextMark() {
        return++sequence;
    }

    @Contract
    private void afterProbe(List<Page> probes, List<Result<AttemptResult>> results, int lossesAtStart, long mark) {
        var passed = passedProbes(probes, results);
        var refused = refusedProbes(probes, results);

        transition(() -> markProbed(passed, mark));
        transition(() -> retireProbes(probes));
        if (passed.size() == probes.size() && transition(() -> closeIfClean(lossesAtStart))) {
            return;
        }
        // P3: past the deadline the deadline probe round owns the close; a probe the deadline cut short proves no loss.
        if (underTransitions(() -> closing)) {
            return;
        }

        transition(this::releaseExit);
        // Only an explicit refusal is a lost holder; an unanswered probe is not, and triggers no re-push.
        refused.forEach(target -> holderLost(target, "probe refused"));
        maybeExit();
    }

    /// The targets whose probe answer refused it (a draining nack or a token mismatch).
    private static List<NodeId> refusedProbes(List<Page> probes, List<Result<AttemptResult>> results) {
        return IntStream.range(0,
                               probes.size())
                        .filter(i -> results.get(i)
                                            .fold(_ -> false, result -> result == AttemptResult.FAILED))
                        .mapToObj(i -> probes.get(i).target)
                        .toList();
    }

    private static List<Page> passedProbes(List<Page> probes, List<Result<AttemptResult>> results) {
        return IntStream.range(0,
                               probes.size())
                        .filter(i -> results.get(i)
                                            .fold(_ -> false, DeparturePushRun::probePassed))
                        .mapToObj(probes::get)
                        .toList();
    }

    private static boolean probePassed(AttemptResult result) {
        return result == AttemptResult.ACKED || result == AttemptResult.NACKED;
    }

    /// Under `transitions`: a passed probe covers the acks its holder counted before `mark`, in the tenure it was sent in.
    private Unit markProbed(List<Page> passed, long mark) {
        passed.forEach(probe -> Option.option(holders.get(probe.target))
                                      .filter(holder -> holder.tenure == probe.tenure)
                                      .onPresent(holder -> holder.probedAt = Math.max(holder.probedAt, mark)));

        return Unit.unit();
    }

    /// Under `transitions`.
    private Unit retireProbes(List<Page> probes) {
        probes.forEach(this::drop);
        pages.removeAll(probes);

        return Unit.unit();
    }

    /// Under `transitions`: closes when no holder was lost since the probe started and nothing is owed.
    private boolean closeIfClean(int lossesAtStart) {
        var clean = holderLosses.get() == lossesAtStart && owed(true).isEmpty() && !closed;

        if (clean) {
            closeUnder(false);
        }

        return clean;
    }

    // --- close ---
    /// #3345 P3: a halted run closes at once and sends nothing (P2); otherwise ONE probe round goes to every holder of a key owed
    /// at the close only for want of a probe passed since its ack, it waits at most one `resendAfter`, then the run closes. A holder
    /// that fails or does not answer covers nothing: its keys stay owed.
    @Contract
    private void onDeadline() {
        if (window.halted().getAsBoolean()) {
            onHalt();
        } else {
            transition(this::claimDeadline).onPresent(this::probeAtDeadline);
        }
    }

    /// Under `transitions`: past the deadline no exit starts; the holders to probe, none once closed.
    private Option<Set<NodeId>> claimDeadline() {
        closing = true;

        return closed
               ? Option.none()
               : Option.some(uncoveredHolders());
    }

    /// Under `transitions`: every holder Safe would rely on for a key owed at a close, with an ack for it no passed probe covers.
    private Set<NodeId> uncoveredHolders() {
        return owed(true).stream()
                   .flatMap(key -> drainTargets(key.stamp.key()).deliverers()
                                               .stream()
                                               .filter(target -> safe(key, target, false)))
                   .collect(Collectors.toSet());
    }

    /// The pre-exit probe path with the probe deadline as its bound; an empty set closes at once.
    private Unit probeAtDeadline(Set<NodeId> uncovered) {
        var until = System.nanoTime() + tuning.resendAfter().nanos();
        var mark = transition(this::nextMark);
        var probes = transition(() -> registerProbes(uncovered));

        sendProbes(probes, until).onSuccess(results -> afterDeadlineProbe(probes, results, mark));

        return Unit.unit();
    }

    @Contract
    private void afterDeadlineProbe(List<Page> probes, List<Result<AttemptResult>> results, long mark) {
        transition(() -> markProbed(passedProbes(probes, results), mark));
        transition(() -> retireProbes(probes));
        transition(() -> closeUnder(window.halted().getAsBoolean()));
    }

    @Contract
    private void onHalt() {
        transition(() -> closeUnder(true));
    }

    /// #3345 P2: the run observes the halt itself, every resend interval, so a run with nothing in flight (no page send polls
    /// the window) resolves within one interval of the halt, not at its deadline.
    @Contract
    private void onHaltWatch() {
        if (window.halted().getAsBoolean()) {
            onHalt();
        }
    }

    /// The resend interval, floored at 1 ms: a fixed-rate period must be positive.
    private TimeSpan haltWatchPeriod() {
        return timeSpan(Math.max(tuning.resendAfter().nanos(),
                                 MIN_HALT_WATCH_NANOS)).nanos();
    }

    /// Under `transitions`, once: report what is owed, raise the closing events (not after a halt), drop every page of the run.
    /// ```tla DHTDepartureDrain:Halt
    /// \* d closes and halts with its in-memory store. Safe(d) at a close: a holder that is one of the key's deliverers in the
    /// \* observation (R, L) and that passes a probe sent after its ack (probeTargets, uncoveredHolders: a halted node answers
    /// \* nothing). The exit (claimExit, afterReread, probeTargets, closeIfClean) closes when no page of d is live and its re-plan,
    /// \* with the inventory re-read and V and L observed afresh, owes nothing: every target holds the key and it is Safe there.
    /// \* Without `Deadline` the run also ends when the key is owed but nothing could still be sent (no deliverer in the fresh
    /// \* observation): its deadline closes it with the key owed. With `Deadline` it may close at any point, from the last
    /// \* observation (onDeadline: no re-read). The report is Safe at the close and outlives d.
    /// \* Without `exit` (the code before #3576): the run halts once every page is acknowledged, and the report is what the acks
    /// \* set (Deliver, Retargeted).
    /// SafeAt(d, R, excl) ==
    ///     \E t \in Held(d, R) : /\ ~Fix("deliverer") \/ t \in Deliverers(R, d, excl, rfAt[d])
    ///                           /\ phase[t] # "halted"
    /// ExitClean(d) ==
    ///     LET R    == Ring(d)
    ///         excl == IF Fix("replan") THEN Excl(d) ELSE leaving[d]
    ///     IN store[d] => /\ DrainTargets(R, d, excl, rfAt[d]) \subseteq Held(d, R)
    ///                    /\ SafeAt(d, R, excl) \/ Deliverers(R, d, excl, rfAt[d]) = {}
    /// Halt(d) ==
    ///     /\ phase[d] = "pushing"
    ///     /\ LET idle  == \A p \in pages : p.from # d
    ///            clean == idle /\ (Fix("exit") => ExitClean(d))
    ///            exL   == IF Fix("replan") THEN Excl(d) ELSE leaving[d]
    ///        IN /\ Deadline \/ clean
    ///           /\ delivered' = IF ~Fix("exit") THEN delivered
    ///                           ELSE [delivered EXCEPT ![d] = IF clean THEN store[d] /\ SafeAt(d, Ring(d), exL)
    ///                                                          ELSE inv[d] /\ SafeAt(d, view[d], leaving[d])]
    ///     /\ phase' = [phase EXCEPT ![d] = "halted"]
    ///     /\ store' = [store EXCEPT ![d] = FALSE]
    ///     /\ pages' = {p \in pages : p.from # d}
    ///     \* A halted node's local state is gone; resetting it keeps equivalent states equal (nothing reads it again).
    ///     /\ known' = [known EXCEPT ![d] = {}]
    ///     /\ pruned' = [pruned EXCEPT ![d] = {}]
    ///     /\ flapped' = [flapped EXCEPT ![d] = {}]
    ///     /\ view' = [view EXCEPT ![d] = {}]
    ///     /\ leaving' = [leaving EXCEPT ![d] = {}]
    ///     /\ inv' = [inv EXCEPT ![d] = FALSE]
    ///     /\ rfAt' = [rfAt EXCEPT ![d] = 0]
    ///     /\ claimed' = [claimed EXCEPT ![d] = {}]
    ///     /\ deliverers' = [deliverers EXCEPT ![d] = {}]
    ///     /\ acked' = [acked EXCEPT ![d] = {}]
    ///     /\ UNCHANGED <<drainers, held0, stale, drainSet, strayNack>>
    /// ```
    private boolean closeUnder(boolean halted) {
        if (closed) {
            return false;
        }

        closed = true;
        var owed = owed(true);
        var sole = owed.stream().filter(key -> key.sole).toList();
        var report = new DeparturePushReport(sole.size(),
                                             owed.size() - sole.size(),
                                             true,
                                             owed.size(),
                                             enumeratedKeys,
                                             (int) owed.stream().filter(key -> key.undeliverable).count(),
                                             digest(owed));

        if (!halted && !sole.isEmpty()) {
            raiseAtRisk(sole);
        }

        if (!halted) {
            reportOutcome(sole, owed);
        }

        pages.forEach(this::drop);
        effects.add(() -> awaitEvents(report).onSuccess(result::succeed));

        return true;
    }

    /// Under `transitions`.
    private void reportOutcome(List<DepartureKey> sole, List<DepartureKey> owed) {
        if (atRiskRaised && sole.isEmpty()) {
            effects.add(() -> log.info("Departure push: every sole-copy chunk has a holder"));
            observe(() -> observer.onSoleCopyDelivered(atRiskCount));
        }

        if (!owed.isEmpty()) {
            effects.add(() -> log.warn("Departure push incomplete: {} chunk(s) owed ({} sole-copy): {}",
                                       owed.size(),
                                       sole.size(),
                                       hexOf(owed)));
            observe(() -> observer.onIncompleteReport(owed.size(), sole.size(), sample(owed)));
        }
    }

    /// FNV-1a 64 over the sorted (key, stamp) pairs; 0 when nothing is owed.
    private static long digest(List<DepartureKey> owed) {
        return owed.isEmpty()
               ? 0L
               : owed.stream()
                     .map(key -> key.stampId()
                                    .digestText())
                     .sorted()
                     .map(text -> text.getBytes(StandardCharsets.UTF_8))
                     .reduce(FNV_OFFSET,
                             DeparturePushRun::fnv,
                             (left, _) -> left);
    }

    private static long fnv(long hash, byte[] bytes) {
        var h = hash;

        for (var b : bytes) {
            h = (h ^ (b & 0xff)) * FNV_PRIME;
        }

        return (h ^ '\n') * FNV_PRIME;
    }

    // --- events ---
    /// Under `transitions`: a sole owed key without a deliverer can never be shown safe, and a window already used up delivers
    /// nothing.
    private Unit raiseAtPlan() {
        var soleOwed = owed(false).stream().filter(key -> key.sole).toList();
        var unpushable = soleOwed.stream().anyMatch(key -> drainTargets(key.stamp.key()).deliverers()
                                                                       .isEmpty());

        return ! closed && !window.halted()
                                  .getAsBoolean() && !soleOwed.isEmpty() && (unpushable || System.nanoTime() >= attemptsDeadline)
               ? raiseAtRisk(soleOwed)
               : Unit.unit();
    }

    /// Once per push, under `transitions`. An empty list means the inventory is unknown (count 0).
    private Unit raiseAtRisk(List<DepartureKey> atRisk) {
        if (!atRiskRaised) {
            atRiskRaised = true;
            atRiskCount = atRisk.size();
            effects.add(() -> log.warn("Departure push: {} sole-copy chunk(s) without a holder: {}",
                                       atRisk.size(),
                                       hexOf(atRisk)));
            observe(() -> observer.onSoleCopyAtRisk(atRisk.size(), sample(atRisk)));
        }

        return Unit.unit();
    }

    /// One state transition: `change` runs under `transitions` and touches in-memory state only; the effects it queued run after
    /// the lock is released.
    private <T> T transition(Supplier<T> change) {
        var value = underTransitions(change);

        runEffects();

        return value;
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

    /// Under `transitions`: the event's promise joins `events` now; the observer is called later, as an effect. An observer that
    /// throws is isolated: its event is lost, never the push.
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

    private static List<String> sample(List<DepartureKey> keys) {
        return keys.stream()
                   .limit(SAMPLE_LIMIT)
                   .map(DepartureKey::hex)
                   .toList();
    }

    private static String hexOf(List<DepartureKey> keys) {
        return keys.stream()
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
