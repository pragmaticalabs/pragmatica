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
/// Distributed hash table: ring placement, replication, anti-entropy and the graceful-departure push.
///
/// The TLA+ model `DHTDepartureDrain` (#3049) of the departure drain lives in doc-comment fragments: this skeleton carries
/// the state, the ring and placement helpers, the environment (drain commands and the leader's drain set, raised outside this
/// module), `Init`, `Next`, fairness and the properties; each action sits on the code that implements it
/// ([DHTTopologyListener] for departure detection, [DeparturePushRun] for the push, [DHTAntiEntropy] for the receiver).
/// `tools/tla/check.sh DHTDepartureDrain <variant> integrations/dht/src/main/java` extracts and checks one config;
/// `tools/tla/run.sh` runs every config with its expected verdict (`tools/tla/models.tsv`). Results are bounds, not proofs: a
/// passing config says no violation exists within its constants.
///
/// What the model leaves out, so a pass says nothing about it: the survivor-side rebalance ([DHTRebalancer#onNodeRemoved]),
/// anti-entropy, joins, deletes and versions (one key, one value), the page windows except as the `Deadline` switch, the
/// at-risk events except as the `delivered` report, node crashes, and the failures that halt a page with its keys at risk
/// (a page read that fails, an encode failure, an inventory not enumerated in time: in the abstract each is the `Deadline`
/// arm, which `abandon` shows red). The two left-out copy paths act in opposite directions:
/// - The survivor-side rebalance only ADDS copies: the primary replica pushes the key to its co-replicas. It needs a copy on a
///   final replica to start from, so it can never undo a NoLastCopyLoss or ReplicaPlacement violation. Leaving out a path that
///   only adds copies can only make the model report a loss the system would repair, never hide one; this one cannot even do that.
/// - Anti-entropy REMOVES copies: `purgeStrays` drops every value of a partition a node stopped replicating once the stray
///   horizon has passed ([DHTAntiEntropy] `synchronizePartitions`, [DHTNode] `dropStray`). Leaving it out can HIDE a loss: a
///   copy that NoLastCopyLoss counts may sit on a node that is not a replica in the final ring, where no read finds it and
///   where a purge may later drop it. ReplicaPlacement is the invariant that does not count such a copy.
///
/// The plan is one step, while the code reads the ring several times in [DeparturePushRun] `drainTargets` (three times in
/// `departureTargets`, once more in `finalPrimaryCandidates`); torn reads are not modelled. A send, its receipt and its ack
/// are one step: the receiver judges the page against its state at receipt, and the pusher's carried leaving set and view
/// are the pusher's state at that step. `Deadline = FALSE` assumes every page settles inside its window; halting with pages
/// unacknowledged is the code's normal deadline path ([DeparturePushRun] `onDeadline` closes the run at `attemptsDeadline`,
/// `shouldStop` stops pages there), so the `code` pass holds only for drains whose pages all settle in time. `Waves = FALSE` assumes the ping that commands the first drainer names every co-drainer (DhtDeparturePush reads
/// the drain set at push time); two operator drains issued minutes apart are the `waves` case, which is red. `Flap` says
/// which stale-entry flaps happen; `code` allows flaps seen by other nodes ("peer").
///
/// Bounds: every run record (constants, distinct states, depth, and whether TLC finished with an empty queue) is in this
/// model's `tools/tla/models.tsv` rows; a run that did not finish is recorded there as bounded, never as a pass.
///
/// #3208 and #3209, fixed by #3225, are pinned both ways: `allnamed` and `strand` pass at the code, and `allnamedpre` and
/// `strandpre` (the final-primary targeting removed) are red; `nonamedself` pins the receiver half of #3208 and
/// `nodeliverer` the deliverer rule. Two flap shapes remain red at the code, see `Flap`: `allnamedflap` (NoLastCopyLoss,
/// at-risk raised) and `flap` (ReceiverAgrees).
///
/// What the passing `code` row does NOT exercise (its invariants are vacuous or its paths unreached there):
/// - ReportTruthful without `Deadline` or `Waves`: a deliverer is a node no drainer names, so it never drains, and it stores the
///   copy in the step that delivers the key. It has content in `report` (pass), `nodeliverer` and `wavesset` (red).
/// - A non-holder credit: the model does not distinguish it at these bounds (#3590). A receiver that acks a page it dropped as
///   strays (the credit #3576 removes) leaves `code`-shaped runs green at N=4 RF=2 and RF=3 with MaxDrain=1 (a mutant of the
///   `Deliver` ack, probed 2026-11-23), so #3576's defect is pinned by its Java tests only.
/// - `Replan` fires in `code` and `live` (TLC action coverage): a page to a named node is re-planned when that node's
///   DEPARTING edge or flap prunes it from the pusher's ring (a view change, `onViewChange`). Whether a re-plan ever delivers
///   the key at once through a deliverer that had already acked is not measured. With MaxDrain = 1 every `Halt` leads to a state
///   outside CONSTRAINT `Active`: TLC checks the invariants there but does not count it, so `report` and `code` count the
///   same distinct states although `Deadline` adds halts (a probe adding NoLastCopyLoss to `report` is red at `Halt`).
/// - Since #3225 the `exhaust` fallback matters for NoLastCopyLoss only when no named node lies within the first rf nodes of
///   the walk (otherwise the final-primary candidates give a target): `noexhaust` is red at N=3 RF=3 MaxStale=0, where rf
///   equals the ring size and a drainer holding an under-replicated key counts the existing replicas as holders.
/// - `nopruned` cannot turn NoLastCopyLoss red at any bound: with `selfcheck` and `exhaust` the delta branch always has a
///   target and, without `Deadline`, a pending page keeps its drainer alive. The `pruned` guard protects replica degree;
///   ReplicaDegree is the invariant under which `nopruned` is red.
/// - `wideaccept` is not expressible: dropping a refusal only adds deliveries, and the guard refuses a departure push from a
///   sender that is neither departing nor a replica, which this model never produces (every pusher is a commanded drainer,
///   no drain is cancelled, and one unversioned value makes a stray write invisible). Its only modelled effect is a delay.
/// - DrainCompletes has its negative control in `nohaltfair` (no fairness on `Halt`). A liveness twin of `noview` was never
///   red and was removed: the safety config `noview` is the red one.
///
/// ```tla DHTDepartureDrain:head
/// EXTENDS Naturals, FiniteSets, Sequences
///
/// \* One key on a ring of N nodes; the key's partition walks the ring in the order 1..N (ConsistentHashRing.nodesFor: the
/// \* first rf distinct nodes clockwise). Every ring geometry of one key is reached by choosing, in Init, which nodes drain,
/// \* which stale entries the drain set carries and which nodes hold the key (any non-empty set: under-replicated, strays).
/// \* RF is DHTConfig.replicationFactor; the effective factor is min(RF, ring size) on the node that computes it.
/// \* MaxDrain bounds the co-drainers, MaxStale the stale drain-set entries (named by the leader, never draining; one may
/// \* flap: enter DEPARTING on an observer and recover). At least one node does not drain: draining every node is a cluster
/// \* shutdown, not a departure. A stale entry is a live member that never halts, so a drain set that names every other node
/// \* still leaves a survivor: N=3 RF=2 with two drainers and one stale entry lost the last copy before #3225 (#3208, `allnamed`).
/// \* Waves: drain commands arrive in separate leader pings, so a drainer may plan before a later co-drainer is named.
/// \* FALSE models the drain DhtDeparturePush documents: every co-drainer is named in the ping that commands the first. Every
/// \* re-plan re-reads the drain set the drainer last received (DeparturePushRun.observeView -> leavingSet), the exit's included,
/// \* so a co-drainer named before the exit is excluded there; one the drainer has not yet learned of is not (`wavesset`, red).
/// \* Deadline: a drainer may close with keys owed at any point: the attempts deadline (onDeadline: one probe round, then
/// \* closeUnder with what is owed), a halted window, or a page that fails for good. FALSE assumes every page settles inside its
/// \* window: a run then ends by its exit (claimExit, closeIfClean) or, with nothing left that it could send, at its deadline.
/// \* HaltFair: Halt is weakly fair. FALSE is the negative control of DrainCompletes (`nohaltfair`).
/// \* Flap: which stale-entry flaps happen ("none", "peer": seen by other nodes, "self": also by the entry itself). A flap
/// \* prunes the entry from the observer's ring until it recovers, which may never happen. Pages reach named nodes only since
/// \* #3225, so two flap shapes matter there: a drainer that sees its only surviving candidate flap no longer has it in its
/// \* view, so it has no page to it and nothing it can send, and its deadline may close it with the copy before the entry
/// \* recovers (`allnamedflap`, red); and an entry that pruned itself is in no walk of its own ring, so it refuses the page even
/// \* when the pusher names it (`flap`, red).
/// \* Fixed: the protections the code carries (AllFixes). A config that drops one models the code without it:
/// \*   drainset  - the leader's drain set exists and reaches nodes (the test's empty-set arm drops it)
/// \*   codrain   - the push excludes the co-drainers it knows (#1818: DeparturePushRun.leavingSet)
/// \*   exhaust   - the push falls back to the whole post-departure set when the exclusion exhausts the ring (#1818)
/// \*   selfcheck - ... and when self is not in the key's current set (a stray copy: no newcomer can be identified)
/// \*   pruned    - ... and when an excluded node already left the ring (#2684 F3)
/// \*   view      - the receiver judges placement in the pusher's view without the pusher's leaving set (#1818 L1)
/// \*   accept    - the receiver accepts a push only from a sender it knows to be departing or that is a current replica
/// \*   primary   - the push also targets the final replica set's primary candidates: the drain-set-named prefix of the walk
/// \*               without self, plus the first unnamed node (#3208, #3209: DeparturePushRun.finalPrimaryCandidates)
/// \*   deliverer - only a holder that is one of the key's deliverers makes it safe (DeparturePushRun.isSafe over
/// \*               drainTargets' deliverers); without it any holder does, as before #3225
/// \*   namedself - a receiver the pusher names in its leaving set keeps itself in its post-departure view (#3208:
/// \*               DHTAntiEntropy.leftInDepartureView), so it places the rescue copy instead of refusing it as a stray
/// \*   replan    - every re-plan observes the view and the drain set afresh and recomputes the key's targets and deliverers
/// \*               from them, paging every target that is not a holder (#3576: replan, observeView, owedTargets); without it,
/// \*               the code before #3576: the leaving set frozen at the plan, a target that left the ring joins it and the key
/// \*               is paged only to targets not claimed before (Retarget), the deliverers only accumulate
/// \*   exit      - the run closes cleanly only by its exit: nothing sendable is owed, the inventory is re-read and re-planned,
/// \*               and every key is Safe at a holder that passes a probe; the report is Safe at the close (#3576: claimExit,
/// \*               afterReread, probeTargets, closeIfClean, owed(true)); without it, the code before #3576: halts once every
/// \*               page is acknowledged, and the report is set by a deliverer's ack and never withdrawn
/// \*   revoke    - a holder that leaves the view no longer counts (#3576: safe() asks V and the tenure); without it, an ack
/// \*               counts for the rest of the run
/// \* A known-bad models the code its defect lived in, so a fix named after it stays off there: every fail row that drops a
/// \* fix, and `waves`, omits replan, exit and revoke (#3580: with exit on, emptyset, nocodrain and waves find no violation
/// \* in 900 s; with replan or revoke alone on, each still fails).
/// CONSTANTS N, RF, MaxDrain, MaxStale, Waves, Deadline, HaltFair, Flap, Fixed
///
/// AllFixes == {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "view", "accept", "primary", "deliverer", "namedself",
///              "replan", "exit", "revoke"}
/// ASSUME Fixed \subseteq AllFixes /\ Flap \in {"none", "peer", "self", "settle"}
/// Fix(f) == f \in Fixed
///
/// Nodes == 1..N
/// Order == [i \in 1..N |-> i]
/// Min(a, b) == IF a < b THEN a ELSE b
///
/// VARIABLES
///     drainers,   \* the nodes that drain (fixed in Init)
///     held0,      \* held0[n]: n held the key in Init (history, read only by ReplicaPlacement and ReplicaDegree)
///     stale,      \* drain-set entries that never drain (fixed in Init)
///     drainSet,   \* the leader's drain set (ClusterSyncCollector.commandedDrainNodes as the leader sends it)
///     known,      \* known[n]: the drain set as node n last received it (DeparturePushRun.drainSet supplier)
///     pruned,     \* pruned[n]: nodes removed from n's ring (DEPARTING edge or NodeRemoved); n's ring is Nodes \ pruned[n]
///     flapped,    \* flapped[n]: stale nodes n has already seen enter DEPARTING (one flap each)
///     store,      \* store[n]: n holds the key
///     phase,      \* "member" | "draining" (commanded) | "pushing" (the run started) | "halted"
///     view,       \* view[d]: DeparturePushRun.view, the ring d observed at its last re-plan (V)
///     leaving,    \* leaving[d]: DeparturePushRun.observedLeaving, the leaving set of that observation (L)
///     inv,        \* inv[d]: the key is in DeparturePushRun.inventory (enumerated at start, re-read at an exit)
///     rfAt,       \* rfAt[d]: the replication factor the push was built with
///     pages,      \* registered, unacknowledged, live departure pages [from, to]
///     claimed,    \* claimed[d]: the targets planned so far (read only without `replan`: the code before #3576)
///     strayNack,  \* a receiver refused, as a stray, a page from a sender it accepted (history)
///     deliverers, \* deliverers[d]: drainTargets' deliverers in the last observation (the targets whose holding makes it safe)
///     acked,      \* acked[d]: DeparturePushRun.holders (the targets with a counted applied ack in their current tenure)
///     delivered   \* delivered[d]: d's report: the key is delivered (kept after d halts)
///
/// vars == <<drainers, held0, stale, drainSet, known, pruned, flapped, store, phase, view, leaving, inv, rfAt, pages, claimed,
///           strayNack, deliverers, acked, delivered>>
///
/// \* ConsistentHashRing.nodesFor(key, rf[, filter]) as a set: the first rf members of `set` met in ring order.
/// Walk(set, rf) == LET s == SelectSeq(Order, LAMBDA x : x \in set) IN {s[i] : i \in 1..Min(rf, Len(s))}
/// Ring(n) == Nodes \ pruned[n]
/// \* DHTConfig.effectiveReplicationFactor(nodeCount)
/// RFOf(ring) == Min(RF, Cardinality(ring))
/// \* n's current responsible set for the key (replicaOfEntry)
/// Cur(n) == Walk(Ring(n), RFOf(Ring(n)))
/// \* DhtDeparturePush.departingSenders on n: named by the drain set n received, or seen entering DEPARTING
/// \* DHTTopologyListener.departing is read as pruned[n]: they differ only on a drainer n learned of by NodeRemoved alone, which
/// \* has halted (it sends nothing) and is outside n's ring (no walk meets it), so no judgement can tell them apart.
/// Departing(n, x) == x \in known[n] \/ x \in pruned[n]
///
/// \* The push helpers read one observation: the ring R (DeparturePushRun.observed, whose members are V) and the leaving set
/// \* `excl` (L), both taken together by observeView.
/// \* DeparturePushRun.departureTargets: newSet is the post-departure set; only its newcomers are targeted when self is in the
/// \* current set, the exclusion leaves a full newSet and every excluded node is still in the ring; otherwise all of newSet.
/// DepartureTargets(R, d, excl, rf) ==
///     LET newSet == Walk(R \ excl, rf)
///         cur    == Walk(R, rf)
///         delta  == /\ (Fix("selfcheck") => d \in cur)
///                   /\ (Fix("exhaust") => Cardinality(newSet) >= rf)
///                   /\ (Fix("pruned") => excl \subseteq R)
///     IN IF delta THEN newSet \ (cur \ excl) ELSE newSet
///
/// \* DeparturePushRun.finalPrimaryCandidates walks the whole ring without self (nodesFor(key, nodeCount, not self)).
/// WalkSeq(R, d) == SelectSeq(Order, LAMBDA x : x \in R /\ x # d)
/// \* The index of the first node of `w` outside `excl`, or Len(w) + 1 when `excl` holds every node of `w`.
/// FirstOutside(w, excl) ==
///     LET idx == {i \in 1..Len(w) : w[i] \notin excl}
///     IN IF idx = {} THEN Len(w) + 1 ELSE CHOOSE i \in idx : \A j \in idx : i <= j
/// \* finalPrimaryCandidates: when a named node (one in `excl`) lies within the first rf nodes of the walk, the named prefix
/// \* of the walk plus the first unnamed node; otherwise none.
/// Candidates(R, d, excl, rf) ==
///     LET w == WalkSeq(R, d)
///     IN IF Fix("primary") /\ \E i \in 1..Min(rf, Len(w)) : w[i] \in excl
///        THEN {w[i] : i \in 1..Min(FirstOutside(w, excl), Len(w))}
///        ELSE {}
/// \* DeparturePushRun.drainTargets: the delta targets plus the candidates (Targets.all) ...
/// DrainTargets(R, d, excl, rf) == DepartureTargets(R, d, excl, rf) \cup Candidates(R, d, excl, rf)
/// \* ... and its deliverers: the delta targets when there are no candidates, else the candidates outside `excl` (the first
/// \* unnamed node, or none).
/// Deliverers(R, d, excl, rf) ==
///     LET c == Candidates(R, d, excl, rf) IN IF c = {} THEN DepartureTargets(R, d, excl, rf) ELSE c \ excl
/// \* DeparturePushRun.classify: sole when the current set holds no survivor beyond the excluded nodes already pruned (read
/// \* only by the plan-time report without `exit`: the code before #3576).
/// Sole(R, d, excl, rf) == Cardinality(Walk(R, rf) \ excl) - Cardinality(excl \ R) <= 0
/// \* DeparturePushRun.leavingSet: the drain set d last received, and self.
/// Excl(d) == (IF Fix("codrain") THEN known[d] ELSE {}) \cup {d}
/// \* The holders d counts in the observation of ring R: DeparturePushRun.safe asks V and the tenure, so a holder outside R
/// \* (it left the view, and a re-entry starts a new tenure with no counted ack) counts no more.
/// Held(d, R) == IF Fix("revoke") THEN acked[d] \cap R ELSE acked[d]
/// \* The holders that make d's key Safe in the observation (R, excl): DeparturePushRun.isSafe, a holder among the deliverers.
/// SafeBy(d, R, excl) == {t \in Held(d, R) : ~Fix("deliverer") \/ t \in Deliverers(R, d, excl, rfAt[d])}
/// \* ... and Safe at a close: such a holder passes a probe sent after its ack (safe(key, target, true), probeTargets,
/// \* uncoveredHolders); a halted node answers nothing.
/// SafeAt(d, R, excl) == \E t \in SafeBy(d, R, excl) : phase[t] # "halted"
/// \* DeparturePushRun.claimExit: no live page of d, and the key of its inventory Safe in the last observation (owed(false), no
/// \* probe). Only then does the exit re-read the inventory and re-plan (afterReread).
/// Claim(d) ==
///     /\ \A p \in pages : p.from # d
///     /\ inv[d] => SafeBy(d, view[d], leaving[d]) # {}
/// \* The leaving set an exit observes: re-read by its re-plan (observeView), or the frozen one without `replan`.
/// ExitL(d) == IF Fix("replan") THEN Excl(d) ELSE leaving[d]
/// \* The exit closes (probeTargets, probe, closeIfClean) when the key, re-read from the store, is Safe through a probe in the
/// \* observation its re-plan takes; pages that re-plan registers are dropped by the close.
/// ExitSafe(d) == store[d] => SafeAt(d, Ring(d), ExitL(d))
///
/// \* DHTAntiEntropy.acceptDeparturePush: the sender is departing in t's view, or a current replica of the key in t's ring.
/// Accepted(t, d) == ~Fix("accept") \/ Departing(t, d) \/ d \in Cur(t)
/// \* DHTAntiEntropy.replicaHereAroundDeparture: t is a current replica, or within rf of t's ring restricted to the pusher's
/// \* view (the V the page carries) without the pusher, its carried leaving set and every node t knows to be departing.
/// \* A receiver the pusher names keeps itself (DHTAntiEntropy.namedSelf, #3208).
/// NamedSelf(t, d, c) == Fix("namedself") /\ Fix("view") /\ c = t /\ c \in leaving[d]
/// PostView(t, d) ==
///     Walk({c \in Ring(t) : /\ (Fix("view") => c \in view[d] /\ (c \notin leaving[d] \/ NamedSelf(t, d, c)))
///                           /\ c # d
///                           /\ (~Departing(t, c) \/ NamedSelf(t, d, c))},
///          RFOf(Ring(t)))
/// Placed(t, d) == t \in Cur(t) \/ t \in PostView(t, d)
///
/// \* A registered departure page that is not yet acknowledged (an ack removes it, a nack leaves it pending).
/// Page(d, t) == [from |-> d, to |-> t]
/// Pending(d, t) == Page(d, t) \in pages
/// ```
///
/// ```tla DHTDepartureDrain:tail
/// \* -- Environment: drain commands and the leader's drain set (aether-node: DrainAssembly, ClusterSyncCollector) --
/// \* The leader commands d to drain: the ping that carries the command carries the drain set, which d records before its
/// \* drain handler fires (DhtDeparturePush). Without Waves every drainer and every stale entry is in the set from the start.
/// Command(d) ==
///     /\ d \in drainers
///     /\ phase[d] = "member"
///     /\ drainSet' = IF Fix("drainset") THEN drainSet \cup {d} ELSE drainSet
///     /\ known' = [known EXCEPT ![d] = drainSet']
///     /\ phase' = [phase EXCEPT ![d] = "draining"]
///     /\ UNCHANGED <<drainers, held0, stale, pruned, flapped, store, view, leaving, inv, rfAt, pages, claimed, strayNack,
///                deliverers, acked, delivered>>
///
/// \* A later leader ping reaches n with the current drain set.
/// LearnDrain(n) ==
///     /\ phase[n] # "halted"
///     /\ known[n] # drainSet
///     /\ known' = [known EXCEPT ![n] = drainSet]
///     /\ UNCHANGED <<drainers, held0, stale, drainSet, pruned, flapped, store, phase, view, leaving, inv, rfAt, pages, claimed,
///                strayNack, deliverers, acked, delivered>>
///
/// Init ==
///     /\ drainers \in {D \in SUBSET Nodes : Cardinality(D) \in 1..MaxDrain /\ D # Nodes}
///     /\ stale \in {S \in SUBSET (Nodes \ drainers) : Cardinality(S) <= MaxStale}
///     /\ drainSet = IF ~Fix("drainset") THEN {} ELSE IF Waves THEN stale ELSE drainers \cup stale
///     /\ known = [n \in Nodes |-> {}]
///     /\ pruned = [n \in Nodes |-> {}]
///     /\ flapped = [n \in Nodes |-> {}]
///     /\ store \in {f \in [Nodes -> BOOLEAN] : \E n \in Nodes : f[n]}
///     /\ held0 = store
///     /\ phase = [n \in Nodes |-> "member"]
///     /\ view = [n \in Nodes |-> {}]
///     /\ leaving = [n \in Nodes |-> {}]
///     /\ inv = [n \in Nodes |-> FALSE]
///     /\ rfAt = [n \in Nodes |-> 0]
///     /\ pages = {}
///     /\ claimed = [n \in Nodes |-> {}]
///     /\ strayNack = FALSE
///     /\ deliverers = [n \in Nodes |-> {}]
///     /\ acked = [n \in Nodes |-> {}]
///     /\ delivered = [n \in Nodes |-> FALSE]
///
/// \* Once every drainer has halted no copy can move or vanish (no pages remain, survivors never halt): the run is over. Every
/// \* config stops exploring there (CONSTRAINT Active in `common`); the state itself is still checked.
/// \* Flap = "settle" keeps the run open while a flapped stale entry is still out of a live node's ring, so FlapRestored sees the
/// \* recovery (or its absence) instead of the cut ending the run first.
/// Active == \/ \E d \in drainers : phase[d] # "halted"
///           \/ Flap = "settle" /\ \E o \in Nodes, x \in stale : phase[o] # "halted" /\ x \in pruned[o]
///
/// Next ==
///     \/ \E d \in Nodes : Command(d) \/ Replan(d) \/ Halt(d)
///     \/ \E n \in Nodes : LearnDrain(n)
///     \/ \E o, x \in Nodes : DepartingEdge(o, x) \/ Recover(o, x) \/ Removed(o, x)
///     \/ \E d, t \in Nodes : Deliver(d, t)
///
/// \* A commanded drainer starts, re-plans on each trigger and halts once its push resolves; every live node eventually
/// \* receives the drain set and sees every drainer enter DEPARTING and, once halted, removed; a page that can be delivered
/// \* eventually is. A stale entry's flap is not fair: it may never happen, but a flap that happened is recovered (the entry
/// \* reaches MEMBER again).
/// Fairness ==
///     /\ \A d \in Nodes : WF_vars(Command(d)) /\ WF_vars(Replan(d)) /\ WF_vars(HaltFair /\ Halt(d))
///     /\ \A n \in Nodes : WF_vars(LearnDrain(n))
///     /\ \A o, x \in Nodes : WF_vars(x \in drainers /\ DepartingEdge(o, x)) /\ WF_vars(Removed(o, x))
///     /\ \A o, x \in Nodes : WF_vars(Recover(o, x))
///     /\ \A d, t \in Nodes : WF_vars(Deliver(d, t))
///
/// Spec == Init /\ [][Next]_vars /\ Fairness
///
/// \* -- Properties --
/// TypeOK ==
///     /\ drainers \subseteq Nodes /\ stale \subseteq Nodes /\ drainSet \subseteq Nodes
///     /\ phase \in [Nodes -> {"member", "draining", "pushing", "halted"}]
///     /\ store \in [Nodes -> BOOLEAN]
///     /\ \A p \in pages : p.from \in Nodes /\ p.to \in Nodes
///
/// \* The key had a copy before the drain (Init); some node that has not halted always holds one. A halted node's store is
/// \* gone, so once every drainer has halted the key survives only on a node that stays.
/// NoLastCopyLoss == \E n \in Nodes : phase[n] # "halted" /\ store[n]
///
/// \* #3208, #3209: a drainer reports its key delivered only while a node that never drains holds a copy. The code targets
/// \* drain-set members on purpose (an entry may be stale), so only a deliverer's ack is evidence: a named target may halt
/// \* with the copy. A key not reported delivered raises the at-risk or incomplete event instead. No node that stays ever
/// \* drops a copy in this model, so the copy that made the report true is still there.
/// ReportTruthful == \A d \in drainers : delivered[d] => \E n \in Nodes \ drainers : store[n]
///
/// \* #1818 L1: a receiver that never drains and accepts the sender never refuses the page as a stray, so no page to a node
/// \* that stays is nacked forever (a refusing co-drainer halts and the page is re-planned, see Deliver).
/// ReceiverAgrees == ~strayNack
///
/// AllHalted == \A d \in drainers : phase[d] = "halted"
///
/// \* #3209: a key whose copies were all on drainers ends on a member of the final replica set (the ring without the drainers;
/// \* stale entries stay members), where reads find it and no stray purge drops it. NoLastCopyLoss counts any copy.
/// ReplicaPlacement ==
///     (AllHalted /\ \A n \in Nodes \ drainers : ~held0[n]) => \E n \in Walk(Nodes \ drainers, RF) : store[n]
///
/// \* #2684 F3: a key placed exactly on its replica set keeps its degree: once every drainer has halted, as many nodes hold it
/// \* as the ring outside the drain set can carry (a named stale entry receives a copy only as a final-primary candidate,
/// \* so those do not count).
/// ReplicaDegree ==
///     (AllHalted /\ {n \in Nodes : held0[n]} = Walk(Nodes, RF))
///         => Cardinality({n \in Nodes : store[n]}) >= Min(RF, Cardinality(Nodes \ (drainers \cup stale)))
///
/// \* Every drainer's push resolves and it halts.
/// DrainCompletes == <>AllHalted
///
/// \* The ring view only ever restores a node that is still alive: once a halted drainer is out of a live node's ring (the
/// \* DEPARTING edge or NodeRemoved pruned it), nothing puts it back. The code: onNodeRemoved drops the recorded prune, so a later
/// \* onNodeRecovered restores nothing. A node that halts loses its ring view (Halt resets it) and is exempt. No data invariant
/// \* sees this: a re-added dead node only adds ring states already reachable before Removed fires, so it is an action property.
/// HaltedStaysRemoved ==
///     [][\A o, x \in Nodes : (x \in drainers /\ phase[x] = "halted" /\ x \in pruned[o] /\ phase'[o] # "halted") => x \in pruned'[o]]_vars
///
/// \* A stale entry that flapped is restored to every live node's ring: a prune is not permanent (#3415). Without Recover the
/// \* ring of a surviving node keeps the entry out forever and no copy is ever placed on it again; no safety invariant sees it,
/// \* the data is only placed on fewer nodes. A node that halted stops acting and is exempt.
/// FlapRestored ==
///     \A o, x \in Nodes : (x \in stale /\ x \in pruned[o]) ~> (phase[o] = "halted" \/ x \notin pruned[o])
/// ```
///
/// ```tlacfg DHTDepartureDrain:common
/// CONSTANTS N = 5
///           RF = 3
///           MaxDrain = 2
///           MaxStale = 1
/// SPECIFICATION Spec
/// CONSTRAINT Active
/// INVARIANT TypeOK
/// ```
///
/// ```tlacfg DHTDepartureDrain:code
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "peer"
///           Fixed <- AllFixes
/// INVARIANT NoLastCopyLoss
/// INVARIANT ReportTruthful
/// INVARIANT ReceiverAgrees
/// INVARIANT ReplicaDegree
/// INVARIANT ReplicaPlacement
/// ```
///
/// ```tlacfg DHTDepartureDrain:live
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "peer"
///           Fixed <- AllFixes
/// PROPERTY DrainCompletes
/// ```
///
/// ```tlacfg DHTDepartureDrain:flaplive
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "settle"
///           Fixed <- AllFixes
/// PROPERTY FlapRestored
/// ```
///
/// ```tlacfg DHTDepartureDrain:removed
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "peer"
///           Fixed <- AllFixes
/// PROPERTY HaltedStaysRemoved
/// ```
///
/// ```tlacfg DHTDepartureDrain:noexhaust
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "self"
///           Fixed = {"drainset", "codrain", "selfcheck", "pruned", "view", "accept", "primary", "deliverer", "namedself"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:noselfcheck
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "self"
///           Fixed = {"drainset", "codrain", "exhaust", "pruned", "view", "accept", "primary", "deliverer", "namedself"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:nocodrain
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "self"
///           Fixed = {"drainset", "exhaust", "selfcheck", "pruned", "view", "accept", "primary", "deliverer", "namedself"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:emptyset
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "self"
///           Fixed = {"codrain", "exhaust", "selfcheck", "pruned", "view", "accept", "primary", "deliverer", "namedself"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:noview
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "peer"
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "accept", "primary", "deliverer", "namedself"}
/// INVARIANT ReceiverAgrees
/// ```
///
/// ```tlacfg DHTDepartureDrain:nopruned
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "self"
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "view", "accept", "primary", "deliverer", "namedself"}
/// INVARIANT ReplicaDegree
/// ```
///
/// ```tlacfg DHTDepartureDrain:nofallback
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "self"
///           Fixed = {"drainset", "codrain", "view", "accept", "primary", "deliverer", "namedself"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:wideaccept
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "peer"
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "view", "primary", "deliverer", "namedself",
///                    "replan", "exit", "revoke"}
/// INVARIANT NoLastCopyLoss
/// INVARIANT ReportTruthful
/// INVARIANT ReceiverAgrees
/// ```
///
/// ```tlacfg DHTDepartureDrain:abandon
/// CONSTANTS Waves = FALSE
///           Deadline = TRUE
///           HaltFair = TRUE
///           Flap = "self"
///           Fixed <- AllFixes
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:waves
/// CONSTANTS Waves = TRUE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "self"
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "view", "accept", "primary", "deliverer", "namedself"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:wavesset
/// CONSTANTS Waves = TRUE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "self"
///           Fixed <- AllFixes
/// INVARIANT ReportTruthful
/// ```
///
/// ```tlacfg DHTDepartureDrain:allnamed
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "none"
///           Fixed <- AllFixes
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:strand
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "peer"
///           Fixed <- AllFixes
/// INVARIANT ReplicaPlacement
/// ```
///
/// ```tlacfg DHTDepartureDrain:allnamedpre
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "none"
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "view", "accept", "deliverer", "namedself"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:strandpre
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "peer"
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "view", "accept", "deliverer", "namedself"}
/// INVARIANT ReplicaPlacement
/// ```
///
/// ```tlacfg DHTDepartureDrain:nonamedself
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "none"
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "view", "accept", "primary", "deliverer"}
/// INVARIANT ReceiverAgrees
/// ```
///
/// ```tlacfg DHTDepartureDrain:report
/// CONSTANTS Waves = FALSE
///           Deadline = TRUE
///           HaltFair = TRUE
///           Flap = "peer"
///           Fixed <- AllFixes
/// INVARIANT ReportTruthful
/// ```
///
/// ```tlacfg DHTDepartureDrain:nodeliverer
/// CONSTANTS Waves = FALSE
///           Deadline = TRUE
///           HaltFair = TRUE
///           Flap = "peer"
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "view", "accept", "primary", "namedself"}
/// INVARIANT ReportTruthful
/// ```
///
/// ```tlacfg DHTDepartureDrain:flapcode
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "self"
///           Fixed <- AllFixes
/// INVARIANT NoLastCopyLoss
/// INVARIANT ReportTruthful
/// INVARIANT ReplicaDegree
/// INVARIANT ReplicaPlacement
/// ```
///
/// ```tlacfg DHTDepartureDrain:flap
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "self"
///           Fixed <- AllFixes
/// INVARIANT ReceiverAgrees
/// ```
///
/// ```tlacfg DHTDepartureDrain:allnamedflap
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Flap = "peer"
///           Fixed <- AllFixes
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:nohaltfair
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = FALSE
///           Flap = "self"
///           Fixed <- AllFixes
/// PROPERTY DrainCompletes
/// ```
package org.pragmatica.dht;
