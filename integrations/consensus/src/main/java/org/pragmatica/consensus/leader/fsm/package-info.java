// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
/// Leader-election state machine ([LeaderElectionState], [LeaderElectionContext], [LeaderElectionFsm]).
///
/// The TLA+ model `LeaderAdmission` (#3585) of how a node admits a committed leader and holds it to its stored view
/// (the I1/I2/I3 header of [LeaderElectionState], #3092, #3414, #3581) lives in doc-comment fragments: this skeleton
/// carries the constants, the state, the environment (sequenced transport views, fresh or stale; view-free removals;
/// `LeaderKey` commits and the pushes `LeaderManager` dispatches after them), `Init`, `Next`, fairness, the invariants
/// and the property; each code action sits on the method that implements it.
/// `tools/tla/check.sh LeaderAdmission <variant> integrations/consensus/src/main/java` extracts and checks one config;
/// `tools/tla/run.sh` runs every config with its expected verdict (`tools/tla/models.tsv`). Results are bounds, not
/// proofs: a passing config says no violation exists within its constants.
///
/// Abstractions (what the model leaves out, so a result says nothing about it): eligibility (every candidate is
/// eligible, this node is none of them); the voting states' KV pull and proposals (a voting node learns a leader only
/// from a pushed commit or, in local mode, its own tick); the local view path's election (`LeaderManager`
/// `electLocallyIfPossible`: I3 is claimed from ticks alone); `Passive` re-entry; and, with `Interleave` = TRUE, every
/// handler but the push's admission stays one atomic step.
///
/// ```tla LeaderAdmission:head
/// EXTENDS Naturals, FiniteSets
///
/// \* One node's leader-election FSM against its environment. Role "passive": a worker, Dormant then Passive. Role
/// \* "voting": a core node in the voting states (QuorumWaiting/AwaitingKvSync = "waiting", Electing/ReElecting =
/// \* "electing", Led = "led"). Nodes: the NodeCount eligible candidates (this node is not one). Local: local mode (no
/// \* commits; a tick elects). Interleave: the push's admission check and its write are two steps a concurrent view
/// \* write may run between (Fsm.dispatch runs on the caller thread; FALSE = every dispatch is one atomic step).
/// \* MaxSeq bounds view sequences, MaxCommits the LeaderKey commits, EnvSteps the view writes and removals. Env: the
/// \* environment events enabled in a run ("view", "stale", "remove", "commit").
/// \* Fixed: the fixes the modelled code carries (the code as it stands carries AllFixes). A config that drops one
/// \* models the earlier version and must fail:
/// \*   entrypull     - Passive's entry pulls the committed LeaderKey (#3414: a worker entering Passive after the push
/// \*                   passed in Dormant never learned the leader)
/// \*   pushinview    - Passive's push passes the in-view admission (78e44f01a8; before, FIX 1 applied to an observer
/// \*                   adopted a push into an empty or leader-less view)
/// \*   pullinview    - Passive's pull passes the same admission (v3581 A3: the pull re-adopted a leader the I2 site
/// \*                   dropped)
/// \*   repull        - every sequenced Passive view write re-runs the pull (78e44f01a8: a refused push heals)
/// \*   removalnopull - a view-free removal re-pulls nothing (5f412d7cdb: a re-pull re-admitted the held leader the
/// \*                   removal named failed where the storage kept it)
/// \* Mutant: vacuity controls, edits no version of the code carried; each must break the invariant it names:
/// \*   dropempty (I1: an empty view is not stored), nostalegate (I1: a stale view is applied), nohold (I2: the I2
/// \*   site holds nothing), seqgatelocal (I3: a local commit takes the cluster sequence gate)
/// CONSTANTS NodeCount, Role, Local, Interleave, MaxSeq, MaxCommits, EnvSteps, Env, Fixed, Mutant
///
/// Nodes == 1..NodeCount
/// AllFixes == {"entrypull", "pushinview", "pullinview", "repull", "removalnopull"}
/// Fix(f) == f \in Fixed
/// Mut(m) == m \in Mutant
/// ASSUME Fixed \subseteq AllFixes
/// ASSUME Mutant \subseteq {"dropempty", "nostalegate", "nohold", "seqgatelocal"}
///
/// None == 0
/// NoRec == <<None, 0>>
/// Max(a, b) == IF a > b THEN a ELSE b
/// MinOf(S) == CHOOSE x \in S : \A y \in S : x <= y
///
/// VARIABLES
///     st,       \* the FSM state: "dormant" | "passive" | "waiting" | "electing" | "led"
///     leader,   \* the leader the state holds (Passive's currentLeader, Led's leader), None if none
///     view,     \* the stored eligible view (currentTopology)
///     vseq,     \* the stored view's sequence
///     kv,       \* the committed LeaderKey record <<leader, viewSequence>>, NoRec before the first commit
///     pend,     \* pushes in flight: committed records LeaderManager has yet to dispatch, delivered in any order
///     adSeq,    \* adoptedViewSequence
///     lastAd,   \* lastAdoptedLeader
///     obsSeq,   \* committedViewSequence (LeaderManager observes a push's sequence before dispatching it)
///     base,     \* the electing state's entryBaseline
///     adm,      \* Interleave: the push between its admission check and its write, NoRec if none
///     env,      \* environment steps left
///     hi,       \* ghost: <<view, sequence>> of the newest sequenced view delivered (I1)
///     removed,  \* ghost: the nodes removed view-free since that view (I1)
///     named,    \* ghost: the node the step's view-free removal named failed, None after any other step (I2)
///     suspect,  \* ghost: a held leader a removal named failed, until an applied view or a newer commit (liveness)
///     cluster   \* ghost: Led's leader came from a cluster commit and no view checked it since (FIX 1)
///
/// vars == <<st, leader, view, vseq, kv, pend, adSeq, lastAd, obsSeq, base, adm, env, hi, removed, named, suspect,
///           cluster>>
///
/// \* LeaderElectionContext.applyTopology (I1): a sequenced view replaces the stored one, an empty one included, iff
/// \* its sequence is above the stored one (#2971), check and write in one getAndUpdate.
/// Applies(S, q) == /\ (q > vseq \/ Mut("nostalegate"))
///                  /\ (S # {} \/ ~Mut("dropempty"))
/// \* LeaderElectionContext.removeFromView: view-free, applied iff the node is present and is not the last node.
/// Removes(n) == n \in view /\ view # {n}
///
/// \* The I1 ghosts follow every sequenced delivery, applied or not; a view-free removal adds to removed.
/// HiNext(S, q) == IF q > hi[2] THEN <<S, q>> ELSE hi
/// RemovedNext(q) == IF q > hi[2] THEN {} ELSE removed
///
/// \* holdLeaderToView then dropObservedLeader / loseLeader (dispatched synchronously, Fsm.dispatch): the held leader
/// \* is lost iff it is absent from the stored view or named failed by the removal.
/// Lost(v, l, nf) == l # None /\ (l \notin v \/ l = nf) /\ ~Mut("nohold")
///
/// \* observeCommittedLeader's gate (#3581): a real commit not older than the adopted one (an equal one only for the
/// \* leader adopted at it), and the leader in the stored view v (admittedToView), on the push and the pull alike.
/// Admits(r, path, v) == /\ r[2] > 0
///                       /\ r[2] >= adSeq
///                       /\ (r[2] = adSeq => lastAd = r[1])
///                       /\ \/ r[1] \in v
///                          \/ path = "push" /\ ~Fix("pushinview")
///                          \/ path = "pull" /\ ~Fix("pullinview")
///
/// \* LeaderManager.onLeaderCommitted: observeViewSequence, then the dispatch; the record leaves the in-flight set.
/// PushStep(r) == /\ r \in pend
///                /\ pend' = pend \ {r}
///                /\ obsSeq' = Max(obsSeq, r[2])
///                /\ named' = None
///
/// \* observeCommittedLeader's write: setAdoptedViewSequence, then setCurrentLeader (which records lastAdoptedLeader).
/// AdoptP(r) == leader' = r[1] /\ adSeq' = r[2] /\ lastAd' = r[1]
///
/// \* -- Environment: transport sequences and LeaderKey commits from other components --
/// Ev == env > 0 /\ env' = env - 1
/// \* A fresh view carries the next sequence; with "stale" an older or equal one may be delivered late (#2971).
/// SeqOk(q) == IF "stale" \in Env THEN q \in 1..MaxSeq ELSE q = hi[2] + 1 /\ q <= MaxSeq
///
/// \* A consensus decision commits LeaderKey (any candidate, in this node's view or not), then LeaderManager's
/// \* listener pushes it (onLeaderCommitted).
/// Commit(l) == /\ "commit" \in Env /\ ~Local
///              /\ kv[2] < MaxCommits
///              /\ kv' = <<l, kv[2] + 1>>
///              /\ pend' = pend \cup {kv'}
///              /\ named' = None
///              /\ UNCHANGED <<st, leader, view, vseq, adSeq, lastAd, obsSeq, base, adm, env, hi, removed, suspect,
///                             cluster>>
/// ```
///
/// ```tla LeaderAdmission:tail
/// Init == /\ st = IF Role = "passive" THEN "dormant" ELSE IF Local THEN "electing" ELSE "waiting"
///         /\ leader = None
///         /\ view = {}
///         /\ vseq = 0
///         /\ kv = NoRec
///         /\ pend = {}
///         /\ adSeq = 0
///         /\ lastAd = None
///         /\ obsSeq = 0
///         /\ base = 0
///         /\ adm = NoRec
///         /\ env = EnvSteps
///         /\ hi = <<{}, 0>>
///         /\ removed = {}
///         /\ named = None
///         /\ suspect = None
///         /\ cluster = FALSE
///
/// EnvNext == \E l \in Nodes : Commit(l)
/// ViewNext == \/ \E S \in SUBSET Nodes, q \in 1..MaxSeq : P_View(S, q) \/ V_View(S, q)
///             \/ \E n \in Nodes : P_Remove(n) \/ V_Remove(n)
/// Push(r) == D_Push(r) \/ P_Push(r) \/ P_Check(r) \/ V_PushWaiting(r) \/ V_PushElecting(r) \/ V_PushLed(r)
/// SysNext == \/ P_Enter \/ P_Write \/ L_Tick
///            \/ \E r \in pend : \/ D_Push(r) \/ P_Push(r) \/ P_Check(r)
///                             \/ V_PushWaiting(r) \/ V_PushElecting(r) \/ V_PushLed(r)
/// Next == EnvNext \/ ViewNext \/ SysNext
///
/// \* PassiveDirectory reaches the worker, every push is dispatched, a started admission writes, ticks keep coming.
/// Fairness == /\ WF_vars(P_Enter) /\ WF_vars(P_Write) /\ WF_vars(L_Tick)
///             /\ \A l \in Nodes, s \in 1..MaxCommits : WF_vars(Push(<<l, s>>))
///
/// Spec == Init /\ [][Next]_vars /\ Fairness
///
/// \* -- Invariants (the LeaderElectionState header) --
/// \* I1: the stored view is the newest sequenced view as delivered, an empty one included, less the nodes removed
/// \* view-free since; a stale view writes nothing.
/// I1 == view = hi[1] \ removed
///
/// \* I2, Passive: Passive holds a leader only inside its stored view (no admission in flight) and never the node the
/// \* step's removal named failed.
/// PassiveInView == st = "passive" /\ leader # None /\ adm = NoRec => leader \in view
/// \* I2, every state: the held leader is not the node the step's view-free removal named failed.
/// NotNamedFailed == leader # None => leader # named
/// \* I2, voting: Led's leader is in the stored view unless a cluster commit adopted it and no view checked it since
/// \* (FIX 1: a consensus decision the node took part in is adopted when absent; the next view checks it).
/// VotingInView == st = "led" /\ ~cluster => leader \in view
///
/// Safety == I1 /\ PassiveInView /\ NotNamedFailed /\ VotingInView
///
/// \* -- Properties --
/// \* A Passive node whose stored view holds the committed leader (and no removal named it failed since) eventually
/// \* adopts it: a refused or lagging push heals on the next view write, a push missed in Dormant on entry.
/// PassiveLearns == <>[](st = "passive" /\ kv # NoRec /\ kv[1] \in view /\ kv[1] # suspect)
///                      => <>[](leader = kv[1])
/// \* I3: a leaderless electing state in local mode makes progress on its own ticks: a stable non-empty view ends Led.
/// LocalProgress == <>[](view # {}) => <>[](st = "led")
/// ```
///
/// ```tlacfg LeaderAdmission:common
/// CONSTANTS NodeCount = 3
/// SPECIFICATION Spec
/// ```
///
/// ```tlacfg LeaderAdmission:pushfirst
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 4
///           MaxCommits = 3
///           EnvSteps = 4
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// PROPERTY PassiveLearns
/// ```
///
/// ```tlacfg LeaderAdmission:removal
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 4
///           MaxCommits = 3
///           EnvSteps = 4
///           Env = {"view", "remove", "commit"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// PROPERTY PassiveLearns
/// ```
///
/// ```tlacfg LeaderAdmission:stale
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 4
///           MaxCommits = 3
///           EnvSteps = 4
///           Env = {"view", "stale", "commit"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// PROPERTY PassiveLearns
/// ```
///
/// ```tlacfg LeaderAdmission:passive
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 4
///           MaxCommits = 3
///           EnvSteps = 4
///           Env = {"view", "stale", "remove", "commit"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// PROPERTY PassiveLearns
/// ```
///
/// ```tlacfg LeaderAdmission:voting
/// CONSTANTS Role = "voting"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 4
///           MaxCommits = 3
///           EnvSteps = 4
///           Env = {"view", "stale", "remove", "commit"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// ```
///
/// ```tlacfg LeaderAdmission:local
/// CONSTANTS Role = "voting"
///           Local = TRUE
///           Interleave = FALSE
///           MaxSeq = 4
///           MaxCommits = 3
///           EnvSteps = 4
///           Env = {"view", "stale", "remove"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// PROPERTY LocalProgress
/// ```
///
/// ```tlacfg LeaderAdmission:noentrypull
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"pushinview", "pullinview", "repull", "removalnopull"}
/// PROPERTY PassiveLearns
/// ```
///
/// ```tlacfg LeaderAdmission:pushnoview
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"entrypull", "pullinview", "repull", "removalnopull"}
/// INVARIANT PassiveInView
/// ```
///
/// ```tlacfg LeaderAdmission:pullnoview
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"entrypull", "pushinview", "repull", "removalnopull"}
/// INVARIANT PassiveInView
/// ```
///
/// ```tlacfg LeaderAdmission:norepull
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"entrypull", "pushinview", "pullinview", "removalnopull"}
/// PROPERTY PassiveLearns
/// ```
///
/// ```tlacfg LeaderAdmission:removalpull
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "remove", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"entrypull", "pushinview", "pullinview", "repull"}
/// INVARIANT NotNamedFailed
/// ```
///
/// ```tlacfg LeaderAdmission:mutdropempty
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {"dropempty"}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT I1
/// ```
///
/// ```tlacfg LeaderAdmission:mutnostale
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "stale", "commit"}
///           Mutant = {"nostalegate"}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT I1
/// ```
///
/// ```tlacfg LeaderAdmission:mutnohold
/// CONSTANTS Role = "voting"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {"nohold"}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT VotingInView
/// ```
///
/// ```tlacfg LeaderAdmission:mutnoholdp
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {"nohold"}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT PassiveInView
/// ```
///
/// ```tlacfg LeaderAdmission:mutnoholdr
/// CONSTANTS Role = "voting"
///           Local = FALSE
///           Interleave = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "remove", "commit"}
///           Mutant = {"nohold"}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT NotNamedFailed
/// ```
///
/// ```tlacfg LeaderAdmission:mutseqgate
/// CONSTANTS Role = "voting"
///           Local = TRUE
///           Interleave = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "stale", "remove"}
///           Mutant = {"seqgatelocal"}
/// CONSTANT Fixed <- AllFixes
/// PROPERTY LocalProgress
/// ```
///
/// ```tlacfg LeaderAdmission:interleave
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           Interleave = TRUE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT PassiveInView
/// ```
package org.pragmatica.consensus.leader.fsm;
