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
/// (* earlier accepted settlement is still in transit (KvApply). That exclusion is read only when STAMP # "off": the *)
/// (* stamp modes extend the base spec conservatively, so at STAMP = "off" LATE is dead as it was before #3203       *)
/// (* (LATE = TRUE and FALSE reach the base states; the code_nolate_* and LATE = FALSE mut_* rows keep their base   *)
/// (* counts, pinned by pinned-counts.tsv beside models.tsv). Reading it at "off" excluded the ABA traces, so     *)
/// (* the CAS controls mut_nocas_agree and mut_norevision_agree passed (PR #3443 round g).                       *)
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
/// (* The Java names cited for rule E at the settle and for "old"/"fixed"/"ge"/"nostamp" (Warnings.notified,          *)
/// (* Warnings.superseded, EffectTranslator.ordered and newestRevision) are the sites at 34c4517a15; ruling B deleted   *)
/// (* them. The code's rule E is Warnings.committedNotice, called by ClusterEventAggregator.onFsmWarningPut.           *)
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
/// \* "cand" (ruling B): the committed emitter publishes; rule E at the settle does not. Rule E is a LIVE-apply rule (CTO ruling
/// \* 2026-11-20, refines B): a committed opening put publishes once, at KvApply, on the node applying it live, above its adoption
/// \* point; rows a node adopts (Adopt: rebuild, snapshot install, inheritance) are installed silently and never re-raised.
/// CommitPub == STAMP = "cand" /\ MODE = "code" /\ MUT # "noruleE"
///
/// \* The operator view after delivering the committed transitions tr of replacement rows f.
/// View(tr, f) == [x \in Subjects |-> IF x \in tr THEN [up |-> f[x].open, rv |-> f[x].rv,
///                                                    cl |-> IF f[x].open THEN opv[x].cl ELSE Max(opv[x].cl, f[x].rv)]
///                                   ELSE opv[x]]
///
/// \* How a mirror takes rows f (an accepted write's replacements, a refusal's values, a put): the code overlays them
/// \* (Warnings.landed / indexRow); "cand" takes a row only if its revision is above the one the mirror holds. Mutant "nogate"
/// \* drops that gate (Warnings.newer always true), so a late settle of an older row overlays a newer one (MirrorNoRegress).
/// Mirror(r, f) == IF STAMP = "cand" /\ MUT # "nogate" THEN [s \in Subjects |-> IF s \in DOMAIN f /\ f[s].rv > r[s].rv THEN f[s] ELSE r[s]]
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
///          \* LATE = FALSE, STAMP # "off": no W commit over a row whose earlier accepted settlement is still in transit (#3203)
///          early == \E st \in settles : st.kind = "W" /\ st.out = "acc" /\ DOMAIN st.wrep \cap DOMAIN tx.wrep # {}
///          c == IF okW THEN ncommit + 1 ELSE 0
///          st == [kind |-> tx.kind, node |-> tx.node, inc |-> tx.inc, out |-> IF ok THEN "acc" ELSE "ref",
///                 wrep |-> tx.wrep, seen |-> [s \in DOMAIN tx.wrep |-> kv[s]], lv |-> Current,
///                 cov |-> IF tx.kind = "W" THEN tx.cov ELSE 0, tx |-> tx, c |-> c]
///      IN /\ LATE \/ STAMP = "off" \/ ~okW \/ ~early
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
/// \* Environment (#3203, rule E as a live-apply rule): node n ADOPTS the committed rows up to the current index without applying them
/// \* live: a snapshot install and the notification replay after it (KVStore.restoreSnapshot / replayNotifications; the committed
/// \* emitter drops a replayed put, ClusterEventAggregator.emitCommitted reading KVStore.isReplaying), a Rebuild's inherit, an
/// \* inherited open. Its mirror side is Activate (inherit) and OnPut (the whole-set sync); Adopt is its publication side: under
/// \* the code it publishes nothing (a stutter). Mutant "adoptpub" (a node publishing every committed put it takes, an
/// \* adopted one included, as the test harness did at d008c2f393) re-publishes the newest committed transition of every open
/// \* row. The operator view (opv) is not advanced by it: OnePubPerOpening reads the stream (notes) only.
/// Adopt(n) ==
///   /\ ncommit > 0
///   /\ notes' = IF MUT = "adoptpub" THEN notes \cup {Note(notes, Last(x).c, x, TRUE) : x \in {s \in OpenOf(kv) : \E t \in trans : t.s = s}}
///               ELSE notes
///   /\ UNCHANGED <<ovars, trans, ncommit, pend, kvW, closedOff, term, kvLeader, elected, lkPend, kv, kvDesired, target, observed, cond,
///                  shown, fsm, inc, view, warned, rows, dirty, flushQ, wInfl, gInfl, gIntent, fired, held, lnd, net, settles,
///                  condBudget, faultBudget, redo>>
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
///   \/ \E n \in Nodes : Activate(n) \/ Deactivate(n) \/ Crash(n) \/ Elect(n) \/ SeeLeader(n) \/ Adopt(n)
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
/// \* #3203 (rule E, live apply): at most one publication per opening index, across all nodes and lives: an opener committed at
/// \* index c is published by its live apply only, never again by a node that adopts the row (Adopt; mutant "adoptpub" goes red).
/// OnePubPerOpening == \A x \in notes : x.up => ~x.dup
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
/// \* #3203 A3: while a node stays Active, its mirror's revision of a row never decreases: a late settle, a refusal or a put
/// \* carrying an older row is not taken (Warnings.indexRow, the `>` of Warnings.newer). Activate and Discard rebuild the mirror.
/// MirrorNoRegress == [][\A n \in Nodes, x \in Subjects : (fsm[n] = "Active" /\ fsm'[n] = "Active") => rows'[n][x].rv >= rows[n][x].rv]_vars
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
/// ```tlacfg Fsm2Leader:late_cand_adopt
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
/// INVARIANT OnePubPerOpening
/// INVARIANT NoPhantom
/// ```
///
/// ```tlacfg Fsm2Leader:late_adoptpub_adopt
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "adoptpub"
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
/// INVARIANT OnePubPerOpening
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
///
/// ```tlacfg Fsm2Leader:late_cand_mirror
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
/// PROPERTY MirrorNoRegress
/// ```
///
/// ```tlacfg Fsm2Leader:late_nogate_mirror
/// CONSTANT MODE = "code"
/// CONSTANT A2834 = FALSE
/// CONSTANT FENCE = TRUE
/// CONSTANT MUT = "nogate"
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
/// PROPERTY MirrorNoRegress
/// ```
///
/// **TLA+ model of the directive receipt** (`DirectiveReceipt`, #3828). The skeleton and the configs are below; the action fragments
/// sit on the methods that carry the behaviour today: `Directory#directiveMutation` (Issue), `DepartureFlow#workerRetirement`
/// (Retire) and `DepartureFlow#admittedRewrite` (Rewrite, which is this commit's code as it stands). **The code at this commit is
/// FACT = "none"** (config `base`, which fails). Every other FACT value and the receipt arms of Issue and Retire describe code that
/// does not exist yet (the redesign of #3828) or was rejected on the way to it; the lanes that write the redesign move those two
/// fragments to `Directory.issue` and `retirementUnit`.
///
/// ```tla DirectiveReceipt:head
/// (* One node id W: its activation directive, the issue receipt proposed for #3828 and its departure record in the KV; the   *)
/// (* leader's join table for W, reduced to the rows that write or withhold a directive; the applier's expected-value rule. *)
/// (* Bounds, not proofs: TLC explores every state within a config's constants and says nothing about larger ones, or      *)
/// (* about what the model abstracts.                                                                                      *)
/// (*                                                                                                                      *)
/// (* FACT selects the fact that decides a directive write:                                                                *)
/// (* "none"      the code at this commit: no fact. A join whose mirror shows no directive writes one (Joins.directive).   *)
/// (* "local"     a leader-local mark taken when the leader sees the directive removed. Not this commit's code.           *)
/// (* "record"    the same mark, taken only for a node with no DEPARTED record that the leader did not see retired        *)
/// (*             (PR #3834, unmerged: Joins.markRemoved, isLive, retire). Not this commit's code.                         *)
/// (* "unguarded" the receipt of "receipt", written and mirrored, but the draft's only expected value is the directive    *)
/// (*             key's (the expectation of Directory.directiveMutation today). Not this commit's code.                   *)
/// (* "arm2"      the receipt names a process token, and a leader whose own registry holds another token for the id       *)
/// (*             issues again, replacing the receipt (the redesign's first draft). Not this commit's code.               *)
/// (* "receipt"   the redesign: a receipt per node id that names no process, written in the directive's own transaction   *)
/// (*             expecting the receipt absent, removed only by the retirement unit. Not this commit's code.              *)
/// (* MUT is a mutant of FACT = "receipt": "blind" (the applier ignores expected values), "nogate" (the leader does not   *)
/// (* read its receipt mirror before it drafts), "nor3" (the sweep retires a departed worker only with its directive, as  *)
/// (* DepartureFlow.workerRetirement does today), "drop" (a withheld join is dropped instead of staying pending),         *)
/// (* "nowitness" (the retirement unit removes what the mirror holds and witnesses nothing absent).                       *)
/// (*                                                                                                                      *)
/// (* The applier (KVStore, not this package's code; assumed): a transaction commits only if its term is the leader term   *)
/// (* and every expected value equals the committed one; it applies all of its mutations or none (Accepts, Commit).        *)
/// (* A leader's token for the id is its own registry's: the first process it saw under the id, never replaced (0: none   *)
/// (* yet). Only "arm2" compares it; "local" and "record" store it in the mark.                                           *)
/// (*                                                                                                                      *)
/// (* Abstractions. One node id of role ROLE. The leader is the current one only: a deposed leader acts through the        *)
/// (* transactions it already submitted, which fail the leader check. Rebuild is one step with the term change and reads  *)
/// (* the committed KV. A notification is one key at a time, in commit order, record before directive before receipt      *)
/// (* within one transaction. A draft counts as in flight until its last notification is applied, so the leader does not  *)
/// (* act between an accepted settlement and its own puts [not read: whether the code can]. The DEPARTED write is one     *)
/// (* step with the observed departure (its loss and resubmission are DepartureFlow's own). J8 (a different process       *)
/// (* under an id that has a record is refused) is not modelled: the model issues there, a superset. The record carries   *)
/// (* no process and no epoch. A termination is one step that reads the store (TerminationRetirement); placement COMPLETE *)
/// (* and the guards on the cluster config and the community are not modelled. A removed directive halts the WORKER       *)
/// (* process that holds it, and a process that starts while a directive stands holds it (OwnDirectiveRetirement, taken   *)
/// (* from the design memo's reading, not read for this model); a core does not halt.                                    *)
/// EXTENDS Integers, Sequences, FiniteSets
///
/// CONSTANTS FACT, MUT,
///           ROLE,        \* "worker" or "core"
///           ORDERED,     \* TRUE: the leader handles a join input after the notifications of everything committed before it
///           MaxProc,     \* process incarnations under the id
///           MaxTerm,     \* leader terms
///           MaxOps,      \* operator removals of the directive
///           MaxForeign,  \* directive puts by another writer (no receipt)
///           MaxDepart,   \* observed departures
///           MaxKill,     \* source-confirmed terminations
///           MaxLose,     \* transactions that are lost and never commit
///           MaxHold      \* transactions reported failed to the leader that may still commit (the late commit)
///
/// VARIABLES kv,       \* committed: [dir: the directive is present, rcpt: NoR or the receipt (a token under "arm2", else 0),
///                     \*             rec: "none" | "D" | "A"]
///           dirOf,    \* history: the process the committed directive was issued to, as the issuing leader's registry
///                     \*          named it (0: no directive, or another writer's put)
///           cur,      \* the id's current process
///           up,       \* the current process runs (FALSE: it stopped; the id may be terminated)
///           owed,     \* membership owes the current leader a join input
///           asked,    \* history: a join input was handled and neither answered by a directive nor ended
///           term,     \* the leader term
///           txs,      \* submitted transactions that have neither committed nor been refused or lost
///           ntf,      \* committed key changes (and, ORDERED, join inputs) the leader has not handled yet
///           mir,      \* the leader's mirror of kv
///           pend,     \* the leader holds a pending join for the id
///           adm,      \* the leader's token for the id (0: none yet)
///           joined,   \* the leader saw a join that admits the id since Rebuild or the last departure (supersedes a DEPARTED record)
///           mark,     \* FACT "local", "record": the leader-local mark (NoR: none)
///           retired,  \* FACT "record": the leader saw the id's DEPARTED record removed and no join since
///           gone,     \* history: processes whose leader-issued directive was removed since the id's last retirement
///           halted,   \* history: processes that held a directive when it was removed, since the id's last retirement
///           viol,     \* history: which safety statements a committed transaction broke
///           left      \* what is left of each environment budget
///
/// vars == <<kv, dirOf, cur, up, owed, asked, term, txs, ntf, mir, pend, adm, joined, mark, retired, gone, halted, viol, left>>
///
/// NoR == -1                 \* no receipt, no mark, not applicable
/// Any == -2                 \* a transaction with no expected value on the receipt key
/// Tokens == 0..MaxProc
/// Kinds == {"issue", "retire", "admit"}
/// Receipts == FACT \in {"unguarded", "arm2", "receipt"}
///
/// \* "arm2" only: the leader's own token and the receipt's are both known and unequal (Admission.isDifferentProcessFrom, C8).
/// Diff(a, b) == a # 0 /\ b # 0 /\ a # b
///
/// \* Invariant I of the redesign as the leader reads it from its mirror: the id has no receipt. "arm2" adds its second arm.
/// MayIssue(r, a) == r = NoR \/ (FACT = "arm2" /\ Diff(a, r))
///
/// \* m.departed (Indexer.refreshDeparted): a DEPARTED record that no join since has superseded (Departures.isSuperseded).
/// Departed(m, j) == m.rec = "D" /\ ~j
///
/// \* A draft of kind k is waiting to be sent, to be settled, or for its own notifications (Directory.directiveInFlight and
/// \* its siblings). A held transaction was reported failed, so it does not count.
/// InFlight(k) == \/ \E t \in txs : t.kind = k /\ ~t.held /\ t.term = term
///                \/ \E i \in 1..Len(ntf) : ntf[i].src = k
///
/// \* eDir: "present" (removed at its value), "absent" (the key is expected or witnessed absent), "any". eRcpt: a receipt value
/// \* (expected, and replaced or removed), NoR (expected or witnessed absent), Any. eRec: "D", "none" (witnessed absent), "any".
/// Tx(k, tok, proc, eDir, eRcpt, eRec) ==
///   [kind |-> k, term |-> term, held |-> FALSE, tok |-> tok, proc |-> proc, eDir |-> eDir, eRcpt |-> eRcpt, eRec |-> eRec]
///
/// Ev(k, v, src) == [k |-> k, v |-> v, src |-> src]
///
/// \* The applier's rule. Mutant "blind": only the leader check is left.
/// Expected(t) == /\ t.eDir = "any" \/ (t.eDir = "present") = kv.dir
///                /\ t.eRcpt = Any \/ t.eRcpt = kv.rcpt
///                /\ t.eRec = "any" \/ t.eRec = kv.rec
/// Accepts(t) == t.term = term /\ (MUT = "blind" \/ Expected(t))
///
/// \* What an accepted transaction writes, and the notifications it raises, in order. A retirement removes a member only
/// \* when it carries the mutation; a witness writes nothing.
/// Written(t) ==
///   CASE t.kind = "issue" -> [kv EXCEPT !.dir = TRUE, !.rcpt = IF Receipts THEN t.tok ELSE @]
///     [] t.kind = "retire" -> [kv EXCEPT !.rec = "none", !.dir = IF t.eDir = "present" THEN FALSE ELSE @,
///                                        !.rcpt = IF t.eRcpt \in {Any, NoR} THEN @ ELSE NoR]
///     [] t.kind = "admit" -> [kv EXCEPT !.rec = "A"]
/// Events(t) ==
///   CASE t.kind = "issue" -> <<Ev("dir", TRUE, "issue")>> \o (IF Receipts THEN <<Ev("rcpt", t.tok, "issue")>> ELSE <<>>)
///     [] t.kind = "retire" -> <<Ev("rec", "none", "retire")>>
///                             \o (IF t.eDir = "present" THEN <<Ev("dir", FALSE, "retire")>> ELSE <<>>)
///                             \o (IF t.eRcpt \notin {Any, NoR} THEN <<Ev("rcpt", NoR, "retire")>> ELSE <<>>)
///     [] t.kind = "admit" -> <<Ev("rec", "A", "admit")>>
///
/// Init ==
///   /\ kv = [dir |-> FALSE, rcpt |-> NoR, rec |-> "none"] /\ mir = [dir |-> FALSE, rcpt |-> NoR, rec |-> "none"]
///   /\ dirOf = 0 /\ cur = 1 /\ up = TRUE /\ owed = TRUE /\ asked = FALSE /\ term = 1 /\ txs = {} /\ ntf = <<>>
///   /\ pend = FALSE /\ adm = 0 /\ joined = FALSE /\ mark = NoR /\ retired = FALSE
///   /\ gone = {} /\ halted = {} /\ viol = {}
///   /\ left = [ops |-> MaxOps, foreign |-> MaxForeign, depart |-> MaxDepart, kill |-> MaxKill, lose |-> MaxLose, hold |-> MaxHold]
///
/// \* ---- The leader's join table for W (Joins.onJoin, evaluateRows, afterTable, directive), without its issuing row ----
///
/// \* A join input of process p handled (Joins.onJoin): the join is pending until a row ends it. The leader's token for the
/// \* id is the first one its registry saw. The join ends `retired` (PR #3834).
/// Deliver(p) ==
///   /\ adm' = (IF adm = 0 THEN p ELSE adm) /\ pend' = TRUE /\ asked' = TRUE /\ retired' = FALSE
///
/// \* The environment sends a join input for a process that ran under the id, the current one or an earlier one (a
/// \* re-drive). ORDERED: it takes its place among the notifications (a join built from a committed put, DeploymentWiring);
/// \* one at a time. Otherwise the leader handles it at once, whatever its mirror has not applied yet.
/// Join(p) ==
///   /\ owed' = FALSE
///   /\ IF ORDERED
///        THEN /\ \A i \in 1..Len(ntf) : ntf[i].k # "join"
///             /\ ntf' = Append(ntf, Ev("join", p, "env"))
///             /\ UNCHANGED <<adm, pend, asked, retired>>
///        ELSE Deliver(p) /\ UNCHANGED ntf
///   /\ UNCHANGED <<kv, dirOf, cur, up, term, txs, mir, joined, mark, gone, halted, viol, left>>
///
/// \* J3: a DEPARTED record and a directive: the same process is admitted again, ADMITTED expecting the record and
/// \* witnessing the directive (Joins.readmit). J7 (a DEPARTED record and no directive: the join stays pending) changes nothing.
/// Readmit ==
///   /\ pend /\ mir.rec = "D" /\ mir.dir /\ (~joined \/ ~InFlight("admit"))
///   /\ joined' = TRUE
///   /\ txs' = IF InFlight("admit") THEN txs ELSE txs \cup {Tx("admit", NoR, 0, "present", Any, "D")}
///   /\ UNCHANGED <<kv, dirOf, cur, up, owed, asked, term, ntf, mir, pend, adm, mark, retired, gone, halted, viol, left>>
///
/// \* J5/J6 with a directive in the mirror: the observed directive ends the join (#3603).
/// Seen ==
///   /\ pend /\ mir.rec # "D" /\ mir.dir
///   /\ pend' = FALSE /\ joined' = TRUE
///   /\ UNCHANGED <<kv, dirOf, cur, up, owed, asked, term, txs, ntf, mir, adm, mark, retired, gone, halted, viol, left>>
///
/// \* The leader may draft a directive for the pending join. FACT "none": always (Joins.assign today).
/// MayAssign == CASE FACT = "none" -> TRUE
///                [] FACT \in {"local", "record"} -> mark = NoR
///                [] OTHER -> MUT = "nogate" \/ MayIssue(mir.rcpt, adm)
///
/// \* J5/J6 with no directive: the join is tracked while a directive draft is in flight; a join the fact withholds stays
/// \* pending under a receipt (so the pass after a retirement finds it) and is dropped by PR #3834 and by mutant "drop".
/// Track ==
///   /\ pend /\ mir.rec # "D" /\ ~mir.dir /\ InFlight("issue") /\ ~joined
///   /\ joined' = TRUE
///   /\ UNCHANGED <<kv, dirOf, cur, up, owed, asked, term, txs, ntf, mir, pend, adm, mark, retired, gone, halted, viol, left>>
/// Withhold ==
///   /\ pend /\ mir.rec # "D" /\ ~mir.dir /\ ~InFlight("issue") /\ ~MayAssign
///   /\ joined' = TRUE /\ pend' = (Receipts /\ MUT # "drop")
///   /\ UNCHANGED <<kv, dirOf, cur, up, owed, asked, term, txs, ntf, mir, adm, mark, retired, gone, halted, viol, left>>
///
/// \* The receipt mutation of an issue draft (the redesign's one builder): the value it puts and the value it expects.
/// \* "receipt": puts a receipt that names no process, expects the receipt absent, and has no other form. "arm2": puts the
/// \* leader's token, and expects the receipt it replaces when its mirror holds one of another token. "unguarded": no
/// \* expected value. The other FACTs have no receipt.
/// IssuePuts == IF FACT = "arm2" THEN adm ELSE 0
/// IssueExpects == CASE FACT = "receipt" -> NoR
///                   [] FACT = "arm2" -> IF mir.rcpt # NoR /\ Diff(adm, mir.rcpt) THEN mir.rcpt ELSE NoR
///                   [] OTHER -> Any
/// ```
///
/// ```tla DirectiveReceipt:tail
/// \* ---- The leader handles the head of its queue: one committed key change (Indexer, the Put and Remove handlers) ----
/// \* A removed directive of a departed node gets R-4.3.3 (Directory.onDirectiveRemove): the retirement, built from the mirror.
/// \* FACT "local": any other removal of the directive marks the id. FACT "record": only for a node with no DEPARTED record
/// \* whose retirement the leader did not see; the removal of a DEPARTED record ends the mark and sets `retired`.
/// \* With a receipt the handlers only mirror; nothing is decided at a notification.
/// Notify ==
///   /\ ntf # <<>> /\ Head(ntf).k # "join"
///   /\ LET e == Head(ntf)
///          m2 == [mir EXCEPT ![e.k] = e.v]
///          removed == e.k = "dir" /\ ~e.v
///          r433 == removed /\ Departed(m2, joined)
///          recGone == e.k = "rec" /\ e.v = "none" /\ mir.rec = "D" /\ FACT = "record"
///      IN /\ mir' = m2 /\ ntf' = Tail(ntf)
///         /\ txs' = IF r433 /\ ~InFlight("retire") THEN txs \cup {Retirement(m2)} ELSE txs
///         /\ mark' = CASE removed /\ ~r433 /\ FACT = "local" -> adm
///                      [] removed /\ ~r433 /\ FACT = "record" /\ ~retired /\ m2.rec # "D" -> adm
///                      [] recGone -> NoR
///                      [] OTHER -> mark
///         /\ retired' = (retired \/ recGone)
///   /\ UNCHANGED <<kv, dirOf, cur, up, owed, asked, term, pend, adm, joined, gone, halted, viol, left>>
///
/// \* ... or one join input (ORDERED).
/// JoinHandled ==
///   /\ ntf # <<>> /\ Head(ntf).k = "join"
///   /\ Deliver(Head(ntf).v) /\ ntf' = Tail(ntf)
///   /\ UNCHANGED <<kv, dirOf, cur, up, owed, term, txs, mir, joined, mark, gone, halted, viol, left>>
///
/// \* ---- The applier ----
/// \* A transaction commits whole or is refused whole. The history. An accepted issue names the process it was drafted
/// \* for (the issuing leader's token for the id) as the directive's.
/// \* It breaks "sticks" when a leader-issued directive of the id was removed since the id's last retirement, "reissue" when
/// \* that directive was this process's own, and "halted" when the current process held a directive at its removal. An
/// \* accepted removal of the DEPARTED record is the id's retirement: the history sets start again; it breaks "split" when it
/// \* leaves a receipt. A retirement that removes a directive halts its holder, which may return only through a fresh join
/// \* (`owed`).
/// Commit(t) ==
///   /\ t \in txs /\ txs' = txs \ {t}
///   /\ IF Accepts(t)
///        THEN LET retires == t.kind = "retire" /\ kv.rec = "D"
///                 unholds == t.kind = "retire" /\ t.eDir = "present" /\ kv.dir
///             IN /\ kv' = Written(t) /\ ntf' = ntf \o Events(t)
///                /\ dirOf' = CASE t.kind = "issue" -> t.proc
///                              [] t.kind = "retire" /\ t.eDir = "present" -> 0
///                              [] OTHER -> dirOf
///                /\ viol' = viol \cup (IF t.kind = "issue" /\ gone # {} THEN {"sticks"} ELSE {})
///                                \cup (IF t.kind = "issue" /\ t.proc \in gone THEN {"reissue"} ELSE {})
///                                \cup (IF t.kind = "issue" /\ cur \in halted THEN {"halted"} ELSE {})
///                                \cup (IF retires /\ Written(t).rcpt # NoR THEN {"split"} ELSE {})
///                /\ gone' = IF retires THEN {} ELSE gone
///                /\ halted' = IF retires THEN {} ELSE halted
///                /\ owed' = (owed \/ unholds)
///                /\ asked' = (asked /\ t.kind # "issue" /\ ~unholds)
///        ELSE UNCHANGED <<kv, ntf, dirOf, viol, gone, halted, owed, asked>>
///   /\ UNCHANGED <<cur, up, term, mir, pend, adm, joined, mark, retired, left>>
///
/// \* A transaction is lost and never commits; the leader derives it again from its mirror.
/// Lose(t) ==
///   /\ t \in txs /\ left.lose > 0
///   /\ txs' = txs \ {t} /\ left' = [left EXCEPT !.lose = @ - 1]
///   /\ UNCHANGED <<kv, dirOf, cur, up, owed, asked, term, ntf, mir, pend, adm, joined, mark, retired, gone, halted, viol>>
///
/// \* A transaction is reported failed to the leader and may still commit later (the harness policy of FsmHarness; the
/// \* outbox's own claim is that an expired draft cannot). The leader no longer counts it as in flight.
/// Hold(t) ==
///   /\ t \in txs /\ ~t.held /\ left.hold > 0
///   /\ txs' = (txs \ {t}) \cup {[t EXCEPT !.held = TRUE]} /\ left' = [left EXCEPT !.hold = @ - 1]
///   /\ UNCHANGED <<kv, dirOf, cur, up, owed, asked, term, ntf, mir, pend, adm, joined, mark, retired, gone, halted, viol>>
///
/// \* ---- The environment ----
/// \* An operator (any writer that is not the leader) removes the directive with a bare transaction: nothing else is written.
/// \* A WORKER process that holds the directive halts; its join is over.
/// OpRemove ==
///   /\ left.ops > 0 /\ kv.dir
///   /\ kv' = [kv EXCEPT !.dir = FALSE] /\ ntf' = Append(ntf, Ev("dir", FALSE, "env"))
///   /\ gone' = gone \cup (IF dirOf # 0 THEN {dirOf} ELSE {})
///   /\ halted' = IF ROLE = "worker" THEN halted \cup {cur} ELSE halted
///   /\ dirOf' = 0 /\ asked' = FALSE /\ left' = [left EXCEPT !.ops = @ - 1]
///   /\ UNCHANGED <<cur, up, owed, term, txs, mir, pend, adm, joined, mark, retired, viol>>
///
/// \* Another writer puts the directive (a test, an operator): no receipt comes with it, and by ruling nothing adds one.
/// ForeignPut ==
///   /\ left.foreign > 0 /\ ~kv.dir
///   /\ kv' = [kv EXCEPT !.dir = TRUE] /\ ntf' = Append(ntf, Ev("dir", TRUE, "env"))
///   /\ dirOf' = 0 /\ asked' = FALSE /\ left' = [left EXCEPT !.foreign = @ - 1]
///   /\ UNCHANGED <<cur, up, owed, term, txs, mir, pend, adm, joined, mark, retired, gone, halted, viol>>
///
/// \* The leader observes the id's departure (true or inferred): the DEPARTED record, whatever the directive state, and the
/// \* pending join is dropped (DepartureFlow.depart, dropPendingJoin).
/// Depart ==
///   /\ left.depart > 0 /\ kv.rec # "D"
///   /\ kv' = [kv EXCEPT !.rec = "D"] /\ ntf' = Append(ntf, Ev("rec", "D", "env"))
///   /\ pend' = FALSE /\ joined' = FALSE /\ asked' = FALSE /\ left' = [left EXCEPT !.depart = @ - 1]
///   /\ UNCHANGED <<dirOf, cur, up, owed, term, txs, mir, adm, mark, retired, gone, halted, viol>>
///
/// \* A source-confirmed termination of the id (TerminationRetirement): unless the record is ADMITTED, the unit over the
/// \* store, in one step: every member the store holds is removed. Only for a process that stopped: a live node is never
/// \* terminated (the release table of the design memo, exit E2). It ends the id's history as a retirement does.
/// Terminate ==
///   /\ left.kill > 0 /\ ~up /\ kv.rec # "A" /\ (kv.rec = "D" \/ kv.dir \/ kv.rcpt # NoR)
///   /\ kv' = [dir |-> FALSE, rcpt |-> NoR, rec |-> "none"]
///   /\ ntf' = ntf \o (IF kv.rec = "D" THEN <<Ev("rec", "none", "env")>> ELSE <<>>)
///                 \o (IF kv.dir THEN <<Ev("dir", FALSE, "env")>> ELSE <<>>)
///                 \o (IF kv.rcpt # NoR THEN <<Ev("rcpt", NoR, "env")>> ELSE <<>>)
///   /\ dirOf' = 0 /\ gone' = {} /\ halted' = {} /\ asked' = FALSE /\ left' = [left EXCEPT !.kill = @ - 1]
///   /\ UNCHANGED <<cur, up, owed, term, txs, mir, pend, adm, joined, mark, retired, viol>>
///
/// \* The current process stops (it exits, or its host goes). Whether a leader records that is Depart's.
/// Stop ==
///   /\ up /\ up' = FALSE
///   /\ UNCHANGED <<kv, dirOf, cur, owed, asked, term, txs, ntf, mir, pend, adm, joined, mark, retired, gone, halted, viol, left>>
///
/// \* Another process starts under the id.
/// Restart ==
///   /\ cur < MaxProc /\ cur' = cur + 1 /\ up' = TRUE /\ owed' = TRUE /\ asked' = FALSE
///   /\ UNCHANGED <<kv, dirOf, term, txs, ntf, mir, pend, adm, joined, mark, retired, gone, halted, viol, left>>
///
/// \* A new leader: Rebuild mirrors the committed KV; every leader-local fact starts empty (Lifecycle.rebuild), its token
/// \* registry included. The deposed leader's transactions fail the leader check from here on (Accepts), so they are
/// \* dropped: a reduction, not a claim.
/// LeaderChange ==
///   /\ term < MaxTerm /\ term' = term + 1 /\ mir' = kv /\ ntf' = <<>> /\ txs' = {}
///   /\ pend' = FALSE /\ adm' = 0 /\ joined' = FALSE /\ mark' = NoR /\ retired' = FALSE
///   /\ owed' = TRUE /\ asked' = FALSE
///   /\ UNCHANGED <<kv, dirOf, cur, up, gone, halted, viol, left>>
///
/// JoinInput == \E p \in 1..cur : Join(p)
/// Applier == \E t \in txs : Commit(t)
/// Lost == \E t \in txs : Lose(t)
/// Held == \E t \in txs : Hold(t)
///
/// Next ==
///   \/ JoinInput \/ JoinHandled \/ Readmit \/ Rewrite \/ Seen \/ Track \/ Withhold \/ Issue \/ Retire \/ Notify
///   \/ Applier \/ Lost \/ Held
///   \/ OpRemove \/ ForeignPut \/ Depart \/ Terminate \/ Stop \/ Restart \/ LeaderChange
///
/// \* Fairness: every leader action, the handling of its queue, the applier per transaction kind, and the one join input
/// \* membership owes a leader. None on a repeated join input or on the other environment actions (bounded anyway).
/// Fairness ==
///   /\ WF_vars(owed /\ JoinInput) /\ WF_vars(JoinHandled)
///   /\ WF_vars(Readmit) /\ WF_vars(Rewrite) /\ WF_vars(Seen) /\ WF_vars(Track) /\ WF_vars(Withhold)
///   /\ WF_vars(Issue) /\ WF_vars(Retire) /\ WF_vars(Notify)
///   /\ \A k \in Kinds : WF_vars(\E t \in txs : t.kind = k /\ Commit(t))
///
/// Spec == Init /\ [][Next]_vars /\ Fairness
///
/// TypeOK ==
///   /\ kv.dir \in BOOLEAN /\ kv.rcpt \in {NoR} \cup Tokens /\ kv.rec \in {"none", "D", "A"}
///   /\ mir.dir \in BOOLEAN /\ mir.rcpt \in {NoR} \cup Tokens /\ mir.rec \in {"none", "D", "A"}
///   /\ (~Receipts => kv.rcpt = NoR) /\ (FACT \in {"unguarded", "receipt"} => kv.rcpt \in {NoR, 0})
///   /\ dirOf \in 0..MaxProc /\ cur \in 1..MaxProc /\ term \in 1..MaxTerm
///   /\ adm \in Tokens /\ mark \in {NoR} \cup Tokens
///   /\ up \in BOOLEAN /\ owed \in BOOLEAN /\ asked \in BOOLEAN /\ pend \in BOOLEAN /\ joined \in BOOLEAN /\ retired \in BOOLEAN
///   /\ \A t \in txs : /\ t.kind \in Kinds /\ t.term = term /\ t.held \in BOOLEAN /\ t.proc \in 0..MaxProc
///                     /\ t.eDir \in {"absent", "present", "any"} /\ t.eRcpt \in {Any, NoR} \cup Tokens
///                     /\ t.eRec \in {"D", "any"}
///   /\ \A i \in 1..Len(ntf) : ntf[i].k \in {"dir", "rcpt", "rec", "join"}
///   /\ gone \subseteq 1..MaxProc /\ halted \subseteq 1..MaxProc /\ viol \subseteq {"sticks", "reissue", "halted", "split"}
///   /\ \A b \in DOMAIN left : left[b] \in Nat
///
/// \* Safety, per id (S of the memo): no leader issue commits for an id whose leader-issued directive was removed since
/// \* the id's last retirement or termination. `gone` is the history variable. A directive another writer put is not
/// \* leader-issued: its removal binds nothing (residual_foreign).
/// Sticks == "sticks" \notin viol
///
/// \* The first draft's weaker S, per process: no leader issue commits for a process whose own leader-issued directive
/// \* was removed since the id's last retirement. Implied by Sticks; "arm2" claimed it and breaks it (arm2_f2).
/// NoReissue == "reissue" \notin viol
///
/// \* The worker's reading: no leader issue commits while the current process is one that held a directive at its removal
/// \* since the id's last retirement (it is halting, and a directive committed now does not cancel the halt). "arm2" breaks
/// \* it for a process that started while its predecessor's directive stood (arm2_n1, finding N1 of the memo); "receipt"
/// \* breaks it only for a directive another writer put (residual_foreign).
/// NoIssueToHalted == "halted" \notin viol
///
/// \* Invariant R of the redesign: an accepted removal of the DEPARTED record leaves no receipt.
/// RetiredWhole == "split" \notin viol
///
/// \* Statement 2 of the redesign: a directive the leader committed has its receipt for as long as it stands.
/// Traced == (Receipts /\ dirOf # 0 /\ kv.dir) => kv.rcpt # NoR
///
/// \* Liveness: a join the leader handled is, from some point on, answered by a directive, or withheld by a standing
/// \* receipt (the id is bound until it is retired), or deferred by the DEPARTED record of an id that has no receipt (J7;
/// \* not this redesign's subject). What may not last: a handled join for an id with no directive, no receipt and no
/// \* DEPARTED record (joined, never directed; or retired), and a DEPARTED record beside a receipt with no directive
/// \* (ruling R3). A process that held a directive at its removal is halting and is owed none until the id is retired; a
/// \* process that stopped is owed none.
/// JoinAnswered == <>[]((asked /\ up /\ cur \notin halted)
///                         => (kv.dir \/ (kv.rcpt # NoR /\ kv.rec # "D") \/ (kv.rec = "D" /\ kv.rcpt = NoR)))
/// ```
///
/// ```tlacfg DirectiveReceipt:common
/// SPECIFICATION Spec
/// INVARIANT TypeOK
/// ```
///
/// ```tlacfg DirectiveReceipt:base
/// CONSTANT FACT = "none"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 1
/// CONSTANT MaxTerm = 1
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 0
/// CONSTANT MaxDepart = 0
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 0
/// CONSTANT MaxHold = 0
/// INVARIANT Sticks
/// ```
///
/// ```tlacfg DirectiveReceipt:local_f3
/// CONSTANT FACT = "local"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 1
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 0
/// CONSTANT MaxDepart = 0
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 0
/// CONSTANT MaxHold = 0
/// INVARIANT Sticks
/// ```
///
/// ```tlacfg DirectiveReceipt:local_ctl
/// CONSTANT FACT = "local"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 1
/// CONSTANT MaxTerm = 1
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 0
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 1
/// CONSTANT MaxHold = 0
/// INVARIANT Sticks
/// ```
///
/// ```tlacfg DirectiveReceipt:record_f1
/// CONSTANT FACT = "record"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 1
/// CONSTANT MaxTerm = 1
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 0
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 1
/// CONSTANT MaxHold = 0
/// INVARIANT Sticks
/// ```
///
/// ```tlacfg DirectiveReceipt:record_ctl
/// CONSTANT FACT = "record"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 1
/// CONSTANT MaxTerm = 1
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 0
/// CONSTANT MaxDepart = 0
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 1
/// CONSTANT MaxHold = 0
/// INVARIANT Sticks
/// ```
///
/// ```tlacfg DirectiveReceipt:unguarded_late
/// CONSTANT FACT = "unguarded"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 1
/// CONSTANT MaxTerm = 1
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 0
/// CONSTANT MaxDepart = 0
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 0
/// CONSTANT MaxHold = 1
/// INVARIANT Sticks
/// ```
///
/// ```tlacfg DirectiveReceipt:unguarded_ctl
/// CONSTANT FACT = "unguarded"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 1
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 0
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 1
/// CONSTANT MaxHold = 0
/// INVARIANT Sticks
/// ```
///
/// ```tlacfg DirectiveReceipt:arm2_f2
/// CONSTANT FACT = "arm2"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 2
/// CONSTANT MaxTerm = 3
/// CONSTANT MaxOps = 2
/// CONSTANT MaxForeign = 0
/// CONSTANT MaxDepart = 0
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 0
/// CONSTANT MaxHold = 0
/// INVARIANT NoReissue
/// ```
///
/// ```tlacfg DirectiveReceipt:arm2_n1
/// CONSTANT FACT = "arm2"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 2
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 0
/// CONSTANT MaxDepart = 0
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 0
/// CONSTANT MaxHold = 0
/// INVARIANT NoIssueToHalted
/// ```
///
/// ```tlacfg DirectiveReceipt:mut_nowitness
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "nowitness"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 1
/// CONSTANT MaxTerm = 1
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 1
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 0
/// CONSTANT MaxHold = 1
/// INVARIANT RetiredWhole
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 2
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxOps = 2
/// CONSTANT MaxForeign = 1
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 1
/// CONSTANT MaxLose = 1
/// CONSTANT MaxHold = 1
/// INVARIANT Sticks
/// INVARIANT Traced
/// INVARIANT RetiredWhole
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_core
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "core"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 2
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxOps = 2
/// CONSTANT MaxForeign = 1
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 1
/// CONSTANT MaxLose = 1
/// CONSTANT MaxHold = 1
/// INVARIANT Sticks
/// INVARIANT Traced
/// INVARIANT RetiredWhole
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_nogate
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "nogate"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 2
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxOps = 2
/// CONSTANT MaxForeign = 1
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 1
/// CONSTANT MaxLose = 1
/// CONSTANT MaxHold = 1
/// INVARIANT Sticks
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_halted
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 2
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxOps = 2
/// CONSTANT MaxForeign = 0
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 1
/// CONSTANT MaxLose = 1
/// CONSTANT MaxHold = 1
/// INVARIANT NoIssueToHalted
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_live
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = TRUE
/// CONSTANT MaxProc = 2
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 1
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 1
/// CONSTANT MaxLose = 1
/// CONSTANT MaxHold = 0
/// PROPERTY JoinAnswered
/// ```
///
/// ```tlacfg DirectiveReceipt:mut_applier
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "blind"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 2
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxOps = 2
/// CONSTANT MaxForeign = 1
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 1
/// CONSTANT MaxLose = 1
/// CONSTANT MaxHold = 1
/// INVARIANT Sticks
/// ```
///
/// ```tlacfg DirectiveReceipt:mut_nor3_live
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "nor3"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = TRUE
/// CONSTANT MaxProc = 2
/// CONSTANT MaxTerm = 1
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 0
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 0
/// CONSTANT MaxHold = 0
/// PROPERTY JoinAnswered
/// ```
///
/// ```tlacfg DirectiveReceipt:mut_drop_live
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "drop"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = TRUE
/// CONSTANT MaxProc = 1
/// CONSTANT MaxTerm = 1
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 0
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 0
/// CONSTANT MaxHold = 0
/// PROPERTY JoinAnswered
/// ```
///
/// ```tlacfg DirectiveReceipt:residual_foreign
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 1
/// CONSTANT MaxTerm = 1
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 1
/// CONSTANT MaxDepart = 0
/// CONSTANT MaxKill = 0
/// CONSTANT MaxLose = 0
/// CONSTANT MaxHold = 0
/// INVARIANT NoIssueToHalted
/// ```
///
/// ```tlacfg DirectiveReceipt:residual_overtake_live
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT ROLE = "worker"
/// CONSTANT ORDERED = FALSE
/// CONSTANT MaxProc = 2
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxOps = 1
/// CONSTANT MaxForeign = 1
/// CONSTANT MaxDepart = 1
/// CONSTANT MaxKill = 1
/// CONSTANT MaxLose = 1
/// CONSTANT MaxHold = 0
/// PROPERTY JoinAnswered
/// ```
package org.pragmatica.aether.deployment.cluster.fsm2;
