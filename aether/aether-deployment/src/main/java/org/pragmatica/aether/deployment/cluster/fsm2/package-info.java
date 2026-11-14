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
/// (*             WARNINGS row carrying it commits (#3085, Warnings.noteRaised / publish); the write that carries a *)
/// (*             held warning commits it as an unpublished marker in its row, and a successor replays the markers  *)
/// (*             it inherits (Warnings.inherit) or receives while suspended (Warnings.onPut); nothing is published *)
/// (*             while the node's leader value names another node (Warnings.ledByAnother) (#3175, fixes #3160).   *)
/// (*   "pre3085" the code before #3085: the Warn effect is published in the raising step, before the row commits. *)
/// (*   "pre2834" the code before #2834: Rebuild has no inherit, a successor's family sets start empty.            *)
/// (* LATE selects the #3203 ordering: TRUE (the code) lets a deposed node whose view still names itself (it has   *)
/// (* not yet taken the LeaderKey notification) publish from a late settlement; FALSE excludes exactly that        *)
/// (* publish, which is the code under the ordering where the LeaderKey notification arrives first (ledByAnother). *)
/// (* Abstractions: one subject per family; a row is [open, op, rc]: open or tombstone, and whether it carries an  *)
/// (* unpublished opener / recovery marker (FsmWarningValue.opener / recovery; the message text is abstracted);    *)
/// (* Nil is "no row". Activate and Rebuild are one step; the leader value is <<node, term>> and the fence compares *)
/// (* it with the committed one (Outbox LeaderTransaction); consensus election (Elect, CI-1) and the LeaderKey     *)
/// (* commit (CommitLeader, CI-16) are separate steps, so a successor activates suspended; the desired-row mirror  *)
/// (* is read straight from KV; "shown" is the operator-visible open set (ClusterEventAggregator.onRecovery). KV  *)
/// (* notifications are a whole-set sync (Notify), and a node takes the LeaderKey notification only once its mirror *)
/// (* caught up with KV (log order: every row committed before the LeaderKey precedes it); a row committed after   *)
/// (* it may reach a node early, an over-approximation. Not modelled: a recovery that Rebuild raises for a subject *)
/// (* that left the model (published at once, not held), the warning a Rebuild raises after inherit (held in the  *)
/// (* code), a second election while a LeaderKey commit is pending, and members of multi-member rows (see M9).     *)
/// (* The environment actions (Crash, Elect, CommitLeader, KvApply, KvFail, LoseSettle, CondOn, CondOff,          *)
/// (* SetTarget, NodeApply) are consensus and fault injection, not this package's code; they are part of the       *)
/// (* skeleton. CommitLeader is weakly fair (a LeaderKey commit follows an election); the others are not fair.     *)
/// (*                                                                                                              *)
/// (* Guarantee, as a bound. Every pass row holds for exactly two nodes {n1, n2}; two subjects, one event (e) and    *)
/// (* one level (l) (the goal rows: none, one desired/observed pair); at most MaxTerm - 1 leader changes (MaxTerm 2  *)
/// (* or 3 per row: one or two changes, each an Elect followed by its CommitLeader); at most MaxCond = 3 condition  *)
/// (* or target changes; at most MaxFault faults (1, or 2 in the goal rows: crashes, lost settlements, failed      *)
/// (* writes); RESUME = FALSE in every pass row (the RESUME = TRUE rows are reachable only via #3253, the guard that *)
/// (* parks Activate over Deactivate); and the LATE value the row names (code_nolate_*: LATE = FALSE excludes the  *)
/// (* #3203 ordering, so the code_* rows with LATE = TRUE are its stated residual). Nothing is claimed beyond those *)
/// (* bounds. The leader fence itself is the applier's (KvApply compares the full leader value) and is assumed;     *)
/// (* what this model checks is that the package's write path (Outbox flush, settlement, resubmission) never       *)
/// (* produces an accepted write for a node other than the committed leader (AcceptedOnlyForLeader), and what each  *)
/// (* property below states, nothing else. A witness-stale refusal (the refusal names this node under a newer       *)
/// (* witness) is modelled: the transaction is resubmitted unchanged under the new witness (var redo,               *)
/// (* Outbox.routeRefusal).                                                                                         *)
/// (* W-side CAS (M9). The rows are values and KvApply compares each mutated row with the expected value the       *)
/// (* transaction carries (Warnings.mutation); mutant "nocas" drops the comparison. The multi-member rows (strays,  *)
/// (* schema holds) are not modelled member by member, and the argument for them is from the code, not from TLC:   *)
/// (* Warnings.strays / holds build each row's replacement from this leader's own family state (the whole member  *)
/// (* map, never a merge with the stored value), so a write that skipped the comparison still installs a value the  *)
/// (* writer's family state describes; the writer's mirror then differs from KV (Indexer -> indexRow), the next     *)
/// (* reconcile marks the set dirty (Warnings.reconcile) and the flush writes the difference again. A lost member    *)
/// (* update is therefore transient, bounded by one reconcile pass and one write, not permanent [argued, not      *)
/// (* model-checked].                                                                                             *)
///
/// EXTENDS Naturals, Sequences, FiniteSets, TLC
///
/// CONSTANTS Nodes,      \* leader candidates
///           Subjects,   \* one warning subject per family modelled
///           EventFam,   \* subset of Subjects: raised only at an event (retry exhaustion, join, growth)
///           MODE,       \* "pre2834" | "pre3085" | "code"
///           A2834,      \* env assumption: no leader change / crash inside a raise-or-close-to-commit window,
///                       \* and a node that is not the committed and elected leader emits no family warning
///           MaxTerm,    \* bound on leader changes (terms)
///           MaxCond,    \* bound on environment condition / target changes
///           MaxFault,   \* bound on crashes, lost settlements and failed writes
///           FENCE,      \* TRUE = the code; FALSE = mutant without the leader fence
///           MUT,        \* "none" | mutant name (instrument validation)
///           RESUME,     \* TRUE: a node may regain leadership while still Active (its CI-1 Deactivate missed or coalesced),
///                       \* so it resumes on CI-16 with its old model; FALSE: it is Dormant before it is elected again
///           GOALS,      \* TRUE: model the desired/observed pair (target may change); FALSE: target fixed, no goal writes
///           LATE        \* TRUE: the code; FALSE: no publish by a node whose view still names itself after the LeaderKey moved (#3203)
///
/// None == "none"
///
/// LevelFam == Subjects \ EventFam
///
/// Persists == MODE \in {"pre3085", "code"}
///
/// \* A committed FsmWarningKey row: open or tombstone, and its unpublished opener / recovery markers (#3175). Nil = no row.
/// Val == [open : BOOLEAN, op : BOOLEAN, rc : BOOLEAN]
///
/// Nil == [open |-> FALSE, op |-> FALSE, rc |-> FALSE]
///
/// NoRows == [s \in Subjects |-> Nil]
///
/// EmptyF == [s \in {} |-> Nil]
///
/// OpenOf(r) == {s \in Subjects : r[s].open}
///
/// Overlay(r, f) == [s \in Subjects |-> IF s \in DOMAIN f THEN f[s] ELSE r[s]]
///
/// Min(a, b) == IF a < b THEN a ELSE b
///
/// VARIABLES term, kvLeader,          \* committed leader value (LeaderKey; the fence's reference)
///           elected, lkPend,         \* consensus leader (CI-1 Activate/Deactivate); its LeaderKey commit is pending
///           kv,                      \* committed FsmWarningKey rows (the persisted open set and its markers)
///           kvDesired, target, observed, \* one desired/observed pair and its blueprint target
///           cond,                    \* environment: which warning conditions currently hold
///           shown,                   \* operator-visible open warnings (cluster event log)
///           fsm, inc, view,          \* per node: Dormant/Active, incarnation, m.leader
///           warned, rows, dirty,     \* family sets, warningRows mirror, warningsDirty
///           flushQ, wInfl, gInfl, gIntent, fired,
///           held,                    \* per node: the family warnings raised or closed, in order, held until their WARNINGS row commits (#3085)
///           lnd,                     \* per node: warningsLanded, the held prefix the accepted WARNINGS write carried, not yet published
///           net, settles,            \* transactions in the engine; settlements in transit
///           condBudget, faultBudget,
///           redo                     \* per node: transactions resubmitted unchanged after a witness-stale refusal (Outbox.routeRefusal)
///
/// vars == <<term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, cond, shown, fsm, inc, view,
///           warned, rows, dirty, flushQ, wInfl, gInfl, gIntent, fired, held, lnd, net, settles,
///           condBudget, faultBudget, redo>>
///
/// Current == <<kvLeader, term>>
///
/// \* Lifecycle.java isSuspended: the model's leader value does not name this node
/// Suspended(n) == view[n][1] # n
///
/// \* Warnings.java ledByAnother: the model's leader value is present and names another node
/// LedBy(v, n) == v[1] # None /\ v[1] # n
///
/// TxOf(n, k) == {x \in net \cup settles : x.kind = k /\ x.node = n /\ x.inc = inc[n]}
///
/// WTraffic == {x \in net \cup settles : x.kind = "W"}
///
/// \* Warnings.java desired / marked: the committed set the node describes, each row marked with its unpublished held
/// \* warnings, a tombstone for a closed key with a held warning (#3175). Diff: the keys Warnings.difference writes.
/// HasMark(hd, s, up) == \E i \in 1..Len(hd) : hd[i].s = s /\ hd[i].up = up
///
/// Des(w, hd) == [s \in Subjects |-> [open |-> s \in w, op |-> HasMark(hd, s, TRUE), rc |-> HasMark(hd, s, FALSE)]]
///
/// Diff(w, r, hd) == {s \in Subjects : r[s] # Des(w, hd)[s]}
///
/// \* A2834's "outside the window": the leader's drain is done and (if the code persists) its open set committed, markers cleared.
/// Quiet(L) == \/ fsm[L] = "Dormant"
///             \/ /\ ~flushQ[L] /\ ~dirty[L] /\ ~wInfl[L] /\ WTraffic = {} /\ held[L] = <<>>
///                /\ (Persists => kv = Des(warned[L], <<>>))
///
/// \* Under A2834 only the committed, elected, non-suspended leader emits family warnings.
/// WarnOK(n) == ~A2834 \/ (n = kvLeader /\ n = elected /\ ~Suspended(n))
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
/// \* Warnings.java replay: a row's markers in the order that ends in the row's state, an open row's recovery before
/// \* its opener, a tombstone's opener before its recovery.
/// RowSeq(x, v) == LET o == IF v.op THEN <<[s |-> x, up |-> TRUE]>> ELSE <<>>
///                     c == IF v.rc THEN <<[s |-> x, up |-> FALSE]>> ELSE <<>>
///                 IN IF v.open THEN c \o o ELSE o \o c
/// RECURSIVE ReplaySeq(_, _)
///
/// ReplaySeq(P, r) == IF P = {} THEN <<>>
///                    ELSE LET x == CHOOSE y \in P : TRUE IN RowSeq(x, r[x]) \o ReplaySeq(P \ {x}, r)
///
/// \* The end of a step, Warnings.java noteRaised: a family Warn marks dirty and joins the held list (hd); then publish:
/// \* nothing while the held list is empty or the node's leader value v names another node (ledByAnother; LATE = FALSE also
/// \* while the committed leader moved and v still names the node), else every held warning when nothing is in flight and
/// \* the committed open rows equal the family sets (settled), else the prefix the accepted write carried (ld, warningsLanded);
/// \* what is published leaves the markers, so the set is dirty again; then clean (dirty, nothing in flight, empty
/// \* difference). Outbox.java requestFlush (hasIntents incl. Warnings.pending). Wired by ClusterFsm.java.
/// Apply(n, w, r, d, f, g, gi, fw, q, sh, hd, ld, v) ==
///   LET d1 == (Persists /\ fw) \/ d
///       skip == hd = <<>> \/ LedBy(v, n) \/ (~LATE /\ kvLeader # n)
///       cnt == IF skip THEN 0 ELSE IF ~f /\ OpenOf(r) = w THEN Len(hd) ELSE Min(ld, Len(hd))
///       hd2 == SubSeq(hd, cnt + 1, Len(hd))
///       d2 == d1 \/ cnt > 0
///       d3 == IF d2 /\ ~f /\ Diff(w, r, hd2) = {} THEN FALSE ELSE d2
///   IN /\ warned' = [warned EXCEPT ![n] = w]
///      /\ rows' = [rows EXCEPT ![n] = r]
///      /\ dirty' = [dirty EXCEPT ![n] = d3]
///      /\ wInfl' = [wInfl EXCEPT ![n] = f]
///      /\ gInfl' = [gInfl EXCEPT ![n] = g]
///      /\ gIntent' = [gIntent EXCEPT ![n] = gi]
///      /\ flushQ' = [flushQ EXCEPT ![n] = q \/ gi \/ (d3 /\ ~f)]
///      /\ held' = [held EXCEPT ![n] = hd2]
///      /\ lnd' = [lnd EXCEPT ![n] = IF skip THEN ld ELSE 0]
///      /\ shown' = Pubd(sh, SubSeq(hd, 1, cnt))
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
///   /\ rows' = [rows EXCEPT ![n] = NoRows]
///   /\ dirty' = [dirty EXCEPT ![n] = FALSE]
///   /\ flushQ' = [flushQ EXCEPT ![n] = FALSE]
///   /\ wInfl' = [wInfl EXCEPT ![n] = FALSE]
///   /\ gInfl' = [gInfl EXCEPT ![n] = FALSE]
///   /\ gIntent' = [gIntent EXCEPT ![n] = FALSE]
///   /\ fired' = [fired EXCEPT ![n] = {}]
///   /\ held' = [held EXCEPT ![n] = <<>>]
///   /\ lnd' = [lnd EXCEPT ![n] = 0]
///   /\ redo' = [redo EXCEPT ![n] = {}]
///
/// Init ==
///   /\ term = 1 /\ kvLeader = CHOOSE n \in Nodes : TRUE /\ elected = kvLeader /\ lkPend = FALSE
///   /\ kv = NoRows /\ kvDesired = 0 /\ target = 0 /\ observed = 0
///   /\ cond = {} /\ shown = {}
///   /\ fsm = [n \in Nodes |-> "Dormant"] /\ inc = [n \in Nodes |-> 0]
///   /\ view = [n \in Nodes |-> <<None, 0>>]
///   /\ warned = [n \in Nodes |-> {}] /\ rows = [n \in Nodes |-> NoRows]
///   /\ dirty = [n \in Nodes |-> FALSE] /\ flushQ = [n \in Nodes |-> FALSE]
///   /\ wInfl = [n \in Nodes |-> FALSE] /\ gInfl = [n \in Nodes |-> FALSE]
///   /\ gIntent = [n \in Nodes |-> FALSE] /\ fired = [n \in Nodes |-> {}] /\ held = [n \in Nodes |-> <<>>]
///   /\ lnd = [n \in Nodes |-> 0] /\ redo = [n \in Nodes |-> {}]
///   /\ net = {} /\ settles = {}
///   /\ condBudget = MaxCond /\ faultBudget = MaxFault
///
/// \* Environment: the leader process dies at any point between effects.
/// Crash(n) ==
///   /\ fsm[n] = "Active" /\ faultBudget > 0
///   /\ A2834 => (n # kvLeader \/ Quiet(n))
///   /\ faultBudget' = faultBudget - 1
///   /\ Discard(n)
///   /\ UNCHANGED <<term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, cond, shown, inc, view, net,
///                  settles, condBudget>>
///
/// \* Environment: consensus elects a leader (possibly the same node again); CI-1 Activate / Deactivate follow it. The
/// \* LeaderKey value moves only at CommitLeader, so a successor that activates in between activates suspended.
/// Elect(m) ==
///   /\ term < MaxTerm /\ ~lkPend
///   /\ A2834 => Quiet(kvLeader)
///   /\ RESUME \/ m = elected \/ fsm[m] = "Dormant"
///   /\ elected' = m /\ lkPend' = TRUE
///   /\ UNCHANGED <<term, kvLeader, kv, kvDesired, target, observed, cond, shown, fsm, inc, view, warned, rows, dirty,
///                  flushQ, wInfl, gInfl, gIntent, fired, net, settles, condBudget, faultBudget, held, lnd, redo>>
///
/// \* Environment: the LeaderKey commit of the elected leader; the committed leader value (the fence's reference) moves.
/// CommitLeader ==
///   /\ lkPend
///   /\ term' = term + 1 /\ kvLeader' = elected /\ lkPend' = FALSE
///   /\ UNCHANGED <<elected, kv, kvDesired, target, observed, cond, shown, fsm, inc, view, warned, rows, dirty, flushQ,
///                  wInfl, gInfl, gIntent, fired, net, settles, condBudget, faultBudget, held, lnd, redo>>
///
/// \* The consensus applier: a LeaderTransaction commits iff the committed leader is the tx's leader value (the fence;
/// \* FsmWarningValue is LeaderAuthorized, Warnings.java) and every mutated row equals the value the tx expects (CAS,
/// \* Warnings.mutation; mutant "nocas" skips the row comparison). A refusal reports the rows' current values (seen).
/// KvApply(tx) ==
///   /\ tx \in net
///   /\ LET fenceOk == tx.lv = Current \/ ~FENCE
///          casOk == IF tx.kind = "W" THEN MUT = "nocas" \/ \A s \in DOMAIN tx.wrep : kv[s] = tx.wexp[s]
///                   ELSE kvDesired = tx.exp
///          ok == fenceOk /\ casOk
///          st == [kind |-> tx.kind, node |-> tx.node, inc |-> tx.inc, out |-> IF ok THEN "acc" ELSE "ref",
///                 wrep |-> tx.wrep, seen |-> [s \in DOMAIN tx.wrep |-> kv[s]], lv |-> Current,
///                 cov |-> IF tx.kind = "W" THEN tx.cov ELSE 0, tx |-> tx]
///      IN /\ net' = net \ {tx}
///         /\ settles' = settles \cup {st}
///         /\ kv' = IF ok /\ tx.kind = "W" THEN Overlay(kv, tx.wrep) ELSE kv
///         /\ kvDesired' = IF ok /\ tx.kind = "G" THEN tx.val ELSE kvDesired
///   /\ UNCHANGED <<term, kvLeader, elected, lkPend, target, observed, cond, shown, fsm, inc, view, warned, rows, dirty,
///                  flushQ, wInfl, gInfl, gIntent, fired, held, lnd, condBudget, faultBudget, redo>>
///
/// \* Environment: a definite failure (Settlement.Failed), nothing committed.
/// KvFail(tx) ==
///   /\ tx \in net /\ faultBudget > 0
///   /\ faultBudget' = faultBudget - 1
///   /\ net' = net \ {tx}
///   /\ settles' = settles \cup {[kind |-> tx.kind, node |-> tx.node, inc |-> tx.inc, out |-> "fail",
///                                wrep |-> tx.wrep, seen |-> EmptyF, lv |-> Current, cov |-> 0, tx |-> tx]}
///   /\ UNCHANGED <<term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, cond, shown, fsm, inc, view,
///                  warned, rows, dirty, flushQ, wInfl, gInfl, gIntent, fired, condBudget, held, lnd, redo>>
///
/// \* Environment: the settlement never reaches the FSM (expiry handles it, #2143).
/// LoseSettle(s) ==
///   /\ s \in settles /\ faultBudget > 0
///   /\ faultBudget' = faultBudget - 1
///   /\ settles' = settles \ {s}
///   /\ UNCHANGED <<term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, cond, shown, fsm, inc, view,
///                  warned, rows, dirty, flushQ, wInfl, gInfl, gIntent, fired, net, condBudget, held, lnd, redo>>
///
/// CondOn(s) ==
///   /\ condBudget > 0 /\ s \notin cond
///   /\ cond' = cond \cup {s} /\ condBudget' = condBudget - 1
///   /\ UNCHANGED <<term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, shown, fsm, inc, view, warned,
///                  rows, dirty, flushQ, wInfl, gInfl, gIntent, fired, net, settles, faultBudget, held, lnd, redo>>
///
/// \* An event-only condition that ends starts a new episode: its retry cycle restarts, so it can exhaust (fire) again
/// \* (without the reset, WarningsShow has a spurious counterexample: e fires once per incarnation only).
/// CondOff(s) ==
///   /\ condBudget > 0 /\ s \in cond
///   /\ cond' = cond \ {s} /\ condBudget' = condBudget - 1
///   /\ fired' = [n \in Nodes |-> fired[n] \ {s}]
///   /\ UNCHANGED <<term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, shown, fsm, inc, view, warned,
///                  rows, dirty, flushQ, wInfl, gInfl, gIntent, net, settles, faultBudget, held, lnd, redo>>
///
/// SetTarget ==
///   /\ GOALS /\ condBudget > 0
///   /\ target' = 1 - target /\ condBudget' = condBudget - 1
///   /\ UNCHANGED <<term, kvLeader, elected, lkPend, kv, kvDesired, observed, cond, shown, fsm, inc, view, warned,
///                  rows, dirty, flushQ, wInfl, gInfl, gIntent, fired, net, settles, faultBudget, held, lnd, redo>>
///
/// \* The node FSM converges its observed row to the committed desired row.
/// NodeApply ==
///   /\ observed # kvDesired /\ observed' = kvDesired
///   /\ UNCHANGED <<term, kvLeader, elected, lkPend, kv, kvDesired, target, cond, shown, fsm, inc, view, warned, rows,
///                  dirty, flushQ, wInfl, gInfl, gIntent, fired, net, settles, condBudget, faultBudget, held, lnd, redo>>
/// ```
///
/// ```tla Fsm2Leader:tail
/// Next ==
///   \/ \E n \in Nodes : Activate(n) \/ Deactivate(n) \/ Crash(n) \/ Elect(n) \/ SeeLeader(n)
///                       \/ Tick(n) \/ Flush(n) \/ Expire(n) \/ Notify(n) \/ \E e \in EventFam : EventFire(n, e)
///   \/ CommitLeader
///   \/ \E tx \in net : KvApply(tx) \/ KvFail(tx)
///   \/ \E s \in settles : Settle(s) \/ DropSettle(s) \/ LoseSettle(s)
///   \/ \E s \in Subjects : CondOn(s) \/ CondOff(s)
///   \/ SetTarget \/ NodeApply
///
/// \* Fairness: every leader-side action and the engine/settlement delivery; the LeaderKey commit that follows an election;
/// \* none on the other environment actions (bounded anyway).
/// \* EventFire is fair: a successor retries while the fault persists (committed retry counts, Failures.java).
/// Fairness ==
///   /\ \A n \in Nodes : /\ WF_vars(Activate(n)) /\ WF_vars(Deactivate(n)) /\ WF_vars(SeeLeader(n))
///                       /\ WF_vars(Tick(n)) /\ WF_vars(Flush(n)) /\ WF_vars(Expire(n)) /\ WF_vars(Notify(n))
///                       /\ \A e \in EventFam : WF_vars(EventFire(n, e))
///   /\ WF_vars(CommitLeader)
///   /\ WF_vars(\E tx \in net : KvApply(tx))
///   /\ WF_vars(\E s \in settles : Settle(s) \/ DropSettle(s))
///   /\ WF_vars(NodeApply)
///
/// Spec == Init /\ [][Next]_vars /\ Fairness
///
/// TypeOK ==
///   /\ kv \in [Subjects -> Val] /\ shown \subseteq Subjects /\ cond \subseteq Subjects
///   /\ \A n \in Nodes : /\ warned[n] \subseteq Subjects /\ rows[n] \in [Subjects -> Val]
///                       /\ \A i \in 1..Len(held[n]) : held[n][i].s \in Subjects
///                       /\ lnd[n] \in Nat
///
/// \* Only the committed leader's writes are accepted: an accepted settlement names the node that is the committed leader when the
/// \* applier accepts it. Holds because Outbox.flush submits only while the node's own witness names it (Suspended) and the applier's
/// \* fence (KVStore: the full leader value, assumed here, not this package's code) refuses a witness that moved. It goes red when the
/// \* fence is off (mut_fence): a deposed node's earlier submission commits. Stated on the node, so it does not restate the applier's
/// \* term comparison; the term half of the fence is the applier's, assumed (see the header).
/// AcceptedOnlyForLeader == [][\A s \in settles' \ settles : s.out = "acc" => s.node = kvLeader]_vars
///
/// \* Outbox.flush while suspended submits nothing (the intents are dropped; the CI-16 resume re-derives them): no node whose witness
/// \* names another leader adds a transaction to the engine.
/// NoSubmitWhileSuspended == [][\A tx \in net' \ net : ~Suspended(tx.node)]_vars
///
/// \* The leader's views: two unsuspended Active nodes never carry a witness of the same term (one leader per term; Lifecycle.rebuild
/// \* takes snapshot.leader(), onLeader the committed value). Red when Activate does not require elected = n and names the node itself.
/// AtMostOneLiveLeader == \A a, b \in Nodes : (a # b /\ fsm[a] = "Active" /\ fsm[b] = "Active" /\ ~Suspended(a) /\ ~Suspended(b))
///                                              => view[a][2] # view[b][2]
///
/// \* #3085 safety half (Warnings.java "never published without a committed row"): in a persisting mode, a step of the committed leader
/// \* (witness = Current) that publishes a raise names only subjects whose row is committed open after the step. Stated for the
/// \* committed leader because a deposed node's accepted-but-late settlement can publish a row its successor has since removed: that is
/// \* the #3203 window (LATE, code_close), not this safety property. And for a step after which the node's mirror equals the
/// \* committed set: with the KV notification not yet applied (a lost settlement, Expire) the publish test compares the family sets with
/// \* a stale mirror (Warnings.publish, settled), a window the model reaches and this property does not claim away (code_inv trace: n2
/// \* removes e, its settlement is lost, e is raised again and equals the stale mirror {e}). That window is pinned on its own by
/// \* PublishedIsCommittedAnyMirror (code_lag_inv).
/// PublishedIsCommitted == [][Persists => \A n \in Nodes : (n = kvLeader /\ view[n] = Current /\ rows'[n] = kv' /\ held'[n] # held[n])
///                                                        => (shown' \ shown) \subseteq OpenOf(kv')]_vars
///
/// \* The mirror-lag window, stated: PublishedIsCommitted without the condition that the node's mirror equals the committed set.
/// PublishedIsCommittedAnyMirror == [][Persists => \A n \in Nodes : (n = kvLeader /\ view[n] = Current /\ held'[n] # held[n])
///                                                                 => (shown' \ shown) \subseteq OpenOf(kv')]_vars
///
/// \* A refused WARNINGS write whose refusal names this node under the witness it submitted with (Warnings.unlanded, via
/// \* Outbox.rederive) leaves the node with a re-draft driver (dirty, or a queued flush) or nothing left to write. Pins the line that
/// \* marks the open set dirty on a refusal: without it the next reconcile pass is the only driver.
/// RefusedWRedrafts == [][\A s \in settles \ settles' :
///                          (s.kind = "W" /\ s.out = "ref" /\ fsm[s.node] = "Active" /\ s.inc = inc[s.node]
///                           /\ s.lv = s.tx.lv /\ Persists /\ ~wInfl'[s.node])
///                              => (dirty'[s.node] \/ flushQ'[s.node] \/ Diff(warned'[s.node], rows'[s.node], held'[s.node]) = {})]_vars
///
/// \* Boundary invariant named from Warnings.java ("the family sets are the desired open set; warningRows mirrors the
/// \* committed one"): once the sole active leader is settled, what the operator sees is exactly its open set.
/// Settled(L) ==
///   /\ fsm[L] = "Active" /\ view[L] = Current /\ ~flushQ[L] /\ ~dirty[L] /\ ~wInfl[L] /\ WTraffic = {}
///   /\ (Persists => Diff(warned[L], rows[L], held[L]) = {} /\ rows[L] = kv) \* rows = kv: the KV notification has been applied (an
///                                                        \* orphan W tx of the node's previous incarnation can commit after Activate, the mirror lags)
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
/// PersistedOrCleared == \A s \in Subjects : (s \in shown) ~> (kv[s].open \/ s \notin shown)
///
/// \* Desired/observed converge after the last leader change.
/// Converges == <>[](kvDesired = target /\ observed = target)
/// ```
///
/// ```tlacfg Fsm2Leader:common
/// SPECIFICATION Spec
/// CONSTANT Nodes = {n1, n2}
/// INVARIANT TypeOK
/// ```
///
/// ```tlacfg Fsm2Leader:code_inv
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY AcceptedOnlyForLeader
/// PROPERTY NoSubmitWhileSuspended
/// INVARIANT AtMostOneLiveLeader
/// PROPERTY PublishedIsCommitted
/// PROPERTY RefusedWRedrafts
/// ```
///
/// ```tlacfg Fsm2Leader:code_persist
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 2
/// CONSTANT GOALS = TRUE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 2
/// CONSTANT GOALS = TRUE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {}
/// CONSTANT EventFam = {}
/// PROPERTY AcceptedOnlyForLeader
/// PROPERTY NoSubmitWhileSuspended
/// INVARIANT AtMostOneLiveLeader
/// ```
///
/// ```tlacfg Fsm2Leader:codeA_inv
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY AcceptedOnlyForLeader
/// PROPERTY NoSubmitWhileSuspended
/// INVARIANT AtMostOneLiveLeader
/// PROPERTY PublishedIsCommitted
/// PROPERTY RefusedWRedrafts
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:codeA_close
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:codeA_resume_inv
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = TRUE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY AcceptedOnlyForLeader
/// PROPERTY NoSubmitWhileSuspended
/// INVARIANT AtMostOneLiveLeader
/// PROPERTY PublishedIsCommitted
/// PROPERTY RefusedWRedrafts
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:codeA_resume_close
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = TRUE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsClose
/// ```
///
/// ```tlacfg Fsm2Leader:codeA_resume_show
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = TRUE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsShow
/// ```
///
/// ```tlacfg Fsm2Leader:codeA_resume_persist
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = TRUE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY PersistedOrCleared
/// ```
///
/// ```tlacfg Fsm2Leader:pre3085A_show
/// CONSTANT MODE = "pre3085"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsShow
/// ```
///
/// ```tlacfg Fsm2Leader:pre3085A_persist
/// CONSTANT MODE = "pre3085"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY PersistedOrCleared
/// ```
///
/// ```tlacfg Fsm2Leader:mut_twoleaders_inv
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "twoleaders"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT AtMostOneLiveLeader
/// ```
///
/// ```tlacfg Fsm2Leader:mut_fence
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = FALSE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY AcceptedOnlyForLeader
/// ```
///
/// ```tlacfg Fsm2Leader:mut_fence_goals
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = FALSE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = TRUE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {}
/// CONSTANT EventFam = {}
/// PROPERTY AcceptedOnlyForLeader
/// ```
///
/// ```tlacfg Fsm2Leader:mut_noinherit_agree
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "noinherit"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
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
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 2
/// CONSTANT GOALS = TRUE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {}
/// CONSTANT EventFam = {}
/// PROPERTY Converges
/// ```
///
/// ```tlacfg Fsm2Leader:code_nolate_close
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsClose
/// ```
///
/// ```tlacfg Fsm2Leader:code_nolate_show
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsShow
/// ```
///
/// ```tlacfg Fsm2Leader:code_nolate_persist
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY PersistedOrCleared
/// ```
///
/// ```tlacfg Fsm2Leader:code_nolate_agree
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:code_lag_inv
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY PublishedIsCommittedAnyMirror
/// ```
///
/// ```tlacfg Fsm2Leader:pre3085A_close
/// CONSTANT MODE = "pre3085"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsClose
/// ```
///
/// ```tlacfg Fsm2Leader:pre3085A_agree
/// CONSTANT MODE = "pre3085"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "none"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:mut_noreplay_show
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "noreplay"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY WarningsShow
/// ```
///
/// ```tlacfg Fsm2Leader:mut_nocas_agree
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "nocas"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
/// PROPERTY PublishedIsCommitted
/// ```
package org.pragmatica.aether.deployment.cluster.fsm2;
