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
/// anti-entropy, joins, deletes and versions (one key, one value), the page windows except as the `Deadline` switch, and the
/// at-risk events. The two left-out copy paths act in opposite directions:
/// - The survivor-side rebalance only ADDS copies: the primary replica pushes the key to its co-replicas. It needs a copy on a
///   final replica to start from, so it can never undo a NoLastCopyLoss or ReplicaPlacement violation. Leaving out a path that
///   only adds copies can only make the model report a loss the system would repair, never hide one; this one cannot even do that.
/// - Anti-entropy REMOVES copies: `purgeStrays` drops every value of a partition a node stopped replicating once the stray
///   horizon has passed ([DHTAntiEntropy] `synchronizePartitions`, [DHTNode] `dropStray`). Leaving it out can HIDE a loss: a
///   copy that NoLastCopyLoss counts may sit on a node that is not a replica in the final ring, where no read finds it and
///   where a purge may later drop it. ReplicaPlacement is the invariant that does not count such a copy.
///
/// The plan is one step, while the code reads the ring three times in [DeparturePushRun] `departureTargets` (a torn read can
/// lower the replica degree but cannot empty the target set: the delta branch keeps at least one target). A send, its receipt
/// and its ack are one step: the receiver judges the page against its state at receipt, and the pusher's carried leaving set
/// and view are the pusher's state at that step. `Deadline = FALSE` assumes every page settles inside its window; halting
/// with pages unacknowledged is the code's normal deadline path ([DeparturePushRun] `execute` stops at `backstopDeadline`,
/// `shouldStop`/`pastDeadline` stop pages at `baseDeadline`), so the `code` pass holds only for drains whose pages all settle
/// in time. `Waves = FALSE` assumes the ping that commands the first drainer names every co-drainer (DhtDeparturePush reads
/// the drain set at push time); two operator drains issued minutes apart are the `waves` case, which is red.
///
/// Bounds, with the rest of the run record (TLC, `code` config unless named; "distinct" = distinct states). The current figures
/// are in the `models.tsv` rows and the PR; the 2026-11-14 figures below were taken before `held0`, ReplicaPlacement and
/// ReplicaDegree were added and before the survivor constraint was dropped from `Init`:
/// - Exhaustive, TLC reported completion with an empty queue and no violation: N=4 RF=2 MaxDrain=1 MaxStale=1 (5,464,960);
///   N=4 RF=2 MaxDrain=2 MaxStale=0 (863,800); N=4 RF=3 MaxDrain=1 MaxStale=1 (6,230,502); `live` at N=3 RF=2 (132,546).
/// - Not proven, stopped after 25 minutes with the queue non-empty and no violation seen: N=4 RF=2 MaxDrain=2 MaxStale=1 (92,952,889
///   distinct, 10,883,308 queued); N=4 RF=3 MaxDrain=2 MaxStale=1 (104,545,313; 25,390,445 queued); N=5 RF=3 MaxDrain=2 MaxStale=1
///   (135,641,865; 80,645,238 queued). The constants written in the `common` config are the last of these.
/// - Red in `code` itself, each with its own config and an open issue: `allnamed` (NoLastCopyLoss at N=3 RF=2: two drainers
///   and a stale entry name every other node, the drainer finds no target and halts with the last copy, #3208) and `strand`
///   (ReplicaPlacement at N=4 RF=2 MaxDrain=1: the only copy lands on a node outside the final replica set, #3209).
///
/// What the passing `code` row does NOT exercise (its invariants are vacuous or its paths unreached there):
/// - TargetsOutsideDrainSet is a tautology without `Waves`: the drain set is constant, `known[d]` equals it at `Command`, and
///   every target lies outside the exclusion, which contains it. It has content only in `wavesset`.
/// - `Retarget` never fires in `code` or `live`: targets avoid drainers and stale entries, `pruned` grows only by those, so a
///   target never leaves the pusher's ring. `claimed` and `rfAt` are exercised only by the fault configs. The code's retarget
///   path is not verified by the `code` pass.
/// - The `exhaust` fallback is unreachable at N=4 RF=2 with MaxDrain + MaxStale <= 2 (two ring members always remain outside
///   the exclusion); it is reached at N=3 RF=2 and N=4 RF=3.
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
/// \* still leaves a survivor: N=3 RF=2 with two drainers and one stale entry loses the last copy in the code (#3208, `allnamed`).
/// \* Waves: drain commands arrive in separate leader pings, so a drainer may plan before a later co-drainer is named.
/// \* FALSE models the drain DhtDeparturePush documents: every co-drainer is named in the ping that commands the first. Two
/// \* operator drains issued minutes apart break that assumption (`waves` is red).
/// \* Deadline: a drainer may halt with pages unacknowledged. This is the code's normal deadline path, not a legacy one
/// \* (execute's backstopDeadline, shouldStop/pastDeadline at baseDeadline, the grace fork); FALSE assumes every page settles
/// \* inside its window.
/// \* HaltFair: Halt is weakly fair. FALSE is the negative control of DrainCompletes (`nohaltfair`).
/// \* Fixed: the protections the code carries (AllFixes). A config that drops one models the code without it:
/// \*   drainset  - the leader's drain set exists and reaches nodes (the test's empty-set arm drops it)
/// \*   codrain   - the push excludes the co-drainers it knows (#1818: DHTRebalancer.leavingSet)
/// \*   exhaust   - the push falls back to the whole post-departure set when the exclusion exhausts the ring (#1818)
/// \*   selfcheck - ... and when self is not in the key's current set (a stray copy: no newcomer can be identified)
/// \*   pruned    - ... and when an excluded node already left the ring (#2684 F3)
/// \*   view      - the receiver judges placement in the pusher's view without the pusher's leaving set (#1818 L1)
/// \*   accept    - the receiver accepts a push only from a sender it knows to be departing or that is a current replica
/// CONSTANTS N, RF, MaxDrain, MaxStale, Waves, Deadline, HaltFair, Fixed
///
/// AllFixes == {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "view", "accept"}
/// ASSUME Fixed \subseteq AllFixes
/// Fix(f) == f \in Fixed
///
/// Nodes == 1..N
/// Order == [i \in 1..N |-> i]
/// Min(a, b) == IF a < b THEN a ELSE b
///
/// VARIABLES
///     drainers,   \* the nodes that drain (fixed in Init)
///     held0,      \* held0[n]: n held the key in Init (history, read only by ReplicaPlacement and ReplicaDegree)
///     stale,     \* drain-set entries that never drain (fixed in Init)
///     drainSet,   \* the leader's drain set (ClusterSyncCollector.commandedDrainNodes as the leader sends it)
///     known,      \* known[n]: the drain set as node n last received it
///     pruned,     \* pruned[n]: nodes removed from n's ring (DEPARTING edge or NodeRemoved); n's ring is Nodes \ pruned[n]
///     flapped,    \* flapped[n]: stale nodes n has already seen enter DEPARTING (one flap each)
///     store,      \* store[n]: n holds the key
///     phase,      \* "member" | "draining" (commanded) | "pushing" (planned) | "halted"
///     leaving,    \* leaving[d]: DeparturePushRun.leaving (self, the co-drainers it knew, targets that left the ring)
///     rfAt,       \* rfAt[d]: the replication factor the push was built with
///     pages,      \* registered, unacknowledged departure pages [from, to]
///     claimed,    \* claimed[d]: DepartureKey.plannedTargets
///     strayNack   \* a receiver refused, as a stray, a page from a sender it accepted (history)
///
/// vars == <<drainers, held0, stale, drainSet, known, pruned, flapped, store, phase, leaving, rfAt, pages, claimed, strayNack>>
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
/// \* DeparturePushRun.departureTargets: newSet is the post-departure set; only its newcomers are targeted when self is in the
/// \* current set, the exclusion leaves a full newSet and every excluded node is still in the ring; otherwise all of newSet.
/// DepartureTargets(d, excl, rf) ==
///     LET ring   == Ring(d)
///         newSet == Walk(ring \ excl, rf)
///         cur    == Walk(ring, rf)
///         delta  == /\ (Fix("selfcheck") => d \in cur)
///                   /\ (Fix("exhaust") => Cardinality(newSet) >= rf)
///                   /\ (Fix("pruned") => excl \subseteq ring)
///     IN IF delta THEN newSet \ (cur \ excl) ELSE newSet
///
/// \* DHTAntiEntropy.acceptDeparturePush: the sender is departing in t's view, or a current replica of the key in t's ring.
/// Accepted(t, d) == ~Fix("accept") \/ Departing(t, d) \/ d \in Cur(t)
/// \* DHTAntiEntropy.replicaHereAroundDeparture: t is a current replica, or within rf of t's ring restricted to the pusher's
/// \* view (its ring at the send) without the pusher, its carried leaving set and every node t knows to be departing.
/// PostView(t, d) ==
///     Walk({c \in Ring(t) : /\ (Fix("view") => c \in Ring(d) /\ c \notin leaving[d])
///                           /\ c # d
///                           /\ ~Departing(t, c)},
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
///     /\ UNCHANGED <<drainers, held0, stale, pruned, flapped, store, leaving, rfAt, pages, claimed, strayNack>>
///
/// \* A later leader ping reaches n with the current drain set.
/// LearnDrain(n) ==
///     /\ phase[n] # "halted"
///     /\ known[n] # drainSet
///     /\ known' = [known EXCEPT ![n] = drainSet]
///     /\ UNCHANGED <<drainers, held0, stale, drainSet, pruned, flapped, store, phase, leaving, rfAt, pages, claimed, strayNack>>
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
///     /\ leaving = [n \in Nodes |-> {}]
///     /\ rfAt = [n \in Nodes |-> 0]
///     /\ pages = {}
///     /\ claimed = [n \in Nodes |-> {}]
///     /\ strayNack = FALSE
///
/// \* Once every drainer has halted no copy can move or vanish (no pages remain, survivors never halt): the run is over. Every
/// \* config stops exploring there (CONSTRAINT Active in `common`); the state itself is still checked.
/// Active == \E d \in drainers : phase[d] # "halted"
///
/// Next ==
///     \/ \E d \in Nodes : Command(d) \/ Plan(d) \/ Halt(d)
///     \/ \E n \in Nodes : LearnDrain(n)
///     \/ \E o, x \in Nodes : DepartingEdge(o, x) \/ Recover(o, x) \/ Removed(o, x)
///     \/ \E d, t \in Nodes : Deliver(d, t) \/ Retarget(d, t)
///
/// \* A commanded drainer plans and halts once its push resolves; every live node eventually receives the drain set and sees
/// \* every drainer enter DEPARTING and, once halted, removed; a page that can be delivered or retargeted eventually is. A
/// \* stale entry's flap is not fair: it may never happen.
/// Fairness ==
///     /\ \A d \in Nodes : WF_vars(Command(d)) /\ WF_vars(Plan(d)) /\ WF_vars(HaltFair /\ Halt(d))
///     /\ \A n \in Nodes : WF_vars(LearnDrain(n))
///     /\ \A o, x \in Nodes : WF_vars(x \in drainers /\ DepartingEdge(o, x)) /\ WF_vars(Removed(o, x))
///     /\ \A d, t \in Nodes : WF_vars(Deliver(d, t)) /\ WF_vars(Retarget(d, t))
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
/// \* Drain-set membership is consistent with placement: a departure page never targets a node the leader is draining.
/// TargetsOutsideDrainSet == \A p \in pages : p.to \notin drainSet
///
/// \* #1818 L1: a receiver that accepts the sender never refuses the page as a stray, so no page is nacked forever.
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
/// \* as the ring outside the drain set can carry (the code never targets a named stale entry, so those do not count).
/// ReplicaDegree ==
///     (AllHalted /\ {n \in Nodes : held0[n]} = Walk(Nodes, RF))
///         => Cardinality({n \in Nodes : store[n]}) >= Min(RF, Cardinality(Nodes \ (drainers \cup stale)))
///
/// \* Every drainer's push resolves and it halts.
/// DrainCompletes == <>AllHalted
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
///           Fixed <- AllFixes
/// INVARIANT NoLastCopyLoss
/// INVARIANT TargetsOutsideDrainSet
/// INVARIANT ReceiverAgrees
/// INVARIANT ReplicaDegree
/// ```
///
/// ```tlacfg DHTDepartureDrain:live
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed <- AllFixes
/// PROPERTY DrainCompletes
/// ```
///
/// ```tlacfg DHTDepartureDrain:noexhaust
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed = {"drainset", "codrain", "selfcheck", "pruned", "view", "accept"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:noselfcheck
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed = {"drainset", "codrain", "exhaust", "pruned", "view", "accept"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:nocodrain
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed = {"drainset", "exhaust", "selfcheck", "pruned", "view", "accept"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:emptyset
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed = {"codrain", "exhaust", "selfcheck", "pruned", "view", "accept"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:noview
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "accept"}
/// INVARIANT ReceiverAgrees
/// ```
///
/// ```tlacfg DHTDepartureDrain:nopruned
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "view", "accept"}
/// INVARIANT ReplicaDegree
/// ```
///
/// ```tlacfg DHTDepartureDrain:nofallback
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed = {"drainset", "codrain", "view", "accept"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:wideaccept
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "view"}
/// INVARIANT NoLastCopyLoss
/// INVARIANT TargetsOutsideDrainSet
/// INVARIANT ReceiverAgrees
/// ```
///
/// ```tlacfg DHTDepartureDrain:abandon
/// CONSTANTS Waves = FALSE
///           Deadline = TRUE
///           HaltFair = TRUE
///           Fixed <- AllFixes
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:waves
/// CONSTANTS Waves = TRUE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed <- AllFixes
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:wavesset
/// CONSTANTS Waves = TRUE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed <- AllFixes
/// INVARIANT TargetsOutsideDrainSet
/// ```
///
/// ```tlacfg DHTDepartureDrain:allnamed
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed <- AllFixes
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:strand
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = TRUE
///           Fixed <- AllFixes
/// INVARIANT ReplicaPlacement
/// ```
///
/// ```tlacfg DHTDepartureDrain:nohaltfair
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           HaltFair = FALSE
///           Fixed <- AllFixes
/// PROPERTY DrainCompletes
/// ```
package org.pragmatica.dht;
