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
/// in V ([#drainTargets]). `Owed = inventory - Safe`. Nothing else counts: not responsible-set membership, not a plan-time survivor,
/// not a target without an ack. Pages of x's previous tenure are dropped from the registry at x's removal edge, so an ack for one
/// finds no page; within one tenure a late ack (its page cancelled by a re-plan) still counts.
///
/// **Re-plan.** One serial [#reconcile] on exactly three triggers: a change of V ([#onViewChange], called after each ring change),
/// a holder lost (a token mismatch, a "draining" nack, a failed probe), and the start and each exit attempt. Acks and nacks never
/// trigger it: an ack records a holder and settles its page, a nack splits a page of several keys in halves after a backoff and
/// resends a single-key page after a growing one (P1). A reconcile re-reads `L = self + drainSet`, computes the desired pages
/// {(t, k): k owed, t a target of k, t not a holder of k}, cancels every live page none of whose pairs is desired (it stays
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
/// **Deadline and events.** The run resolves at the window deadline less `eventDeliveryWait` with its current owed set, the one
/// resolve point of this step besides a passed exit. Classification (S) of a key as SOLE (`|currentSet \ L| - |L \ ring| <= 0` at
/// the time it entered the inventory) only shapes the events: `onSoleCopyAtRisk` once at plan time when a sole key has no
/// deliverer or the window is already used up (or the inventory was not enumerated), `onSoleCopyDelivered` at the end iff it was
/// raised and no sole key is owed, `onIncompleteReport` at the end iff anything is owed. After a halt nothing more is sent or raised.
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

    /// A holder's record in one tenure: its token and the copies its counted acks covered. Guarded by `transitions`.
    private record Holder(long token, Set<StampId> copies) {
        static Holder holder(long token) {
            return new Holder(token, new HashSet<>());
        }
    }

    /// The targets of one key in the current view, and those whose holding makes it safe.
    private record Targets(List<NodeId> all, List<NodeId> deliverers) {}

    /// The outcome of one reconcile: the chains to start once `transitions` is released.
    private record Plan(List<List<Page>> chains) {}

    /// One departure page, registered under its correlation id until its target's removal edge or the end of the run.
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
            return run.underTransitions(() -> keys.stream().map(key -> key.stamp).toList());
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
    private Set<NodeId> view = Set.of();
    private boolean closed;
    private boolean exitInFlight;
    private int liveChains;
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
        this.attemptsDeadline = Math.max(t0, window.deadlineNanos() - tuning.eventDeliveryWait().nanos());
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

        awaitEvents(DeparturePushReport.NOT_ENUMERATED).onSuccess(result::succeed);

        return Unit.unit();
    }

    private Unit start(List<KeyStamp> stamps) {
        transition(() -> enumerate(stamps));
        reconcile();
        transition(this::raiseAtPlan);
        deadlineTimer.set(Option.some(SharedScheduler.schedule(this::onDeadline, remainingUntil(attemptsDeadline))));
        haltWatch.set(Option.some(SharedScheduler.scheduleAtFixedRate(this::onHaltWatch, haltWatchPeriod())));
        if (result.isResolved()) {
            cancelTimers();
        }
        maybeExit();

        return Unit.unit();
    }

    /// Under `transitions`: the first inventory, every key owed.
    private Unit enumerate(List<KeyStamp> stamps) {
        var ringNodes = Set.copyOf(node.ring().nodes());
        var leaving = leavingSet();

        stamps.forEach(stamp -> inventory.put(HEX.formatHex(stamp.key()), classify(stamp, ringNodes, leaving)));
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

        stamps.forEach(stamp -> read.put(HEX.formatHex(stamp.key()), stamp));
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
        log.info("Departure push: holder {} lost ({}){}", holder.id(), why, lost
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
    private Plan replan() {
        if (closed || window.halted().getAsBoolean()) {
            return new Plan(List.of());
        }

        reconciles.incrementAndGet();
        observeView();
        var leaving = leavingSet();
        var desired = new HashMap<NodeId, List<DepartureKey>>();

        inventory.values()
                 .stream()
                 .filter(key -> !key.absent && !key.undeliverable)
                 .forEach(key -> desire(key, leaving, desired));
        pages.stream().filter(DeparturePushRun::isLive).forEach(page -> keepOrCancel(page, desired));

        return new Plan(desired.entrySet()
                               .stream()
                               .filter(entry -> !entry.getValue().isEmpty())
                               .map(entry -> registerPages(entry.getKey(), entry.getValue()))
                               .toList());
    }

    /// Under `transitions`: tenures start at entry into V; at a removal edge the node's pages leave the registry and its holder
    /// record is cleared, so its earlier tenure never counts again.
    private void observeView() {
        var now = Set.copyOf(node.ring().nodes());

        view.stream().filter(member -> !now.contains(member)).forEach(this::removalEdge);
        now.stream().filter(member -> !view.contains(member)).forEach(member -> tenures.merge(member, 1, Integer::sum));
        view = now;
    }

    private void removalEdge(NodeId member) {
        holders.remove(member);
        pages.stream().filter(page -> page.target.equals(member)).forEach(this::drop);
    }

    private void drop(Page page) {
        page.cancelled.set(true);
        registry.remove(page.correlationId());
    }

    /// Under `transitions`: an owed key wants a page on every target that does not hold it.
    private void desire(DepartureKey key, Set<NodeId> leaving, Map<NodeId, List<DepartureKey>> desired) {
        var targets = drainTargets(key.stamp.key(), leaving);

        if (!isSafe(key, targets)) {
            targets.all()
                   .stream()
                   .filter(target -> !holds(target, key))
                   .forEach(target -> desired.computeIfAbsent(target, _ -> new ArrayList<>()).add(key));
        }
    }

    private static boolean isLive(Page page) {
        return !page.isProbe() && !page.cancelled.get() && !page.finished.get();
    }

    /// Under `transitions`: a live page stays when one of its pairs is still desired (those pairs need no new page); otherwise it is
    /// cancelled and stays registered, so a late ack still counts.
    private void keepOrCancel(Page page, Map<NodeId, List<DepartureKey>> desired) {
        var wanted = desired.getOrDefault(page.target, List.of());
        var covered = page.keys.stream().filter(wanted::contains).toList();

        if (covered.isEmpty()) {
            page.cancelled.set(true);
        } else {
            wanted.removeAll(covered);
        }
    }

    private boolean isSafe(DepartureKey key, Targets targets) {
        return targets.deliverers()
                      .stream()
                      .anyMatch(target -> holds(target, key));
    }

    private boolean holds(NodeId target, DepartureKey key) {
        var holder = holders.get(target);

        return holder != null && holder.copies().contains(key.stampId());
    }

    /// Under `transitions`.
    private List<DepartureKey> owed() {
        var leaving = leavingSet();

        return inventory.values()
                        .stream()
                        .filter(key -> !isSafe(key, drainTargets(key.stamp.key(), leaving)))
                        .toList();
    }

    // --- targets ---
    /// Every target of one key: the delta targets ([#departureTargets]) plus, when a node of L lies ahead in the key's walk, the
    /// named prefix and the first node outside L ([#finalPrimaryCandidates]). The deliverers, whose holding makes the key safe, are
    /// the delta targets when no candidates exist, else only the first node outside L: a node of L may halt with the copy (#3208),
    /// and a delta target may lie outside the final replica set (#3209).
    private Targets drainTargets(byte[] key, Set<NodeId> excluded) {
        var delta = departureTargets(key, excluded);
        var candidates = finalPrimaryCandidates(key, excluded);
        var deliverers = candidates.isEmpty()
                         ? delta
                         : candidates.stream().filter(candidate -> !excluded.contains(candidate)).toList();

        return new Targets(Stream.concat(delta.stream(), candidates.stream()).distinct().toList(), deliverers);
    }

    /// The primary of the key's FINAL replica set, whichever L entries turn out stale (#3208, #3209): the named prefix of the walk
    /// without self, plus the first node outside L. Empty when no node of L lies within the first `replicationFactor` nodes.
    private List<NodeId> finalPrimaryCandidates(byte[] key, Set<NodeId> excluded) {
        var walk = node.ring().nodesFor(key,
                                        node.ring().nodeCount(),
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

    // --- pages ---
    /// Under `transitions`: one target's keys split by the page budget, each page tagged with the target's current tenure.
    private List<Page> registerPages(NodeId target, List<DepartureKey> keys) {
        var byStamp = new IdentityHashMap<KeyStamp, DepartureKey>();

        keys.forEach(key -> byStamp.put(key.stamp, key));

        return node.repair()
                   .pageBudget()
                   .pages(keys.stream().map(key -> key.stamp).toList())
                   .stream()
                   .map(page -> registerPage(target, page.stream().map(byStamp::get).toList(), false))
                   .toList();
    }

    /// Under `transitions`.
    private Page registerPage(NodeId target, List<DepartureKey> keys, boolean split) {
        var page = new Page(this, target, tenures.getOrDefault(target, 0), keys, split);

        registry.put(page.correlationId(), page);
        pages.add(page);

        return page;
    }

    @Contract
    private void startChain(List<Page> chain) {
        transition(this::chainStarted);
        runChain(chain, 0).onResultRun(this::chainEnded);
    }

    /// Under `transitions`.
    private Unit chainStarted() {
        liveChains++;

        return Unit.unit();
    }

    @Contract
    private void chainEnded() {
        transition(this::chainStopped);
        maybeExit();
    }

    /// Under `transitions`.
    private Unit chainStopped() {
        liveChains--;

        return Unit.unit();
    }

    private Promise<Unit> runChain(List<Page> chain, int index) {
        return index >= chain.size()
               ? Promise.unitPromise()
               : runPage(chain.get(index)).onResultRun(() -> chain.get(index).finished.set(true))
                                          .flatMap(_ -> runChain(chain, index + 1));
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
        page.sent.set(entries.stream().map(KeyStamp::keyStamp).map(StampId::stampId).toList());

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
        return shouldStop(page) || !node.ring().nodes().contains(page.target)
               ? Promise.unitPromise()
               : dispatch(page, entries).flatMap(dispatched -> afterSend(page, entries, dispatched, nackBackoff));
    }

    /// The push carries L, the set excluded when choosing its target (#1818 L1), so the receiver checks placement against the
    /// same view rather than its own.
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
                                                                 List.copyOf(leavingSet()),
                                                                 List.copyOf(node.ring().nodes())))
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
            case WriteOutcome.Sent _ -> awaitAttempt(dispatched).flatMap(result -> afterAttempt(page, entries, result, nackBackoff));
            case WriteOutcome.EncodeFailed failed -> encodeFailed(page, failed);
            case WriteOutcome.BackpressureRefused _, WriteOutcome.ConnectionDead _, WriteOutcome.NoPeerState _ ->
                pause(tuning.resendAfter()).flatMap(_ -> attempt(page, entries, nackBackoff));
        };
    }

    private Promise<AttemptResult> awaitAttempt(Dispatched dispatched) {
        return dispatched.attempt()
                         .timeout(timeSpan(Math.min(tuning.resendAfter().nanos(), remainingNanos(attemptsDeadline))).nanos())
                         .recover(_ -> AttemptResult.TIMED_OUT);
    }

    private Promise<Unit> afterAttempt(Page page,
                                       List<DHTMessage.KeyValue> entries,
                                       AttemptResult result,
                                       TimeSpan nackBackoff) {
        return switch (result) {
            case ACKED, FAILED, IGNORED -> Promise.unitPromise();
            case NACKED -> pause(nackBackoff).flatMap(_ -> afterNackPause(page, entries, nackBackoff));
            case TIMED_OUT -> attempt(page, entries, nackBackoff);
        };
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
        return Promise.allOf(halves.stream().map(half -> runChain(List.of(half), 0)).toList())
                      .mapToUnit();
    }

    /// Under `transitions`: the nacked page is cancelled and its keys go to two pages on the same target and tenure.
    private List<Page> halvePage(Page page) {
        page.cancelled.set(true);
        var middle = page.keys.size() / 2;

        return List.of(registerPage(page.target, List.copyOf(page.keys.subList(0, middle)), false),
                       registerPage(page.target, List.copyOf(page.keys.subList(middle, page.keys.size())), false));
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

    /// Under `transitions`.
    private List<Page> splitPage(Page page) {
        page.cancelled.set(true);

        return page.keys.stream().map(key -> registerPage(page.target, List.of(key), true)).toList();
    }

    private boolean shouldStop(Page page) {
        if (window.halted().getAsBoolean()) {
            onHalt();
        }

        return page.cancelled.get() || !registry.containsKey(page.correlationId()) || isClosed() || pastAttemptsDeadline();
    }

    /// No attempt starts once the attempts window is used up: the deadline resolves the run, so a send then is never counted.
    private boolean pastAttemptsDeadline() {
        return System.nanoTime() >= attemptsDeadline;
    }

    private boolean isClosed() {
        return underTransitions(() -> closed);
    }

    private Promise<Unit> pause(TimeSpan delay) {
        return Promise.promise(timeSpan(Math.min(delay.nanos(), remainingNanos(attemptsDeadline))).nanos(),
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
            holderLost(page.target, ack.draining()
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
            return AttemptResult.FAILED;
        }

        var holder = holders.computeIfAbsent(page.target, _ -> Holder.holder(ack.bootToken()));

        if (holder.token() != ack.bootToken()) {
            return AttemptResult.FAILED;
        }

        if (ack.applied()) {
            holder.copies().addAll(page.sent.get());
        }

        return ack.applied()
               ? AttemptResult.ACKED
               : AttemptResult.NACKED;
    }

    /// Under `transitions`: registered, its target in V now, and the tenure it was registered in is the target's current one.
    private boolean inTenure(Page page) {
        return registry.containsKey(page.correlationId())
               && node.ring().nodes().contains(page.target)
               && view.contains(page.target)
               && tenures.getOrDefault(page.target, 0) == page.tenure;
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
        var ready = !closed && !exitInFlight && liveChains == 0 && owed().stream().allMatch(key -> key.absent);

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
        var reliedOn = transition(this::probeTargets);

        reliedOn.fold(this::reopen, this::probe);

        return Unit.unit();
    }

    /// Under `transitions`: empty when something is still owed (the exit fails and the re-plan's chains carry on), else the distinct
    /// holders Safe relies on.
    private Option<Set<NodeId>> probeTargets() {
        var leaving = leavingSet();

        return owed().isEmpty()
               ? Option.some(inventory.values()
                                                          .stream()
                                                          .flatMap(key -> drainTargets(key.stamp.key(), leaving).deliverers()
                                                                                                                 .stream()
                                                                                                                 .filter(target -> holds(target, key)))
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

    /// Send an EMPTY page to every holder Safe relies on; pass iff each answers within `resendAfter` in its tenure with its token,
    /// and no holder was lost meanwhile (a loss between a passed probe and the close re-opens the run).
    private Unit probe(Set<NodeId> reliedOn) {
        var lossesAtStart = holderLosses.get();
        var probes = transition(() -> reliedOn.stream().map(target -> registerPage(target, List.of(), false)).toList());

        Promise.allOf(probes.stream().map(this::probeOne).toList())
               .onSuccess(results -> afterProbe(probes, results, lossesAtStart));

        return Unit.unit();
    }

    private Promise<AttemptResult> probeOne(Page page) {
        return shouldStop(page)
               ? Promise.success(AttemptResult.FAILED)
               : dispatch(page, List.of()).flatMap(this::probeAnswer);
    }

    private Promise<AttemptResult> probeAnswer(Dispatched dispatched) {
        return dispatched.outcome() instanceof WriteOutcome.Sent
               ? awaitAttempt(dispatched)
               : Promise.success(AttemptResult.FAILED);
    }

    @Contract
    private void afterProbe(List<Page> probes, List<Result<AttemptResult>> results, int lossesAtStart) {
        var failed = new ArrayList<NodeId>();

        for (int i = 0; i < probes.size(); i++) {
            if (!results.get(i).fold(_ -> false, DeparturePushRun::probePassed)) {
                failed.add(probes.get(i).target);
            }
        }

        transition(() -> retireProbes(probes));
        if (failed.isEmpty() && transition(() -> closeIfClean(lossesAtStart))) {
            return;
        }

        transition(this::releaseExit);
        failed.forEach(target -> holderLost(target, "probe failed"));
        maybeExit();
    }

    private static boolean probePassed(AttemptResult result) {
        return result == AttemptResult.ACKED || result == AttemptResult.NACKED;
    }

    /// Under `transitions`.
    private Unit retireProbes(List<Page> probes) {
        probes.forEach(this::drop);
        pages.removeAll(probes);

        return Unit.unit();
    }

    /// Under `transitions`: closes when no holder was lost since the probe started and nothing is owed.
    private boolean closeIfClean(int lossesAtStart) {
        var clean = holderLosses.get() == lossesAtStart && owed().isEmpty() && !closed;

        if (clean) {
            closeUnder(false);
        }

        return clean;
    }

    // --- close ---
    @Contract
    private void onDeadline() {
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
        return timeSpan(Math.max(tuning.resendAfter().nanos(), MIN_HALT_WATCH_NANOS)).nanos();
    }

    /// Under `transitions`, once: report what is owed, raise the closing events (not after a halt), drop every page of the run.
    private boolean closeUnder(boolean halted) {
        if (closed) {
            return false;
        }

        closed = true;
        var owed = owed();
        var sole = owed.stream().filter(key -> key.sole).toList();
        var report = new DeparturePushReport(sole.size(),
                                             owed.size() - sole.size(),
                                             true,
                                             owed.size(),
                                             enumeratedKeys,
                                             (int) owed.stream().filter(key -> key.undeliverable).count(),
                                             digest(owed));

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
                     .map(key -> key.stampId().digestText())
                     .sorted()
                     .map(text -> text.getBytes(StandardCharsets.UTF_8))
                     .reduce(FNV_OFFSET, DeparturePushRun::fnv, (left, _) -> left);
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
        var leaving = leavingSet();
        var soleOwed = owed().stream().filter(key -> key.sole).toList();
        var unpushable = soleOwed.stream().anyMatch(key -> drainTargets(key.stamp.key(), leaving).deliverers().isEmpty());

        return !closed && !window.halted().getAsBoolean() && !soleOwed.isEmpty()
               && (unpushable || System.nanoTime() >= attemptsDeadline)
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
