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
/// (* MODE selects the code version modelled:                                                                        *)
/// (* "code" the code (#3274, PR #3278): the committed WARNINGS rows are the only warning state. A node's read (GET  *)
/// (* /api/v1/warnings) answers its mirror of the committed rows (Read, Warnings.warningRows); the notification      *)
/// (* stream is `notes`, emitted by rule E at the writer's accepted settlement (Settle, Warnings.notified); rule O   *)
/// (* joins another writer's open row to the family set (OnPut) and inherit is rule O over the snapshot (Activate).  *)
/// (* Nothing is held: Hold is empty in every mode, so `held` and `lnd` stay empty and the publish branch of Apply   *)
/// (* never runs.                                                                                                    *)
/// (* "pre3085" the code before #3085: the Warn effect is published in the raising step, before the row commits      *)
/// (* (`shown`).                                                                                                     *)
/// (* "pre2834" the code before #2834: Rebuild has no inherit, a successor's family sets start empty.                *)
/// (* LATE (#3203): TRUE lets a writer's accepted WARNINGS settlement land after a newer commit of the same row (the   *)
/// (* deposed leader's late settle); FALSE excludes that ordering: KvApply does not commit a W write over a key whose  *)
/// (* earlier accepted settlement is still in transit (KvApply). The code_nolate_* rows run LATE = FALSE.            *)
/// (* STAMP (#3203, PR #3443) models the operator view `opv` (the last warning entry delivered per subject, across  *)
/// (* nodes) and the per-node sink order `newest` (EffectTranslator.newestRevision): "off" = neither is modelled and *)
/// (* rule E is the #3274 one (every pre-#3203 row); "old" = rule E unfiltered, every notification delivered (no     *)
/// (* stamp); "fixed" = the PR: rule E drops a transition whose row the writer's mirror holds at a higher revision   *)
/// (* (Warnings.superseded, `>`), and each node's sink drops a stamped warning older than the newest it delivered    *)
/// (* for the row (EffectTranslator.ordered, `max`); mutants "ge" (superseded with >=) and "nostamp" (the effect      *)
/// (* carries revision 0, so the sink never reorders); "cand" = the fix (CTO ruling B): rule E emits nothing at the    *)
/// (* settle; a family warning is published from the committed FsmWarningKey put (ClusterEventAggregator.emitCommitted, *)
/// (* one emitter per KV index), so the view takes the transitions in commit order (KvApply); every mirror applies a row *)
/// (* only if its revision is above the one it holds. Abstraction: the committed emitter is atomic with the commit; its  *)
/// (* own limits (a dead owner suppresses until the next ownership record; replay suppresses) are emitCommitted's.      *)
/// (* PUTFIRST: a late accepted settlement is handled only after    *)
/// (* the writer's mirror took a newer put of the row (the premise Warnings.superseded needs); FALSE: in any order.  *)
/// (* The revision is the order key: a per-row counter raised by one at every commit (CAS on the whole row), so it   *)
/// (* orders writes across terms; the PR's stamp has no term component, and the model gives it none.                *)
/// (* Abstractions: one subject per family; a row is [open, op, rc, rv]: open or tombstone, two marker fields of the *)
/// (* #3175 model that no mode sets any more (always FALSE), and the revision; the message text is abstracted;     *)
/// (* Nil is "no row". Activate and Rebuild are one step; the leader value is <<node, term>> and the fence compares *)
/// (* it with the committed one (Outbox LeaderTransaction); consensus election (Elect, CI-1) and the LeaderKey     *)
/// (* commit (CommitLeader, CI-16) are separate steps, so a successor activates suspended; the desired-row mirror  *)
/// (* is read straight from KV; "shown" is the pre-#3274 published set (ClusterEventAggregator.onRecovery); in    *)
/// (* "code" (#3274) the operator reads the committed rows through a node's mirror (Read) and the notification      *)
/// (* stream is `notes`, emitted by rule E only (Settle), safety-checked, complete only for a handled settlement. KV *)
/// (* notifications are a whole-set sync (OnPut), and a node takes the LeaderKey notification only once its mirror *)
/// (* caught up with KV (log order: every row committed before the LeaderKey precedes it); a row committed after   *)
/// (* it may reach a node early, an over-approximation. Not modelled: a recovery that Rebuild raises for a subject *)
/// (* that left the model, the warning a Rebuild raises after inherit, a second election while a LeaderKey commit  *)
/// (* is pending, and members of multi-member rows (see M9).                                                       *)
/// (* The environment actions (Crash, Elect, CommitLeader, KvApply, KvFail, LoseSettle, CondOn, CondOff,          *)
/// (* SetTarget, NodeApply) are consensus and fault injection, not this package's code; they are part of the       *)
/// (* skeleton. CommitLeader is weakly fair (a LeaderKey commit follows an election); the others are not fair.     *)
/// (*                                                                                                              *)
/// (* Guarantee, as a bound. Every pass row holds for exactly two nodes {n1, n2}; two subjects, one event (e) and    *)
/// (* one level (l) (the goal rows: none, one desired/observed pair); at most MaxTerm - 1 leader changes (MaxTerm 2  *)
/// (* or 3 per row: one or two changes, each an Elect followed by its CommitLeader); at most MaxCond = 3 condition  *)
/// (* or target changes; at most MaxFault faults (1, or 2 in the goal rows: crashes, lost settlements, failed      *)
/// (* writes); RESUME = FALSE in every pass row but codeA_resume_* (RESUME = TRUE: a node regains leadership while *)
/// (* still Active, reachable only via #3253, the guard that parked Activate over Deactivate, fixed by #3260; those *)
/// (* rows pass; mut_noruleo_resume is red at their constants and green with RESUME off). Nothing is claimed beyond *)
/// (* those bounds. These are the configs as written; a row whose `small` column in tools/tla/models.tsv lowers a   *)
/// (* constant holds, as recorded there, at the lowered bound only.                                                 *)
/// (* The leader fence itself is the applier's (KvApply compares the full leader value) and is assumed;     *)
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
///           LATE,       \* TRUE: an accepted W settlement may land after a newer commit of its row (#3203); FALSE: excluded
///           STAMP,      \* "off" | "old" | "fixed" | "ge" | "nostamp" | "cand": the #3203 operator view and sink order (header)
///           PUTFIRST    \* TRUE: a late accepted W settlement waits until the writer's mirror took a newer put of the row
///
/// None == "none"
///
/// LevelFam == Subjects \ EventFam
///
/// Persists == MODE \in {"pre3085", "code"}
///
/// \* A committed FsmWarningKey row: open or tombstone, two #3175 marker fields no mode sets (always FALSE), and its revision
/// \* (FsmWarningValue.revision: every write sets it to the mirrored row's + 1, Warnings.put). Nil = no row (revision 0).
/// \* Each accepted WARNINGS commit raises a mutated row's revision by one, so rv <= ncommit (TypeOK): the revision is bounded
/// \* by the number of commits, which the model's existing constants (MaxCond, MaxFault, MaxTerm) bound; no constant of its own.
/// Row(m) == [open : BOOLEAN, op : BOOLEAN, rc : BOOLEAN, rv : 0..m]
///
/// Nil == [open |-> FALSE, op |-> FALSE, rc |-> FALSE, rv |-> 0]
///
/// \* A row's content without its revision (FsmWarningValue.sameContent, used by Warnings.same for the difference), and a
/// \* content with a revision.
/// Cnt(v) == [open |-> v.open, op |-> v.op, rc |-> v.rc]
///
/// Rev(d, r) == [open |-> d.open, op |-> d.op, rc |-> d.rc, rv |-> r]
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
///           kv,                      \* committed FsmWarningKey rows (the persisted open set)
///           kvDesired, target, observed, \* one desired/observed pair and its blueprint target
///           cond,                    \* environment: which warning conditions currently hold
///           shown,                   \* operator-visible open warnings (cluster event log)
///           fsm, inc, view,          \* per node: Dormant/Active, incarnation, m.leader
///           warned, rows, dirty,     \* family sets, warningRows mirror, warningsDirty
///           flushQ, wInfl, gInfl, gIntent, fired,
///           held,                    \* per node: held warnings of the #3085/#3175 publish; always <<>> (Hold: nothing is held, #3274)
///           lnd,                     \* per node: the landed prefix of `held`; always 0
///           net, settles,            \* transactions in the engine; settlements in transit
///           condBudget, faultBudget,
///           redo,                    \* per node: transactions resubmitted unchanged after a witness-stale refusal (Outbox.routeRefusal)
///           notes,                   \* #3274 notification stream: [c, s, up, dup], one per emission (dup: the transition was already notified)
///           trans,                   \* history: every committed open/closed transition [c |-> commit number, s, up]
///           ncommit,                 \* history: accepted WARNINGS commits so far (names a commit)
///           pend,                    \* per node: a committed WARNINGS put has not yet reached the node (Indexer -> onPut)
///           closedOff,               \* history, per node: subjects this incarnation closed on an observed condition-off (Tick),
///                                    \* not raised or joined since (NoBlindTombstone)
///           kvW,                     \* per committed row: its writer stamp <<node, incarnation>> (FsmWarningValue writerNode /
///                                    \* writerProcess; the model's incarnation stands for the process), read by rule O (OnPut)
///           opv,                     \* #3203 operator view: per subject, the last delivered entry [up, rv] (any node's sink),
///                                    \* and cl, the highest revision of a close delivered for it (0: none)
///           newest                   \* #3203 per node, per subject: the newest revision its sink delivered (EffectTranslator)
///
/// ovars == <<opv, newest>>
///
/// nvars == <<notes, trans, ncommit, pend, kvW, ovars>>
///
/// vars == <<term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, cond, shown, fsm, inc, view,
///           warned, rows, dirty, flushQ, wInfl, gInfl, gIntent, fired, held, lnd, net, settles,
///           condBudget, faultBudget, redo, notes, trans, ncommit, pend, closedOff, kvW, opv, newest>>
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
/// \* Warnings.java desired: the committed set the node describes (the marks are from the #3175 model; with `held` empty they
/// \* are FALSE). Diff: the keys Warnings.difference writes.
/// HasMark(hd, s, up) == \E i \in 1..Len(hd) : hd[i].s = s /\ hd[i].up = up
///
/// Des(w, hd) == [s \in Subjects |-> [open |-> s \in w, op |-> HasMark(hd, s, TRUE), rc |-> HasMark(hd, s, FALSE)]]
///
/// Diff(w, r, hd) == {s \in Subjects : Cnt(r[s]) # Des(w, hd)[s]}
///
/// \* A2834's "outside the window": the leader's drain is done and (if the code persists) its open set committed, markers cleared.
/// Quiet(L) == \/ fsm[L] = "Dormant"
///             \/ /\ ~flushQ[L] /\ ~dirty[L] /\ ~wInfl[L] /\ WTraffic = {} /\ held[L] = <<>>
///                /\ (Persists => \A s \in Subjects : Cnt(kv[s]) = Des(warned[L], <<>>)[s])
///
/// \* Under A2834 only the committed, elected, non-suspended leader emits family warnings.
/// WarnOK(n) == ~A2834 \/ (n = kvLeader /\ n = elected /\ ~Suspended(n))
///
/// \* The held warnings are an ordered list of effects (the model variable `held`; the code has no such field): [s |-> subject, up |-> raised or closed].
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
/// \* The end of a step: a family Warn marks dirty (fw); the publish of held warnings (skip, cnt) is the #3085/#3175 mechanism and
/// \* never runs, because the held list hd is always empty (Hold), so `skip` holds and LATE is never read in effect; then clean
/// \* (dirty, nothing in flight, empty difference). Outbox.java requestFlush (hasIntents incl. Warnings.pending). Wired by
/// \* ClusterFsm.java.
/// Apply(n, w, r, d, f, g, gi, fw, q, sh, hd, ld, v) ==
///   LET d1 == (Persists /\ fw) \/ d
///       skip == hd = <<>> \/ LedBy(v, n) \/ (~LATE /\ kvLeader # n)
///       cnt == IF skip THEN 0 ELSE IF ~f /\ OpenOf(r) = w THEN Len(hd) ELSE Min(ld, Len(hd))
///       hd2 == SubSeq(hd, cnt + 1, Len(hd))
///       d2 == d1 \/ cnt > 0
///       d3 == IF d2 /\ ~f /\ Diff(w, r, hd2) = {} THEN FALSE ELSE d2
///   IN /\ warned' = [warned EXCEPT ![n] = w]
///      \* the family set shrinks only at a Tick close (an observed condition-off); a subject that joins it again leaves the history
///      /\ closedOff' = [closedOff EXCEPT ![n] = (closedOff[n] \cup (warned[n] \ w)) \ w]
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
/// \* Warn effects reach the sink inside the raising step (ClusterFsmInterpreter.java) before #3085; "code" (#3274) publishes
/// \* nothing at the raise: its notifications are rule E's, at the accepted settlement (Settle).
/// Pub(adds, rems) == IF MODE = "code" THEN shown ELSE (shown \ rems) \cup adds
///
/// \* #3274: no mode holds a warning any more (the merged code publishes by rule E, Settle / Warnings.accepted); the held list
/// \* stays empty, so the marker fields of every row stay FALSE.
/// Hold(n, adds, rems) == <<>>
///
/// \* Rule E (Warnings.notified): per mutated subject, from the committed (expected, replacement) pair: the opener when the
/// \* replacement is open and the expected is not, the recovery when the expected is open and the replacement closed; each
/// \* names commit c. Emit adds one notification per transition, marked dup when that transition was already notified.
/// Note(N, c, x, up) == [c |-> c, s |-> x, up |-> up, dup |-> \E y \in N : y.c = c /\ y.s = x]
///
/// Emit(N, c, exp, rep) == N \cup {Note(N, c, x, rep[x].open) : x \in {y \in DOMAIN rep : exp[y].open # rep[y].open}}
///
/// \* #3203 (Warnings.superseded): node n's mirror holds row x at a higher revision than the replacement r of its own accepted
/// \* write, so rule E drops the transition. The mirror is read before the write lands (Warnings.accepted: notified, then landed).
/// \* Mutant "ge" compares with >=. "off" and "old" have no filter.
/// Sup(n, x, r) == CASE STAMP \in {"fixed", "nostamp"} -> rows[n][x].rv > r.rv
///                   [] STAMP = "ge" -> rows[n][x].rv >= r.rv
///                   [] OTHER -> FALSE
///
/// \* The replacement rows rule E reads: the superseded ones removed (Warnings.notified: replacement.filter(!superseded)).
/// Kept(n, rep) == [x \in {y \in DOMAIN rep : ~Sup(n, y, rep[y])} |-> rep[x]]
///
/// \* The transitions rule E emits for an accepted write: (expected, kept replacement) pairs whose open bit differs.
/// Tr(exp, rep) == {y \in DOMAIN rep : exp[y].open # rep[y].open}
///
/// \* The stamp the Warn effect carries (ClusterEffect.Warn revision): the row's revision; 0 = unstamped ("old": no stamp at all,
/// \* "nostamp": the stamp dropped). EffectTranslator.ordered delivers an unstamped warning always, a stamped one iff its revision
/// \* is not older than the newest delivered for the row on that node (newestRevision.merge(.., Math::max) == revision).
/// Stamp(r) == IF STAMP \in {"fixed", "ge"} THEN r.rv ELSE 0
///
/// Passes(n, x, r) == Stamp(r) = 0 \/ Stamp(r) >= newest[n][x]
///
/// Max(a, b) == IF a > b THEN a ELSE b
///
/// \* "cand" (ruling B): the committed emitter publishes; rule E at the settle does not.
/// CommitPub == STAMP = "cand" /\ MODE = "code" /\ MUT # "noruleE"
///
/// \* The operator view after delivering the committed transitions tr of replacement rows f.
/// View(tr, f) == [x \in Subjects |-> IF x \in tr THEN [up |-> f[x].open, rv |-> f[x].rv,
///                                                    cl |-> IF f[x].open THEN opv[x].cl ELSE Max(opv[x].cl, f[x].rv)]
///                                   ELSE opv[x]]
///
/// \* How a mirror takes rows f (an accepted write's replacements, a refusal's values, a put): the code overlays them
/// \* (Warnings.landed / indexRow); "cand" takes a row only if its revision is above the one the mirror holds.
/// Mirror(r, f) == IF STAMP = "cand" THEN [s \in Subjects |-> IF s \in DOMAIN f /\ f[s].rv > r[s].rv THEN f[s] ELSE r[s]]
///                 ELSE Overlay(r, f)
///
/// \* The READ (GET /api/v1/warnings, Warnings.java warningRows): what node n answers is the open rows of its mirror.
/// Read(n) == OpenOf(rows[n])
///
/// \* The newest committed transition of subject x (mutant "emitonput" names it from the put).
/// Last(x) == CHOOSE t \in trans : t.s = x /\ \A u \in trans : u.s = x => u.c <= t.c
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
///   /\ pend' = [pend EXCEPT ![n] = FALSE]
///   /\ closedOff' = [closedOff EXCEPT ![n] = {}]
///
/// Init ==
///   /\ term = 1 /\ kvLeader = (CHOOSE n \in Nodes : TRUE) /\ elected = kvLeader /\ lkPend = FALSE
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
///   /\ notes = {} /\ trans = {} /\ ncommit = 0 /\ pend = [n \in Nodes |-> FALSE] /\ closedOff = [n \in Nodes |-> {}]
///   /\ kvW = [s \in Subjects |-> <<None, 0>>]
///   /\ opv = [s \in Subjects |-> [up |-> FALSE, rv |-> 0, cl |-> 0]] /\ newest = [n \in Nodes |-> [s \in Subjects |-> 0]]
///   /\ condBudget = MaxCond /\ faultBudget = MaxFault
///
/// \* Environment: the leader process dies at any point between effects.
/// Crash(n) ==
///   /\ fsm[n] = "Active" /\ faultBudget > 0
///   /\ A2834 => (n # kvLeader \/ Quiet(n))
///   /\ faultBudget' = faultBudget - 1
///   /\ Discard(n)
///   /\ newest' = [newest EXCEPT ![n] = [s \in Subjects |-> 0]]   \* a restart empties EffectTranslator.newestRevision
///   /\ UNCHANGED <<notes, opv, trans, ncommit, kvW, term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, cond, shown, inc, view, net,
///                  settles, condBudget>>
///
/// \* Environment: consensus elects a leader (possibly the same node again); CI-1 Activate / Deactivate follow it. The
/// \* LeaderKey value moves only at CommitLeader, so a successor that activates in between activates suspended.
/// Elect(m) ==
///   /\ term < MaxTerm /\ ~lkPend
///   /\ A2834 => Quiet(kvLeader)
///   /\ RESUME \/ m = elected \/ fsm[m] = "Dormant"
///   /\ elected' = m /\ lkPend' = TRUE
///   /\ UNCHANGED <<nvars, closedOff, term, kvLeader, kv, kvDesired, target, observed, cond, shown, fsm, inc, view, warned, rows, dirty,
///                  flushQ, wInfl, gInfl, gIntent, fired, net, settles, condBudget, faultBudget, held, lnd, redo>>
///
/// \* Environment: the LeaderKey commit of the elected leader; the committed leader value (the fence's reference) moves.
/// CommitLeader ==
///   /\ lkPend
///   /\ term' = term + 1 /\ kvLeader' = elected /\ lkPend' = FALSE
///   /\ UNCHANGED <<nvars, closedOff, elected, kv, kvDesired, target, observed, cond, shown, fsm, inc, view, warned, rows, dirty, flushQ,
///                  wInfl, gInfl, gIntent, fired, net, settles, condBudget, faultBudget, held, lnd, redo>>
///
/// \* The consensus applier: a LeaderTransaction commits iff the committed leader is the tx's leader value (the fence;
/// \* FsmWarningValue is LeaderAuthorized, Warnings.java) and every mutated row equals the value the tx expects (CAS,
/// \* Warnings.mutation; KVStore.validMutation compares the whole record, revision included; mutant "nocas" skips the row
/// \* comparison, mutant "norevision" compares the content only and admits an ABA commit). A refusal reports the rows' current
/// \* values (seen).
/// KvApply(tx) ==
///   /\ tx \in net
///   /\ LET fenceOk == tx.lv = Current \/ ~FENCE
///          casOk == IF tx.kind = "W" THEN \/ MUT = "nocas"
///                                          \/ (MUT = "norevision" /\ \A s \in DOMAIN tx.wrep : Cnt(kv[s]) = Cnt(tx.wexp[s]))
///                                          \/ (MUT # "norevision" /\ \A s \in DOMAIN tx.wrep : kv[s] = tx.wexp[s])
///                   ELSE kvDesired = tx.exp
///          ok == fenceOk /\ casOk
///          okW == ok /\ tx.kind = "W"
///          \* LATE = FALSE: no W commit over a row whose earlier accepted settlement is still in transit (no late settle, #3203)
///          early == \E st \in settles : st.kind = "W" /\ st.out = "acc" /\ DOMAIN st.wrep \cap DOMAIN tx.wrep # {}
///          c == IF okW THEN ncommit + 1 ELSE 0
///          st == [kind |-> tx.kind, node |-> tx.node, inc |-> tx.inc, out |-> IF ok THEN "acc" ELSE "ref",
///                 wrep |-> tx.wrep, seen |-> [s \in DOMAIN tx.wrep |-> kv[s]], lv |-> Current,
///                 cov |-> IF tx.kind = "W" THEN tx.cov ELSE 0, tx |-> tx, c |-> c]
///      IN /\ LATE \/ ~okW \/ ~early
///         /\ net' = net \ {tx}
///         /\ ncommit' = IF okW THEN c ELSE ncommit
///         /\ trans' = IF okW THEN trans \cup {[c |-> c, s |-> x, up |-> tx.wrep[x].open] :
///                                              x \in {y \in DOMAIN tx.wrep : kv[y].open # tx.wrep[y].open}}
///                     ELSE trans
///         \* ruling B: the committed put is published here, in commit order (emitCommitted on the FsmWarningKey put)
///         /\ notes' = IF okW /\ CommitPub THEN Emit(notes, c, kv, tx.wrep) ELSE notes
///         /\ opv' = IF okW /\ CommitPub THEN View(Tr(kv, tx.wrep), tx.wrep) ELSE opv
///         /\ pend' = IF okW THEN [m \in Nodes |-> pend[m] \/ fsm[m] = "Active"] ELSE pend
///         /\ kvW' = IF okW THEN [x \in Subjects |-> IF x \in DOMAIN tx.wrep THEN <<tx.node, tx.inc>> ELSE kvW[x]] ELSE kvW
///         /\ settles' = settles \cup {st}
///         /\ kv' = IF ok /\ tx.kind = "W" THEN Overlay(kv, tx.wrep) ELSE kv
///         /\ kvDesired' = IF ok /\ tx.kind = "G" THEN tx.val ELSE kvDesired
///   /\ UNCHANGED <<newest, closedOff, term, kvLeader, elected, lkPend, target, observed, cond, shown, fsm, inc, view, warned, rows, dirty,
///                  flushQ, wInfl, gInfl, gIntent, fired, held, lnd, condBudget, faultBudget, redo>>
///
/// \* Environment: a definite failure (Settlement.Failed), nothing committed.
/// KvFail(tx) ==
///   /\ tx \in net /\ faultBudget > 0
///   /\ faultBudget' = faultBudget - 1
///   /\ net' = net \ {tx}
///   /\ settles' = settles \cup {[kind |-> tx.kind, node |-> tx.node, inc |-> tx.inc, out |-> "fail",
///                                wrep |-> tx.wrep, seen |-> EmptyF, lv |-> Current, cov |-> 0, tx |-> tx, c |-> 0]}
///   /\ UNCHANGED <<nvars, closedOff, term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, cond, shown, fsm, inc, view,
///                  warned, rows, dirty, flushQ, wInfl, gInfl, gIntent, fired, condBudget, held, lnd, redo>>
///
/// \* Environment: the settlement never reaches the FSM (expiry handles it, #2143).
/// LoseSettle(s) ==
///   /\ s \in settles /\ faultBudget > 0
///   /\ faultBudget' = faultBudget - 1
///   /\ settles' = settles \ {s}
///   /\ UNCHANGED <<nvars, closedOff, term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, cond, shown, fsm, inc, view,
///                  warned, rows, dirty, flushQ, wInfl, gInfl, gIntent, fired, net, condBudget, held, lnd, redo>>
///
/// CondOn(s) ==
///   /\ condBudget > 0 /\ s \notin cond
///   /\ cond' = cond \cup {s} /\ condBudget' = condBudget - 1
///   /\ UNCHANGED <<nvars, closedOff, term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, shown, fsm, inc, view, warned,
///                  rows, dirty, flushQ, wInfl, gInfl, gIntent, fired, net, settles, faultBudget, held, lnd, redo>>
///
/// \* An event-only condition that ends starts a new episode: its retry cycle restarts, so it can exhaust (fire) again
/// \* (without the reset, WarningsShow has a spurious counterexample: e fires once per incarnation only).
/// CondOff(s) ==
///   /\ condBudget > 0 /\ s \in cond
///   /\ cond' = cond \ {s} /\ condBudget' = condBudget - 1
///   /\ fired' = [n \in Nodes |-> fired[n] \ {s}]
///   /\ UNCHANGED <<nvars, closedOff, term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, shown, fsm, inc, view, warned,
///                  rows, dirty, flushQ, wInfl, gInfl, gIntent, net, settles, faultBudget, held, lnd, redo>>
///
/// SetTarget ==
///   /\ GOALS /\ condBudget > 0
///   /\ target' = 1 - target /\ condBudget' = condBudget - 1
///   /\ UNCHANGED <<nvars, closedOff, term, kvLeader, elected, lkPend, kv, kvDesired, observed, cond, shown, fsm, inc, view, warned,
///                  rows, dirty, flushQ, wInfl, gInfl, gIntent, fired, net, settles, faultBudget, held, lnd, redo>>
///
/// \* The node FSM converges its observed row to the committed desired row.
/// NodeApply ==
///   /\ observed # kvDesired /\ observed' = kvDesired
///   /\ UNCHANGED <<nvars, closedOff, term, kvLeader, elected, lkPend, kv, kvDesired, target, cond, shown, fsm, inc, view, warned, rows,
///                  dirty, flushQ, wInfl, gInfl, gIntent, fired, net, settles, condBudget, faultBudget, held, lnd, redo>>
/// ```
///
/// ```tla Fsm2Leader:tail
/// Next ==
///   \/ \E n \in Nodes : Activate(n) \/ Deactivate(n) \/ Crash(n) \/ Elect(n) \/ SeeLeader(n)
///                       \/ Tick(n) \/ Flush(n) \/ Expire(n) \/ OnPut(n) \/ \E e \in EventFam : EventFire(n, e)
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
///                       /\ WF_vars(Tick(n)) /\ WF_vars(Flush(n)) /\ WF_vars(Expire(n)) /\ WF_vars(OnPut(n))
///                       /\ \A e \in EventFam : WF_vars(EventFire(n, e))
///   /\ WF_vars(CommitLeader)
///   /\ WF_vars(\E tx \in net : KvApply(tx))
///   /\ WF_vars(\E s \in settles : Settle(s) \/ DropSettle(s))
///   /\ WF_vars(NodeApply)
///
/// Spec == Init /\ [][Next]_vars /\ Fairness
///
/// TypeOK ==
///   /\ kv \in [Subjects -> Row(ncommit)] /\ shown \subseteq Subjects /\ cond \subseteq Subjects
///   /\ \A n \in Nodes : /\ warned[n] \subseteq Subjects /\ rows[n] \in [Subjects -> Row(ncommit)]
///                       /\ \A i \in 1..Len(held[n]) : held[n][i].s \in Subjects
///                       /\ closedOff[n] \subseteq Subjects
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
/// \* (#3085's PublishedIsCommitted and its mirror-lag variant are removed: both constrained the publish of held warnings, and
/// \* nothing is held in any mode since #3274, so neither checked a step. NoPhantom states the code's "no notification without a
/// \* committed transition"; the read is OpenSetAgreement's.)
///
/// \* A refused WARNINGS write whose refusal names this node under the witness it submitted with (Warnings.unlanded, via
/// \* Outbox.rederive) leaves the node with a re-draft driver (dirty, or a queued flush) or nothing left to write. Pins the line that
/// \* marks the open set dirty on a refusal: without it the next reconcile pass is the only driver.
/// RefusedWRedrafts == [][\A s \in settles \ settles' :
///                          (s.kind = "W" /\ s.out = "ref" /\ fsm[s.node] = "Active" /\ s.inc = inc[s.node]
///                           /\ s.lv = s.tx.lv /\ Persists /\ ~wInfl'[s.node])
///                              => (dirty'[s.node] \/ flushQ'[s.node] \/ Diff(warned'[s.node], rows'[s.node], held'[s.node]) = {})]_vars
///
/// \* Rule O and inherit, over the desired set: an accepted WARNINGS write never tombstones a committed open row whose condition
/// \* still holds, unless its writer closed that subject on an observed condition-off (the subject left the family set at a Tick
/// \* and did not rejoin; `blind` is fixed at the draft, so a resubmission keeps it). A successor that did not inherit an open
/// \* row (mutant "noinherit") or did not join another writer's open row (mutant "noruleo") drafts the tombstone blind. The
/// \* condition is read at the commit, so a close whose condition came back before the commit is not counted (closedOff).
/// NoBlindTombstone == [][\A s \in settles' \ settles : (s.kind = "W" /\ s.out = "acc") => s.tx.blind \cap cond = {}]_vars
///
/// \* Boundary invariant named from Warnings.java ("the family sets are the desired open set; warningRows mirrors the
/// \* committed one"): once the sole active leader is settled, what the operator sees is exactly its open set.
/// Settled(L) ==
///   /\ fsm[L] = "Active" /\ view[L] = Current /\ ~flushQ[L] /\ ~dirty[L] /\ ~wInfl[L] /\ WTraffic = {}
///   /\ (Persists => Diff(warned[L], rows[L], held[L]) = {} /\ rows[L] = kv) \* rows = kv: the KV notification has been applied (an
///                                                        \* orphan W tx of the node's previous incarnation can commit after Activate, the mirror lags)
///   /\ \A m \in Nodes \ {L} : fsm[m] = "Dormant"
///
/// \* #3274 promise (warnpub-redesign.md, events row and accepted losses): the operator-facing truth is the READ of the committed
/// \* rows, not the notification stream. MODE "code" states the read and notification properties below; the pre-#3274 modes keep
/// \* their own promise, the published set `shown` (which the "code" mode never changes).
/// \* Quiescent: Settled without its mirror clause, which is replaced by "no committed put is still on its way to the node" (pend),
/// \* so a mirror that does not follow a put it has taken is visible (mutant "nomirror").
/// Quiescent(L) ==
///   /\ fsm[L] = "Active" /\ view[L] = Current /\ ~flushQ[L] /\ ~dirty[L] /\ ~wInfl[L] /\ WTraffic = {} /\ ~pend[L]
///   /\ \A m \in Nodes \ {L} : fsm[m] = "Dormant"
///
/// \* code: once the sole leader is quiescent, its read is the committed open set. pre-#3274: what was published is its open set.
/// OpenSetAgreement == IF MODE = "code" THEN Quiescent(kvLeader) => Read(kvLeader) = OpenOf(kv)
///                     ELSE Settled(kvLeader) => shown = warned[kvLeader]
///
/// \* Liveness. Every warning whose condition ended is eventually closed (#2834 / #3085 operator consequence). code: the committed row.
/// WarningsClose == IF MODE = "code" THEN \A s \in Subjects : (kv[s].open /\ s \notin cond) ~> (~kv[s].open \/ s \in cond)
///                  ELSE \A s \in Subjects : (s \in shown /\ s \notin cond) ~> (s \notin shown \/ s \in cond)
///
/// \* Every holding condition is eventually shown (the dual: a stale inherited set must not suppress a raise). code: a condition that
/// \* stays on is eventually an open committed row, independent of any settlement.
/// WarningsShow == IF MODE = "code" THEN \A s \in Subjects : (s \in cond) ~> (kv[s].open \/ s \notin cond)
///                 ELSE \A s \in Subjects : (s \in cond) ~> (s \in shown \/ s \notin cond)
///
/// \* Every raised warning is eventually persisted or cleared. code: no node's read keeps answering a row that is not committed open.
/// PersistedOrCleared == IF MODE = "code" THEN \A n \in Nodes, s \in Subjects : (s \in Read(n)) ~> (kv[s].open \/ s \notin Read(n))
///                       ELSE \A s \in Subjects : (s \in shown) ~> (kv[s].open \/ s \notin shown)
///
/// \* Notifications, safety only (#3274: no completeness under a lost settlement, a deposed writer or a rebuild; those are the
/// \* design's accepted losses). Every notification names a committed transition.
/// NoPhantom == \A x \in notes : [c |-> x.c, s |-> x.s, up |-> x.up] \in trans
///
/// \* At most one notification per committed transition.
/// NoDoubleEmit == \A x \in notes : ~x.dup
///
/// \* A transition whose writer handled its accepted settlement was notified (Settle, not DropSettle or LoseSettle: the node is
/// \* Active in the settlement's incarnation and no fault was spent).
/// CompleteWhenSettled ==
///   [][\A s \in settles \ settles' :
///        (s.kind = "W" /\ s.out = "acc" /\ fsm[s.node] = "Active" /\ s.inc = inc[s.node] /\ faultBudget' = faultBudget)
///            => \A t \in trans : (t.c = s.c /\ ~(STAMP \in {"fixed", "nostamp", "ge"} /\ rows[s.node][t.s].rv > s.wrep[t.s].rv))
///                                  => \E x \in notes' : x.c = t.c /\ x.s = t.s /\ x.up = t.up]_vars
///
/// \* (#3203: a transition rule E drops because the writer's mirror holds a newer row (Warnings.superseded, at `>`) is exempt above:
/// \* that settlement is late by construction, its notification is the accepted loss. The exemption is the `>` of the fix, so
/// \* mutant "ge" still has to notify a write its mirror holds at the same revision, and goes red.)
///
/// \* #3203: the operator view never regresses to an older entry of a row: every delivery carries a revision at least the one shown.
/// ViewNoRegress == [][\A x \in Subjects : opv'[x].rv >= opv[x].rv]_vars
///
/// \* #3203: a late opener is never delivered after a close of a higher revision of the same key was delivered (the
/// \* successor's close reached the operator, then the deposed node's older opener re-opened it). An opener delivered while
/// \* a newer close is committed but not yet delivered is in order: the close follows it and the view ends closed.
/// NoLateReopen == [][\A x \in Subjects : (opv'[x] # opv[x] /\ opv'[x].up) => ~(opv[x].cl > opv'[x].rv)]_vars
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY AcceptedOnlyForLeader
/// PROPERTY NoSubmitWhileSuspended
/// INVARIANT AtMostOneLiveLeader
/// PROPERTY RefusedWRedrafts
/// PROPERTY NoBlindTombstone
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY AcceptedOnlyForLeader
/// PROPERTY NoSubmitWhileSuspended
/// INVARIANT AtMostOneLiveLeader
/// PROPERTY RefusedWRedrafts
/// PROPERTY NoBlindTombstone
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY AcceptedOnlyForLeader
/// PROPERTY NoSubmitWhileSuspended
/// INVARIANT AtMostOneLiveLeader
/// PROPERTY RefusedWRedrafts
/// PROPERTY NoBlindTombstone
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:mut_noruleo_resume
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = TRUE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "noruleo"
/// CONSTANT RESUME = TRUE
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = TRUE
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY NoBlindTombstone
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {}
/// CONSTANT EventFam = {}
/// PROPERTY AcceptedOnlyForLeader
/// ```
///
/// ```tlacfg Fsm2Leader:mut_noinherit_blind
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY NoBlindTombstone
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY PersistedOrCleared
/// ```
///
/// ```tlacfg Fsm2Leader:code_nolate_notify
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT NoPhantom
/// INVARIANT NoDoubleEmit
/// PROPERTY CompleteWhenSettled
/// ```
///
/// ```tlacfg Fsm2Leader:mut_nodeposed_close
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "nodeposed"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = FALSE
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY CompleteWhenSettled
/// ```
///
/// ```tlacfg Fsm2Leader:mut_noruleo_blind
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "noruleo"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = FALSE
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY NoBlindTombstone
/// ```
///
/// ```tlacfg Fsm2Leader:mut_noruleE_notify
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "noruleE"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = FALSE
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY CompleteWhenSettled
/// ```
///
/// ```tlacfg Fsm2Leader:mut_emitonput_notify
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "emitonput"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = FALSE
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT NoDoubleEmit
/// ```
///
/// ```tlacfg Fsm2Leader:mut_nomirror_agree
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "nomirror"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = FALSE
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:mut_norevision_agree
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "norevision"
/// CONSTANT RESUME = FALSE
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxCond = 3
/// CONSTANT MaxFault = 1
/// CONSTANT GOALS = FALSE
/// CONSTANT LATE = FALSE
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
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
/// CONSTANT STAMP = "off"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:late_old_view
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
/// CONSTANT STAMP = "old"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY ViewNoRegress
/// ```
///
/// ```tlacfg Fsm2Leader:late_old_reopen
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
/// CONSTANT STAMP = "old"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY NoLateReopen
/// ```
///
/// ```tlacfg Fsm2Leader:late_oldput_view
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
/// CONSTANT STAMP = "old"
/// CONSTANT PUTFIRST = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY ViewNoRegress
/// ```
///
/// ```tlacfg Fsm2Leader:late_fixed_put
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
/// CONSTANT STAMP = "fixed"
/// CONSTANT PUTFIRST = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY ViewNoRegress
/// PROPERTY NoLateReopen
/// INVARIANT NoPhantom
/// INVARIANT NoDoubleEmit
/// PROPERTY CompleteWhenSettled
/// ```
///
/// ```tlacfg Fsm2Leader:late_fixed_view
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
/// CONSTANT STAMP = "fixed"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY ViewNoRegress
/// ```
///
/// ```tlacfg Fsm2Leader:late_ge_complete
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
/// CONSTANT STAMP = "ge"
/// CONSTANT PUTFIRST = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY CompleteWhenSettled
/// ```
///
/// ```tlacfg Fsm2Leader:late_nostamp_put
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
/// CONSTANT STAMP = "nostamp"
/// CONSTANT PUTFIRST = TRUE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY ViewNoRegress
/// PROPERTY NoLateReopen
/// ```
///
/// ```tlacfg Fsm2Leader:nolate_old_view
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
/// CONSTANT STAMP = "old"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY ViewNoRegress
/// PROPERTY NoLateReopen
/// ```
///
/// ```tlacfg Fsm2Leader:late_fixed_reopen
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
/// CONSTANT STAMP = "fixed"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY NoLateReopen
/// ```
///
/// ```tlacfg Fsm2Leader:late_old_agree
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
/// CONSTANT STAMP = "old"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
/// ```
///
/// ```tlacfg Fsm2Leader:late_cand_view
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
/// CONSTANT STAMP = "cand"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY ViewNoRegress
/// ```
///
/// ```tlacfg Fsm2Leader:late_cand_reopen
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
/// CONSTANT STAMP = "cand"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// PROPERTY NoLateReopen
/// ```
///
/// ```tlacfg Fsm2Leader:late_cand_notify
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
/// CONSTANT STAMP = "cand"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT NoPhantom
/// INVARIANT NoDoubleEmit
/// PROPERTY CompleteWhenSettled
/// ```
///
/// ```tlacfg Fsm2Leader:late_cand_agree
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
/// CONSTANT STAMP = "cand"
/// CONSTANT PUTFIRST = FALSE
/// CONSTANT Subjects = {e, l}
/// CONSTANT EventFam = {e}
/// INVARIANT OpenSetAgreement
/// ```
package org.pragmatica.aether.deployment.cluster.fsm2;
