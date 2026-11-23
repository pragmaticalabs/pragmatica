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
/// eligible, this node is none of them); the electing states' KV pull and proposals (an electing node learns a
/// leader only from a pushed commit or, in local mode, its own tick; the waiting states' KV pulls, at entry, on the
/// poll and at the grace timeout, are the one unconditional adoption `V_Grace` models, with the local KV replica equal
/// to the committed record); the grace timer's re-arm while KV sync is pending (the timeout may fire at any time, or
/// never); the pre-vote (consensus mode's `enableLeaderPreVote`: `loseLeader` keeps `Led` with an out-of-view leader
/// while the electorate votes, and the model drops that leader at once, so `VotingInView` is claimed for pre-vote
/// disabled only; modelling it is #3589); the local view path's election (`LeaderManager`
/// `electLocallyIfPossible`: I3 is claimed from ticks alone); `QuorumLost` (its entry clears the
/// leader); `Passive` re-entry; the lease trip (`LeaderSilent`, `LeaderElectionState` LES:574 -> 631-649 ->
/// `loseLeader` LES:766: `Led` -> `ReElecting` for a leader that is still in the view; it only drops a held leader, as
/// the model's out-of-view loss already does, and `ReElecting` is a state the model enters, so it carries no leader
/// into a state the model checks); `QuorumWaiting` -> `Dormant` (`QuorumDisappeared`, LES:203) and `Dormant` ->
/// `QuorumWaiting` (`QuorumEstablished`, LES:116) (neither state holds a leader, so no leader crosses either edge);
/// the `LeaderManager` `reconnectPeer` view write (`LeaderManager` LM:633, `context.applyTopology` with no dispatch,
/// taken only when `restoresTheView` (LM:639-645) is false, i.e. the reconnect's view is content-equal to the stored
/// one: it moves only the sequence fence, never the content, so no I1/I2 decision changes; the only reachable
/// difference is a `Led` holding a FIX-1 out-of-view leader (`cluster` mode) across the sequence bump, which is benign
/// for `VotingInView`; a view whose content differs is dispatched to the FSM and modelled as `V_View`); and,
/// without the fix `serial`, every handler but the push's admission stays one atomic step.
///
/// ```tla LeaderAdmission:head
/// EXTENDS Naturals, FiniteSets
///
/// \* One node's leader-election FSM against its environment. Role "passive": a worker, Dormant then Passive. Role
/// \* "voting": a core node in the voting states (QuorumWaiting/AwaitingKvSync = "waiting", Electing/ReElecting =
/// \* "electing", Led = "led"). Nodes: the NodeCount eligible candidates (this node is not one). Local: local mode (no
/// \* commits; a tick elects). MaxSeq bounds view sequences, MaxCommits the LeaderKey commits, EnvSteps the view writes
/// \* and removals. Env: the environment events enabled in a run ("view", "stale", "remove", "commit", "demote": a
/// \* PassiveDirectory reaching a voting state, and "readmit": VoterReadmitted reaching Passive).
/// \* Fixed: the fixes the modelled code carries. AllFixes is the code as it stands (release-1.0.0-rc6, with #3581,
/// \* #3605 and #3588 finding A). A config that drops one models the code without it and must fail:
/// \*   serial        - Passive's push admission check and its write are one step against the view (#3587, know
/// \*                   45a9e2fdd9; fixed by #3605: LeaderManager runs observeViewSequence and the push's dispatch in
/// \*                   one task of the node's serial executor, which every view write also enters; before it,
/// \*                   Fsm.dispatch ran on the caller thread and a view write on another thread could run between;
/// \*                   configs `interleave`, `interleavelearns`, `interleavemono` drop it: the known-bad of #3587)
/// \*   entryhold     - Passive's entry holds an inherited leader to the stored view, whatever the predecessor (#3581,
/// \*                   dropLeaderOutOfViewOnEntry: without it a Led holding a FIX 1 leader absent from the view
/// \*                   enters Passive still holding it)
/// \*   entrypull     - Passive's entry pulls the committed LeaderKey (#3414: a worker entering Passive after the push
/// \*                   passed in Dormant never learned the leader)
/// \*   pushinview    - Passive's push passes the in-view admission (78e44f01a8; before, FIX 1 applied to an observer
/// \*                   adopted a push into an empty or leader-less view)
/// \*   pullinview    - Passive's pull passes the same admission (v3581 A3: the pull re-adopted a leader the I2 site
/// \*                   dropped)
/// \*   repull        - every sequenced Passive view write re-runs the pull (78e44f01a8: a refused push heals)
/// \*   removalnopull - a view-free removal re-pulls nothing (5f412d7cdb: a re-pull re-admitted the held leader the
/// \*                   removal named failed where the storage kept it)
/// \*   adseq         - every voting adoption skips a commit older than the adopted one and records the sequence
/// \*                   monotonically (#3588 finding A, know 900c63b485: rc6's AwaitingKvSync re-adopted the held
/// \*                   leader at the observed sequence, below the one a passive pull had adopted, and QuorumWaiting
/// \*                   adopted an older push outright; configs `readmitnoadseq`, `readmitstale` drop it)
/// \* Mutant: vacuity controls, edits no version of the code carried; each must break the invariant it names:
/// \*   dropempty (I1: an empty view is not stored), nostalegate (I1: a stale view is applied), nohold (I2: the I2
/// \*   site holds nothing), seqgatelocal (I3: a local commit takes the cluster sequence gate)
/// CONSTANTS NodeCount, Role, Local, MaxSeq, MaxCommits, EnvSteps, Env, Fixed, Mutant
///
/// Nodes == 1..NodeCount
/// AllFixes == {"serial", "entryhold", "entrypull", "pushinview", "pullinview", "repull", "removalnopull", "adseq"}
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
///     adm,      \* without "serial": the push between its admission check and its write, NoRec if none
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
/// \* The I2 site: holdLeaderToView after a view write (storeView, storeRemoval), then dropObservedLeader / loseLeader
/// \* (dispatched synchronously, Fsm.dispatch); Passive's entry (dropLeaderOutOfViewOnEntry) and Led's local entry
/// \* (leftTheViewOnLocalEntry, a follow-up LeaderOutOfView) apply the same predicate without a view write: the held
/// \* leader is lost iff it is absent from the stored view or named failed by the removal.
/// Lost(v, l, nf) == l # None /\ (l \notin v \/ l = nf) /\ ~Mut("nohold")
///
/// \* isOlderThanAdopted, the one staleness rule (Passive's since #3581, every voting adoption's with "adseq", #3588
/// \* finding A): a commit with a real sequence below the adopted one, or equal to it for a leader other than the one
/// \* adopted at it. A sequence-less commit (the waiting states' KV pull, V_Grace) is never older.
/// OlderThanAdopted(r) == r[2] > 0 /\ (r[2] < adSeq \/ (r[2] = adSeq /\ lastAd # r[1]))
///
/// \* skippedAsOlderThanAdopted, at a voting adoption (adoptLeaderUnconditionally, adoptLeaderIfPostElectionCommit):
/// \* with "adseq" an older commit is skipped; without it (rc6) the voting states had no staleness rule.
/// VotingSkips(r) == Fix("adseq") /\ OlderThanAdopted(r)
///
/// \* recordAdoptedSequence for a commit at sequence s with the observed committed sequence o: with "adseq" the largest
/// \* of the adopted sequence, s and o, so adSeq never moves down; rc6 recorded the larger of s and o.
/// RecordAd(s, o) == IF Fix("adseq") THEN Max(adSeq, Max(s, o)) ELSE Max(s, o)
///
/// \* observeCommittedLeader's gate (#3581): a real commit not older than the adopted one (isOlderThanAdopted), and
/// \* the leader in the stored view v (admittedToView), on the push and the pull alike.
/// Admits(r, path, v) == /\ r[2] > 0
///                       /\ ~OlderThanAdopted(r)
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
/// SysNext == \/ P_Enter \/ P_Write \/ L_Tick \/ V_Grace \/ P_Readmit
///            \/ \E r \in pend : \/ D_Push(r) \/ P_Push(r) \/ P_Check(r)
///                             \/ V_PushWaiting(r) \/ V_PushElecting(r) \/ V_PushLed(r)
/// Next == EnvNext \/ ViewNext \/ SysNext
///
/// \* PassiveDirectory reaches the worker (with "demote", a voting state too), every push is dispatched, a started
/// \* admission writes, ticks keep coming.
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
/// \* (FIX 1: a consensus decision the node took part in is adopted when absent; the next view checks it). Pre-vote
/// \* disabled: with the pre-vote Led keeps an out-of-view leader while the electorate votes (an abstraction, above).
/// VotingInView == st = "led" /\ ~cluster => leader \in view
///
/// Safety == I1 /\ PassiveInView /\ NotNamedFailed /\ VotingInView
///
/// \* -- Properties --
/// \* A Passive node whose stored view holds the committed leader (and no removal named it failed since) eventually
/// \* adopts it: a refused or lagging push heals on the next view write, a push missed in Dormant on entry.
/// PassiveLearns == <>[](st = "passive" /\ kv # NoRec /\ kv[1] \in view /\ kv[1] # suspect)
///                      => <>[](leader = kv[1])
/// \* adoptedViewSequence never moves back: an adoption at an older sequence is a lost update (the split admission
/// \* writes a push the view write's re-pull has overtaken).
/// AdSeqMono == [][adSeq' >= adSeq]_vars
/// \* No push is adopted while older than the adopted leader (know 900c63b485, #3588 finding A): a push below the
/// \* adopted sequence, or at it for another leader, leaves the held leader as it was (written out, not through
/// \* OlderThanAdopted, so it does not share the premise of the rule it checks).
/// NoOlderAdoption == [][\A r \in pend \ pend' :
///                          (r[2] < adSeq \/ (r[2] = adSeq /\ r[1] # lastAd)) => leader' = leader]_vars
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
///           MaxSeq = 5
///           MaxCommits = 3
///           EnvSteps = 5
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// PROPERTY PassiveLearns AdSeqMono
/// ```
///
/// ```tlacfg LeaderAdmission:removal
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 5
///           MaxCommits = 3
///           EnvSteps = 5
///           Env = {"view", "remove", "commit"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// PROPERTY PassiveLearns AdSeqMono
/// ```
///
/// ```tlacfg LeaderAdmission:stale
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 5
///           MaxCommits = 3
///           EnvSteps = 5
///           Env = {"view", "stale", "commit"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// PROPERTY PassiveLearns AdSeqMono
/// ```
///
/// ```tlacfg LeaderAdmission:passive
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 5
///           MaxCommits = 3
///           EnvSteps = 5
///           Env = {"view", "stale", "remove", "commit"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// PROPERTY PassiveLearns AdSeqMono
/// ```
///
/// ```tlacfg LeaderAdmission:voting
/// CONSTANTS Role = "voting"
///           Local = FALSE
///           MaxSeq = 5
///           MaxCommits = 3
///           EnvSteps = 5
///           Env = {"view", "stale", "remove", "commit", "demote"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// PROPERTY AdSeqMono
/// ```
///
/// ```tlacfg LeaderAdmission:local
/// CONSTANTS Role = "voting"
///           Local = TRUE
///           MaxSeq = 5
///           MaxCommits = 3
///           EnvSteps = 5
///           Env = {"view", "stale", "remove"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// PROPERTY LocalProgress AdSeqMono
/// ```
///
/// ```tlacfg LeaderAdmission:readmit
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 5
///           MaxCommits = 3
///           EnvSteps = 5
///           Env = {"view", "stale", "remove", "commit", "demote", "readmit"}
///           Mutant = {}
/// CONSTANT Fixed <- AllFixes
/// INVARIANT Safety
/// PROPERTY AdSeqMono NoOlderAdoption
/// ```
///
/// ```tlacfg LeaderAdmission:noentrypull
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"serial", "entryhold", "pushinview", "pullinview", "repull", "removalnopull"}
/// PROPERTY PassiveLearns
/// ```
///
/// ```tlacfg LeaderAdmission:pushnoview
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"serial", "entryhold", "entrypull", "pullinview", "repull", "removalnopull"}
/// INVARIANT PassiveInView
/// ```
///
/// ```tlacfg LeaderAdmission:pullnoview
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"serial", "entryhold", "entrypull", "pushinview", "repull", "removalnopull"}
/// INVARIANT PassiveInView
/// ```
///
/// ```tlacfg LeaderAdmission:norepull
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"serial", "entryhold", "entrypull", "pushinview", "pullinview", "removalnopull"}
/// PROPERTY PassiveLearns
/// ```
///
/// ```tlacfg LeaderAdmission:removalpull
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "remove", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"serial", "entryhold", "entrypull", "pushinview", "pullinview", "repull"}
/// INVARIANT NotNamedFailed
/// ```
///
/// ```tlacfg LeaderAdmission:mutdropempty
/// CONSTANTS Role = "passive"
///           Local = FALSE
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
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"entryhold", "entrypull", "pushinview", "pullinview", "repull", "removalnopull"}
/// INVARIANT PassiveInView
/// ```
///
/// ```tlacfg LeaderAdmission:interleavelearns
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"entryhold", "entrypull", "pushinview", "pullinview", "repull", "removalnopull"}
/// PROPERTY PassiveLearns
/// ```
///
/// ```tlacfg LeaderAdmission:interleavemono
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit"}
///           Mutant = {}
/// CONSTANT Fixed = {"entryhold", "entrypull", "pushinview", "pullinview", "repull", "removalnopull"}
/// PROPERTY AdSeqMono
/// ```
///
/// ```tlacfg LeaderAdmission:noentryhold
/// CONSTANTS Role = "voting"
///           Local = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit", "demote"}
///           Mutant = {}
/// CONSTANT Fixed = {"serial", "entrypull", "pushinview", "pullinview", "repull", "removalnopull"}
/// INVARIANT PassiveInView
/// ```
///
/// ```tlacfg LeaderAdmission:readmitnoadseq
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit", "readmit"}
///           Mutant = {}
/// CONSTANT Fixed = {"serial", "entryhold", "entrypull", "pushinview", "pullinview", "repull", "removalnopull"}
/// PROPERTY AdSeqMono
/// ```
///
/// ```tlacfg LeaderAdmission:readmitstale
/// CONSTANTS Role = "passive"
///           Local = FALSE
///           MaxSeq = 3
///           MaxCommits = 2
///           EnvSteps = 3
///           Env = {"view", "commit", "readmit"}
///           Mutant = {}
/// CONSTANT Fixed = {"serial", "entryhold", "entrypull", "pushinview", "pullinview", "repull", "removalnopull"}
/// PROPERTY NoOlderAdoption
/// ```
package org.pragmatica.consensus.leader.fsm;
