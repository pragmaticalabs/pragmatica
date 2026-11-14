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
/// What the model leaves out, so a pass says nothing about it: the survivor-side rebalance ([DHTRebalancer#onNodeRemoved]) and
/// anti-entropy (both only ADD copies, so leaving them out can only hide a loss the model reports, never invent one); joins;
/// deletes and versions (one key, one value); the page windows except as the `Deadline` switch; the at-risk events. The plan
/// is one step, while the code reads the ring three times in [DeparturePushRun] `departureTargets`; a send, its receipt and
/// its ack are one step (the receiver judges the page against its state at receipt, the pusher's carried leaving set and view
/// are its state at that step).
///
/// ```tla DHTDepartureDrain:head
/// EXTENDS Naturals, FiniteSets, Sequences
///
/// \* One key on a ring of N nodes; the key's partition walks the ring in the order 1..N (ConsistentHashRing.nodesFor: the
/// \* first rf distinct nodes clockwise). Every ring geometry of one key is reached by choosing, in Init, which nodes drain,
/// \* which stale entries the drain set carries and which nodes hold the key (any non-empty set: under-replicated, strays).
/// \* RF is DHTConfig.replicationFactor; the effective factor is min(RF, ring size) on the node that computes it.
/// \* MaxDrain bounds the co-drainers, MaxStale the stale drain-set entries (named by the leader, never draining; one may
/// \* flap: enter DEPARTING on an observer and recover).
/// \* Waves: drain commands arrive in separate leader pings, so a drainer may plan before a later co-drainer is named.
/// \* FALSE models the drain DhtDeparturePush documents: every co-drainer is named in the ping that commands the first.
/// \* Deadline: a drainer may halt with pages unacknowledged (the grace fork, a window used up: the #3049 legacy budget).
/// \* Fixed: the protections the code carries (AllFixes). A config that drops one models the code without it:
/// \*   drainset  - the leader's drain set exists and reaches nodes (the test's empty-set arm drops it)
/// \*   codrain   - the push excludes the co-drainers it knows (#1818: DHTRebalancer.leavingSet)
/// \*   exhaust   - the push falls back to the whole post-departure set when the exclusion exhausts the ring (#1818)
/// \*   selfcheck - ... and when self is not in the key's current set (a stray copy: no newcomer can be identified)
/// \*   pruned    - ... and when an excluded node already left the ring (#2684 F3)
/// \*   view      - the receiver judges placement in the pusher's view without the pusher's leaving set (#1818 L1)
/// \*   accept    - the receiver accepts a push only from a sender it knows to be departing or that is a current replica
/// CONSTANTS N, RF, MaxDrain, MaxStale, Waves, Deadline, Fixed
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
///     stale,      \* drain-set entries that never drain (fixed in Init)
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
/// vars == <<drainers, stale, drainSet, known, pruned, flapped, store, phase, leaving, rfAt, pages, claimed, strayNack>>
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
///     /\ UNCHANGED <<drainers, stale, pruned, flapped, store, leaving, rfAt, pages, claimed, strayNack>>
///
/// \* A later leader ping reaches n with the current drain set.
/// LearnDrain(n) ==
///     /\ phase[n] # "halted"
///     /\ known[n] # drainSet
///     /\ known' = [known EXCEPT ![n] = drainSet]
///     /\ UNCHANGED <<drainers, stale, drainSet, pruned, flapped, store, phase, leaving, rfAt, pages, claimed, strayNack>>
///
/// Init ==
///     /\ drainers \in {D \in SUBSET Nodes : Cardinality(D) \in 1..MaxDrain}
///     /\ stale \in {S \in SUBSET (Nodes \ drainers) : Cardinality(S) <= MaxStale}
///     /\ drainSet = IF ~Fix("drainset") THEN {} ELSE IF Waves THEN stale ELSE drainers \cup stale
///     /\ known = [n \in Nodes |-> {}]
///     /\ pruned = [n \in Nodes |-> {}]
///     /\ flapped = [n \in Nodes |-> {}]
///     /\ store \in {f \in [Nodes -> BOOLEAN] : \E n \in Nodes : f[n]}
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
///     /\ \A d \in Nodes : WF_vars(Command(d)) /\ WF_vars(Plan(d)) /\ WF_vars(Halt(d))
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
/// \* Every drainer's push resolves and it halts.
/// DrainCompletes == <>(\A d \in drainers : phase[d] = "halted")
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
///           Fixed <- AllFixes
/// INVARIANT NoLastCopyLoss
/// INVARIANT TargetsOutsideDrainSet
/// INVARIANT ReceiverAgrees
/// ```
///
/// ```tlacfg DHTDepartureDrain:live
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           Fixed <- AllFixes
/// PROPERTY DrainCompletes
/// ```
///
/// ```tlacfg DHTDepartureDrain:noexhaust
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           Fixed = {"drainset", "codrain", "selfcheck", "pruned", "view", "accept"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:noselfcheck
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           Fixed = {"drainset", "codrain", "exhaust", "pruned", "view", "accept"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:nocodrain
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           Fixed = {"drainset", "exhaust", "selfcheck", "pruned", "view", "accept"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:emptyset
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           Fixed = {"codrain", "exhaust", "selfcheck", "pruned", "view", "accept"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:noview
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "accept"}
/// INVARIANT ReceiverAgrees
/// ```
///
/// ```tlacfg DHTDepartureDrain:noviewlive
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "accept"}
/// PROPERTY DrainCompletes
/// ```
///
/// ```tlacfg DHTDepartureDrain:nopruned
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "view", "accept"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:nofallback
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           Fixed = {"drainset", "codrain", "view", "accept"}
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:wideaccept
/// CONSTANTS Waves = FALSE
///           Deadline = FALSE
///           Fixed = {"drainset", "codrain", "exhaust", "selfcheck", "pruned", "view"}
/// INVARIANT NoLastCopyLoss
/// INVARIANT TargetsOutsideDrainSet
/// INVARIANT ReceiverAgrees
/// ```
///
/// ```tlacfg DHTDepartureDrain:abandon
/// CONSTANTS Waves = FALSE
///           Deadline = TRUE
///           Fixed <- AllFixes
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:waves
/// CONSTANTS Waves = TRUE
///           Deadline = FALSE
///           Fixed <- AllFixes
/// INVARIANT NoLastCopyLoss
/// ```
///
/// ```tlacfg DHTDepartureDrain:wavesset
/// CONSTANTS Waves = TRUE
///           Deadline = FALSE
///           Fixed <- AllFixes
/// INVARIANT TargetsOutsideDrainSet
/// ```
package org.pragmatica.dht;
