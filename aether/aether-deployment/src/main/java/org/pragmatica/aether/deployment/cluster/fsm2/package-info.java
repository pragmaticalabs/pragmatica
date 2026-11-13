// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
/// The cluster (leader-side) deployment state machine of the rewrite specified in
/// `oss/internal/spec-rewrite/deployment-fsm-spec.md` (draft 6), as a pure transition function.
///
/// **Shape.** [ClusterFsm#step] takes a [ClusterState] and a [ClusterInput] and returns the next state and a list of
/// [ClusterEffect]s. The only reads beyond its arguments are the two injected clocks (read once per step) and the
/// injected jitter of [Settings]. The [ClusterModel] is owned by one step at a time and every field is an immutable value
/// ([PMap], [PSet], records), so a model that was returned never changes.
///
/// **Where the transitions live.**
/// - [Handlers]: the table of 3.3, one arm per input.
/// - [Lifecycle], [Reconcile]: the Active entry, the leader mirror, the timers and the ten-step reconcile pass.
/// - [Blueprints], [Schemas], [Slices], [Failures], [Strays], [Placement], [Rebalance], [Drains], [Communities], [Orphans],
///   [Divergence], [Outcomes]: the slice side.
/// - [Joins], [DepartureFlow], [Absence], [Backstop], [Directory], [Departures], [Liveness]: the membership side (the join table,
///   the fenced departure record with its leader-assigned epoch, the `joined` evidence that supersedes it, the per-class absence
///   backstop and the community breaker).
/// - [Outbox]: the write side. Handlers record intents; a queued `Flush` turns the intents of one mailbox drain into
///   `LeaderTransaction`s; [Outbox#settle] handles completions and refusals.
///
/// **Not here (phase 3).** The effect interpreter (mailbox, scheduler, applier submission, snapshot read), the seams
/// (`admission(n)`, `coreView()`, the local alive view), and every reader of `NodeArtifactKey`.
///
/// **TLA+ model of the leader boundary** (`Fsm2Leader`). The module is assembled from the fragments in this package by
/// `tools/tla/extract.py` (see `tools/tla/README.md`): this file carries the skeleton and the configs, the action fragments sit on
/// the methods that implement them (`Lifecycle#rebuild`, `Lifecycle#onLeader`, `Warnings#indexRow`, `Failures#exhausted`,
/// `Reconcile#run`, `Outbox#flush`, `Outbox#settle`, `Outbox#expire`, `ClusterFsm#deactivated`).
///
/// ```tla Fsm2Leader:head
/// (* FSM2 cluster deployment engine at its leader boundary: the leader's open-warning set, the leader fence, the *)
/// (* Rebuild inherit and the write path (Outbox flush, settlement) that carry it from one leader to the next.      *)
/// (* Bounds, not proofs: TLC explores every state within a config's constants (nodes, terms, condition changes,   *)
/// (* faults) and says nothing about larger ones, or about what the model abstracts.                               *)
/// (*                                                                                                              *)
/// (* MODE selects the code version modelled:                                                                      *)
/// (*   "code"    the code: a family warning is held (ClusterModel.warningsHeld) and published only once the       *)
/// (*             WARNINGS row carrying it commits (#3085, Warnings.noteRaised / publish).                         *)
/// (*   "pre3085" the code before #3085: the Warn effect is published in the raising step, before the row commits. *)
/// (*   "pre2834" the code before #2834: Rebuild has no inherit, a successor's family sets start empty.            *)
/// (* Abstractions: one subject per family; Activate and Rebuild are one step; the leader value is <<node, term>> *)
/// (* and the fence compares it with the committed one (Outbox LeaderTransaction); the desired-row mirror is read *)
/// (* straight from KV; "shown" is the operator-visible open set (ClusterEventAggregator.onRecovery). Not modelled:*)
/// (* a recovery that Rebuild raises for a subject that left the model (published at once, not held), and the     *)
/// (* warning a Rebuild raises after inherit (held in the code).                                                   *)
/// (* The environment actions (Crash, ChangeLeader, KvApply, KvFail, LoseSettle, CondOn, CondOff, SetTarget,      *)
/// (* NodeApply) are consensus and fault injection, not this package's code; they are part of the skeleton.        *)
///
/// EXTENDS Naturals, Sequences, FiniteSets, TLC
///
/// CONSTANTS Nodes,      \* leader candidates
///           Subjects,   \* one warning subject per family modelled
///           EventFam,   \* subset of Subjects: raised only at an event (retry exhaustion, join, growth)
///           MODE,       \* "pre2834" | "pre3085" | "code"
///           A2834,      \* env assumption: no leader change / crash inside a raise-or-close-to-commit window,
///                       \* and a node that is not the committed leader emits no family warning
///           MaxTerm,    \* bound on leader changes (terms)
///           MaxCond,    \* bound on environment condition / target changes
///           MaxFault,   \* bound on crashes, lost settlements and failed writes
///           FENCE,      \* TRUE = the code; FALSE = mutant without the leader fence
///           MUT,        \* "none" | mutant name (instrument validation)
///           RESUME,     \* TRUE: a node may regain leadership while still Active (its CI-1 Deactivate missed or coalesced),
///                       \* so it resumes on CI-16 with its old model; FALSE: it is Dormant before it is elected again
///           GOALS       \* TRUE: model the desired/observed pair (target may change); FALSE: target fixed, no goal writes
///
/// None == "none"
///
/// LevelFam == Subjects \ EventFam
///
/// Persists == MODE \in {"pre3085", "code"}
///
/// VARIABLES term, kvLeader,          \* committed leader value (the fence's reference)
///           kv,                      \* committed FsmWarningKey rows (the persisted open set)
///           kvDesired, target, observed, \* one desired/observed pair and its blueprint target
///           cond,                    \* environment: which warning conditions currently hold
///           shown,                   \* operator-visible open warnings (cluster event log)
///           fsm, inc, view,          \* per node: Dormant/Active, incarnation, m.leader
///           warned, rows, dirty,     \* family sets, warningRows mirror, warningsDirty
///           flushQ, wInfl, gInfl, gIntent, fired,
///           held,                    \* per node: the family warnings raised or closed, in order, held until their WARNINGS row commits (#3085)
///           net, settles,            \* transactions in the engine; settlements in transit
///           condBudget, faultBudget,
///           deposedCommit, inheritGap \* ghosts
///
/// vars == <<term, kvLeader, kv, kvDesired, target, observed, cond, shown, fsm, inc, view,
///           warned, rows, dirty, flushQ, wInfl, gInfl, gIntent, fired, held, net, settles,
///           condBudget, faultBudget, deposedCommit, inheritGap>>
///
/// Current == <<kvLeader, term>>
///
/// \* Lifecycle.java isSuspended: the model's leader value does not name this node
/// Suspended(n) == view[n][1] # n
///
/// TxOf(n, k) == {x \in net \cup settles : x.kind = k /\ x.node = n /\ x.inc = inc[n]}
///
/// WTraffic == {x \in net \cup settles : x.kind = "W"}
///
/// \* A2834's "outside the window": the leader's drain is done and (if the code persists) its open set committed.
/// Quiet(L) == \/ fsm[L] = "Dormant"
///             \/ /\ ~flushQ[L] /\ ~dirty[L] /\ ~wInfl[L] /\ WTraffic = {}
///                /\ (Persists => warned[L] = kv)
///
/// \* Under A2834 only the committed, non-suspended leader emits family warnings.
/// WarnOK(n) == ~A2834 \/ (n = kvLeader /\ ~Suspended(n))
///
/// \* The held warnings are an ordered list of effects (ClusterModel.warningsHeld): [s |-> subject, up |-> raised or closed].
/// RECURSIVE SeqOf(_, _)
///
/// SeqOf(S, up) == IF S = {} THEN <<>>
///                 ELSE LET x == CHOOSE y \in S : TRUE IN <<[s |-> x, up |-> up]>> \o SeqOf(S \ {x}, up)
/// RECURSIVE Pubd(_, _)
///
/// Pubd(sh, q) == IF q = <<>> THEN sh
///                ELSE Pubd(IF Head(q).up THEN sh \cup {Head(q).s} ELSE sh \ {Head(q).s}, Tail(q))
///
/// \* Warnings.java noteRaised (a family Warn marks dirty; dirty with an empty difference and nothing in flight is
/// \* clean), then Outbox.java requestFlush (hasIntents incl. Warnings.pending). Wired by ClusterFsm.java.
/// Apply(n, w, r, d, f, g, gi, fw, q, sh, hd) ==
///   LET d1 == IF Persists /\ fw /\ MUT # "nodirty" THEN TRUE ELSE d
///       d2 == IF d1 /\ ~f /\ w = r THEN FALSE ELSE d1
///       \* Warnings.publish: with nothing in flight and the committed mirror equal to the family sets, every held warning goes out
///       done == ~f /\ w = r
///   IN /\ warned' = [warned EXCEPT ![n] = w]
///      /\ rows' = [rows EXCEPT ![n] = r]
///      /\ dirty' = [dirty EXCEPT ![n] = d2]
///      /\ wInfl' = [wInfl EXCEPT ![n] = f]
///      /\ gInfl' = [gInfl EXCEPT ![n] = g]
///      /\ gIntent' = [gIntent EXCEPT ![n] = gi]
///      /\ flushQ' = [flushQ EXCEPT ![n] = q \/ gi \/ (d2 /\ ~f)]
///      /\ held' = [held EXCEPT ![n] = IF done THEN <<>> ELSE hd]
///      /\ shown' = IF done THEN Pubd(sh, hd) ELSE sh
///
/// \* Warn effects reach the sink inside the raising step (ClusterFsmInterpreter.java) before #3085; "code" holds them
/// \* (Warnings.noteRaised, `held`) until their row commits, so the raising step publishes nothing.
/// Pub(adds, rems) == IF MODE = "code" THEN shown ELSE (shown \ rems) \cup adds
///
/// Hold(n, adds, rems) == IF MODE = "code" THEN held[n] \o SeqOf(adds, TRUE) \o SeqOf(rems, FALSE) ELSE <<>>
///
/// Discard(n) ==   \* the model is dropped (ClusterFsm.java deactivated; a crash loses the process)
///   /\ fsm' = [fsm EXCEPT ![n] = "Dormant"]
///   /\ warned' = [warned EXCEPT ![n] = {}]
///   /\ rows' = [rows EXCEPT ![n] = {}]
///   /\ dirty' = [dirty EXCEPT ![n] = FALSE]
///   /\ flushQ' = [flushQ EXCEPT ![n] = FALSE]
///   /\ wInfl' = [wInfl EXCEPT ![n] = FALSE]
///   /\ gInfl' = [gInfl EXCEPT ![n] = FALSE]
///   /\ gIntent' = [gIntent EXCEPT ![n] = FALSE]
///   /\ fired' = [fired EXCEPT ![n] = {}]
///   /\ held' = [held EXCEPT ![n] = <<>>]
///
/// Init ==
///   /\ term = 1 /\ kvLeader = CHOOSE n \in Nodes : TRUE
///   /\ kv = {} /\ kvDesired = 0 /\ target = 0 /\ observed = 0
///   /\ cond = {} /\ shown = {}
///   /\ fsm = [n \in Nodes |-> "Dormant"] /\ inc = [n \in Nodes |-> 0]
///   /\ view = [n \in Nodes |-> <<None, 0>>]
///   /\ warned = [n \in Nodes |-> {}] /\ rows = [n \in Nodes |-> {}]
///   /\ dirty = [n \in Nodes |-> FALSE] /\ flushQ = [n \in Nodes |-> FALSE]
///   /\ wInfl = [n \in Nodes |-> FALSE] /\ gInfl = [n \in Nodes |-> FALSE]
///   /\ gIntent = [n \in Nodes |-> FALSE] /\ fired = [n \in Nodes |-> {}] /\ held = [n \in Nodes |-> <<>>]
///   /\ net = {} /\ settles = {}
///   /\ condBudget = MaxCond /\ faultBudget = MaxFault
///   /\ deposedCommit = FALSE /\ inheritGap = FALSE
///
/// \* Environment: the leader process dies at any point between effects.
/// Crash(n) ==
///   /\ fsm[n] = "Active" /\ faultBudget > 0
///   /\ A2834 => (n # kvLeader \/ Quiet(n))
///   /\ faultBudget' = faultBudget - 1
///   /\ Discard(n)
///   /\ UNCHANGED <<term, kvLeader, kv, kvDesired, target, observed, cond, shown, inc, view, net, settles,
///                  condBudget, deposedCommit, inheritGap>>
///
/// \* Environment: consensus elects a leader (possibly the same node again); the committed leader value moves.
/// ChangeLeader(m) ==
///   /\ term < MaxTerm
///   /\ A2834 => Quiet(kvLeader)
///   /\ RESUME \/ m = kvLeader \/ fsm[m] = "Dormant"
///   /\ term' = term + 1 /\ kvLeader' = m
///   /\ UNCHANGED <<kv, kvDesired, target, observed, cond, shown, fsm, inc, view, warned, rows, dirty, flushQ,
///                  wInfl, gInfl, gIntent, fired, net, settles, condBudget, faultBudget, deposedCommit, inheritGap, held>>
///
/// \* The consensus applier: a LeaderTransaction commits iff the committed leader is the tx's leader value (the fence;
/// \* FsmWarningValue is LeaderAuthorized, Warnings.java) and every expected value matches (CAS).
/// KvApply(tx) ==
///   /\ tx \in net
///   /\ LET fenceOk == tx.lv = Current \/ ~FENCE
///          casOk == IF tx.kind = "W" THEN tx.adds \cap kv = {} /\ tx.rems \subseteq kv ELSE kvDesired = tx.exp
///          ok == fenceOk /\ casOk
///          s == [kind |-> tx.kind, node |-> tx.node, inc |-> tx.inc, out |-> IF ok THEN "acc" ELSE "ref",
///                adds |-> tx.adds, rems |-> tx.rems, seen |-> kv \cap (tx.adds \cup tx.rems), lv |-> Current,
///                cov |-> IF tx.kind = "W" THEN tx.cov ELSE 0]
///      IN /\ net' = net \ {tx}
///         /\ settles' = settles \cup {s}
///         /\ kv' = IF ok /\ tx.kind = "W" THEN (kv \ tx.rems) \cup tx.adds ELSE kv
///         /\ kvDesired' = IF ok /\ tx.kind = "G" THEN tx.val ELSE kvDesired
///         /\ deposedCommit' = (deposedCommit \/ (ok /\ tx.lv # Current))
///   /\ UNCHANGED <<term, kvLeader, target, observed, cond, shown, fsm, inc, view, warned, rows, dirty, flushQ,
///                  wInfl, gInfl, gIntent, fired, held, condBudget, faultBudget, inheritGap>>
///
/// \* Environment: a definite failure (Settlement.Failed), nothing committed.
/// KvFail(tx) ==
///   /\ tx \in net /\ faultBudget > 0
///   /\ faultBudget' = faultBudget - 1
///   /\ net' = net \ {tx}
///   /\ settles' = settles \cup {[kind |-> tx.kind, node |-> tx.node, inc |-> tx.inc, out |-> "fail",
///                                adds |-> tx.adds, rems |-> tx.rems, seen |-> {}, lv |-> Current]}
///   /\ UNCHANGED <<term, kvLeader, kv, kvDesired, target, observed, cond, shown, fsm, inc, view, warned, rows,
///                  dirty, flushQ, wInfl, gInfl, gIntent, fired, condBudget, deposedCommit, inheritGap, held>>
///
/// \* Environment: the settlement never reaches the FSM (expiry handles it, #2143).
/// LoseSettle(s) ==
///   /\ s \in settles /\ faultBudget > 0
///   /\ faultBudget' = faultBudget - 1
///   /\ settles' = settles \ {s}
///   /\ UNCHANGED <<term, kvLeader, kv, kvDesired, target, observed, cond, shown, fsm, inc, view, warned, rows,
///                  dirty, flushQ, wInfl, gInfl, gIntent, fired, net, condBudget, deposedCommit, inheritGap, held>>
///
/// CondOn(s) ==
///   /\ condBudget > 0 /\ s \notin cond
///   /\ cond' = cond \cup {s} /\ condBudget' = condBudget - 1
///   /\ UNCHANGED <<term, kvLeader, kv, kvDesired, target, observed, shown, fsm, inc, view, warned, rows, dirty,
///                  flushQ, wInfl, gInfl, gIntent, fired, net, settles, faultBudget, deposedCommit, inheritGap, held>>
///
/// \* An event-only condition that ends starts a new episode: its retry cycle restarts, so it can exhaust (fire) again
/// \* (without the reset, WarningsShow has a spurious counterexample: e fires once per incarnation only).
/// CondOff(s) ==
///   /\ condBudget > 0 /\ s \in cond
///   /\ cond' = cond \ {s} /\ condBudget' = condBudget - 1
///   /\ fired' = [n \in Nodes |-> fired[n] \ {s}]
///   /\ UNCHANGED <<term, kvLeader, kv, kvDesired, target, observed, shown, fsm, inc, view, warned, rows, dirty,
///                  flushQ, wInfl, gInfl, gIntent, net, settles, faultBudget, deposedCommit, inheritGap, held>>
///
/// SetTarget ==
///   /\ GOALS /\ condBudget > 0
///   /\ target' = 1 - target /\ condBudget' = condBudget - 1
///   /\ UNCHANGED <<term, kvLeader, kv, kvDesired, observed, cond, shown, fsm, inc, view, warned, rows, dirty,
///                  flushQ, wInfl, gInfl, gIntent, fired, net, settles, faultBudget, deposedCommit, inheritGap, held>>
///
/// \* The node FSM converges its observed row to the committed desired row.
/// NodeApply ==
///   /\ observed # kvDesired /\ observed' = kvDesired
///   /\ UNCHANGED <<term, kvLeader, kv, kvDesired, target, cond, shown, fsm, inc, view, warned, rows, dirty,
///                  flushQ, wInfl, gInfl, gIntent, fired, net, settles, condBudget, faultBudget, deposedCommit, inheritGap, held>>
///
/// ```
///
/// ```tla Fsm2Leader:tail
/// Next ==
///   \/ \E n \in Nodes : Activate(n) \/ Deactivate(n) \/ Crash(n) \/ ChangeLeader(n) \/ SeeLeader(n)
///                       \/ Tick(n) \/ Flush(n) \/ Expire(n) \/ Notify(n) \/ \E e \in EventFam : EventFire(n, e)
///   \/ \E tx \in net : KvApply(tx) \/ KvFail(tx)
///   \/ \E s \in settles : Settle(s) \/ DropSettle(s) \/ LoseSettle(s)
///   \/ \E s \in Subjects : CondOn(s) \/ CondOff(s)
///   \/ SetTarget \/ NodeApply
///
/// \* Fairness: every leader-side action and the engine/settlement delivery; none on environment actions (bounded anyway).
/// \* EventFire is fair: a successor retries while the fault persists (committed retry counts, Failures.java).
/// Fairness ==
///   /\ \A n \in Nodes : /\ WF_vars(Activate(n)) /\ WF_vars(Deactivate(n)) /\ WF_vars(SeeLeader(n))
///                       /\ WF_vars(Tick(n)) /\ WF_vars(Flush(n)) /\ WF_vars(Expire(n)) /\ WF_vars(Notify(n))
///                       /\ \A e \in EventFam : WF_vars(EventFire(n, e))
///   /\ WF_vars(\E tx \in net : KvApply(tx))
///   /\ WF_vars(\E s \in settles : Settle(s) \/ DropSettle(s))
///   /\ WF_vars(NodeApply)
///
/// Spec == Init /\ [][Next]_vars /\ Fairness
///
/// TypeOK ==
///   /\ kv \subseteq Subjects /\ shown \subseteq Subjects /\ cond \subseteq Subjects
///   /\ \A n \in Nodes : warned[n] \subseteq Subjects /\ rows[n] \subseteq Subjects /\ \A i \in 1..Len(held[n]) : held[n][i].s \in Subjects
///
/// \* No deposed leader's write commits (the leader fence).
/// NoDeposedWrite == ~deposedCommit
///
/// \* A successor's open set contains the committed open set at Rebuild (#2834: the successor inherits the exact open set).
/// InheritSuperset == ~inheritGap
///
/// \* Boundary invariant named from Warnings.java ("the family sets are the desired open set; warningRows mirrors the
/// \* committed one"): once the sole active leader is settled, what the operator sees is exactly its open set.
/// Settled(L) ==
///   /\ fsm[L] = "Active" /\ view[L] = Current /\ ~flushQ[L] /\ ~dirty[L] /\ ~wInfl[L] /\ WTraffic = {}
///   /\ (Persists => warned[L] = rows[L] /\ rows[L] = kv) \* rows = kv: the KV notification has been applied (an orphan
///                                                       \* W tx of the node's previous incarnation can commit after Activate, the mirror lags)
///   /\ \A m \in Nodes \ {L} : fsm[m] = "Dormant"
///
/// OpenSetAgreement == Settled(kvLeader) => shown = warned[kvLeader]
///
/// \* Liveness. Every warning whose condition ended is eventually closed (#2834 / #3085 operator consequence).
/// WarningsClose == \A s \in Subjects : (s \in shown /\ s \notin cond) ~> (s \notin shown \/ s \in cond)
///
/// \* Every holding condition is eventually shown (the dual: a stale inherited set must not suppress a raise).
/// WarningsShow == \A s \in Subjects : (s \in cond) ~> (s \in shown \/ s \notin cond)
///
/// \* Every raised warning is eventually persisted or cleared.
/// PersistedOrCleared == \A s \in Subjects : (s \in shown) ~> (s \in kv \/ s \notin shown)
///
/// \* Desired/observed converge after the last leader change.
/// Converges == <>[](kvDesired = target /\ observed = target)
///
/// ```
///
/// ```tlacfg Fsm2Leader:common
/// SPECIFICATION Spec
/// CONSTANT Nodes = {n1, n2}
/// CONSTANT RESUME = FALSE
/// INVARIANT TypeOK
/// ```
///
/// ```tlacfg Fsm2Leader:code_inv
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT NoDeposedWrite
/// INVARIANT InheritSuperset
/// ```
///
/// ```tlacfg Fsm2Leader:code_persist
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY PersistedOrCleared
/// ```
///
/// ```tlacfg Fsm2Leader:code_close
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsClose
/// ```
///
/// ```tlacfg Fsm2Leader:code_show
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsShow
/// ```
///
/// ```tlacfg Fsm2Leader:code_agree
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:code_goals
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 2
/// CONSTANT GOALS = TRUE
/// CONSTANT Subjects = {}
/// CONSTANT EventFam = {}
/// PROPERTY Converges
/// ```
///
/// ```tlacfg Fsm2Leader:code_goals_inv
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 2
/// CONSTANT GOALS = TRUE
/// CONSTANT Subjects = {}
/// CONSTANT EventFam = {}
/// INVARIANT NoDeposedWrite
/// ```
///
/// ```tlacfg Fsm2Leader:codeA_inv
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT NoDeposedWrite
/// INVARIANT InheritSuperset
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:codeA_close
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsClose
/// ```
///
/// ```tlacfg Fsm2Leader:codeA_show
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsShow
/// ```
///
/// ```tlacfg Fsm2Leader:codeA_persist
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY PersistedOrCleared
/// ```
///
/// ```tlacfg Fsm2Leader:pre3085_close
/// CONSTANT MODE = "pre3085"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsClose
/// ```
///
/// ```tlacfg Fsm2Leader:pre3085_show
/// CONSTANT MODE = "pre3085"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsShow
/// ```
///
/// ```tlacfg Fsm2Leader:pre3085_persist
/// CONSTANT MODE = "pre3085"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY PersistedOrCleared
/// ```
///
/// ```tlacfg Fsm2Leader:pre3085_agree
/// CONSTANT MODE = "pre3085"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:pre2834_close
/// CONSTANT MODE = "pre2834"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsClose
/// ```
///
/// ```tlacfg Fsm2Leader:pre2834_agree
/// CONSTANT MODE = "pre2834"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:mut_fence
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = FALSE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT NoDeposedWrite
/// ```
///
/// ```tlacfg Fsm2Leader:mut_fence_goals
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = FALSE
/// CONSTANT MUT = "none"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = TRUE
/// CONSTANT Subjects = {}
/// CONSTANT EventFam = {}
/// INVARIANT NoDeposedWrite
/// ```
///
/// ```tlacfg Fsm2Leader:mut_noinherit_inv
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "noinherit"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT InheritSuperset
/// ```
///
/// ```tlacfg Fsm2Leader:mut_noinherit_agree
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "noinherit"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:mut_noinherit_close
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "noinherit"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsClose
/// ```
///
/// ```tlacfg Fsm2Leader:mut_noremove_show
/// CONSTANT MODE = "pre3085"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "noremove"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsShow
/// ```
///
/// ```tlacfg Fsm2Leader:mut_nodraft_persist
/// CONSTANT MODE = "pre3085"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "nodraft"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY PersistedOrCleared
/// ```
///
/// ```tlacfg Fsm2Leader:mut_noexpire_goals
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "noexpire"
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 2
/// CONSTANT GOALS = TRUE
/// CONSTANT Subjects = {}
/// CONSTANT EventFam = {}
/// PROPERTY Converges
/// ```
package org.pragmatica.aether.deployment.cluster.fsm2;
