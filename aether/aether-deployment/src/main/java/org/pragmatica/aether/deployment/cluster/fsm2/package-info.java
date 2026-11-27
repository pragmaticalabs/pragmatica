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
/// **TLA+ model of the directive receipt** (`DirectiveReceipt`, #3828): the model of section 7 of the redesign's memo, round 10.
/// The skeleton and the configs are below; three action fragments sit on the methods that carry the behaviour:
/// `Directory#directiveMutation` (Issue), `RetirementUnit#retirementUnit` (Retire) and `DepartureFlow#admittedRewrite` (Admit).
/// **The code at this commit is FACT = "none"** (config `base`, which fails): the retirement unit is code, and nothing writes a
/// receipt yet, so the unit finds none and the issue is withheld by nothing. Every other FACT value and the issue's receipt
/// describe code that does not exist yet (the redesign of #3828) or was rejected on the way to it; the lane that writes the
/// issue moves the Issue fragment to `Directory.issue`.
/// against `tools/tla/DirectiveReceipt.writers.tsv` by `tools/tla/writers-check.py`.
///
/// **What the configs cover.** The model with every action on does not finish at these constants (two leaders, `E_max` 1,
/// `H_max` 5). So a config that must FAIL switches actions off freely (`Off`): its counterexample is a behaviour of the whole
/// model. A config that must PASS covers the actions it leaves on and no others: the `receipt_*` rows each leave one release
/// authority on, and the `e7_*` rows do the same for the two liveness sentences. Three things stand beside that split.
/// `step` checks the commit step alone from every committed state and every single transaction: Safety, RetiredWhole and
/// FactWithholds are properties of one commit, so no interleaving of the writers breaks them. `receipt_composed` and
/// `e7_composed_*` run every release authority together at the largest constants that finish (one leader, `H_max` 4). And
/// the full config was run once to a deadline as a bound, not a pass (the lane's record, #3828).
///
/// ```tla DirectiveReceipt:head
/// (* One node id: its departure record, its activation directive and the issue receipt proposed for #3828, as the applier *)
/// (* commits them; the writers of the three keys, each submitting a transaction from a read that may be old; the id's     *)
/// (* process. It is the model of section 7 of the design memo of #3828 (round 10): every state variable is a line of its  *)
/// (* variables table, every action a row of its writer table (W1 to W23, S1 to S6; the row is named at each action), and  *)
/// (* tools/tla/DirectiveReceipt.writers.tsv with tools/tla/writers-check.py checks both, since TLC cannot show a wrong    *)
/// (* writer. Bounds, not proofs: TLC explores every state within a config's constants and says nothing about larger ones *)
/// (* or about what the model leaves out.                                                                                 *)
/// (*                                                                                                                    *)
/// (* FACT selects the fact that decides a directive write. Only "none" is the code at this commit.                       *)
/// (* "none"      no fact: a pending join whose mirror shows no directive issues one.                                     *)
/// (* "local"     a leader-local mark, taken when the leader's mirror passes a removal of the directive; a leader that a  *)
/// (*             leader change names starts with none.                                                                   *)
/// (* "record"    the same mark, not taken while the mirror holds a DEPARTED record (PR #3834, unmerged).                 *)
/// (* "unguarded" the receipt of "receipt", written and mirrored, but the issue expects only the directive key absent.    *)
/// (* "arm2"      the receipt names a process token, and a leader whose own view holds another token issues again and     *)
/// (*             replaces it (the first draft of the redesign).                                                          *)
/// (* "receipt"   the redesign: a receipt per node id, put by the issue's own transaction which expects it absent, and    *)
/// (*             removed only by the retirement unit.                                                                    *)
/// (* MUT is one switch on FACT = "receipt": "nowitness" (the unit removes what its read holds and witnesses nothing      *)
/// (* absent), and the three mutants of the edge runs, each taking the submit out of one arm of `join`: "noJ4" (arm J-4), *)
/// (* "noJ4ret" (arm J-4 where the id was retired), "noJ1" (arm J-1).                                                     *)
/// (* Off is the set of model actions a config switches off (by the action names of the writers table). A config that    *)
/// (* must FAIL may narrow freely: a behaviour with fewer inputs is a behaviour of the whole model. A config that must     *)
/// (* PASS says nothing about the actions it switches off.                                                                *)
/// (*                                                                                                                    *)
/// (* The instrument's bounds are conjuncts of enablings, never a cfg CONSTRAINT: the epoch (E_max, in the admissions),   *)
/// (* the commit history (H_max, in Commit), the term (MaxTerm), the token (MaxToken), the retirements (MaxRet). The      *)
/// (* in-flight bag holds one transaction per row instance (the action, with its leader or its retirement): a new submit  *)
/// (* replaces that instance's own transaction, so no submit waits for room.                                              *)
/// (*                                                                                                                    *)
/// (* Five rows have no action of their own, each a named omission: W17 (the LEGACY engine: no LEGACY node runs in an FSM2 *)
/// (* cluster), W19 (the receipt: only the issue puts it and only a unit removes it), W20 (`directed`: set inside Commit and *)
/// (* Reconnect), W22 (the record's process and time: the record is state, epoch and stale), S2 (the leader's tick: the    *)
/// (* model has no clock, and the rows the tick triggers are enabled at every step where their expressed part holds).      *)
/// (* Also left out: every condition the code adds to a writer and the model drops (the rows' dropped cells), the end of a *)
/// (* pending join, the stop of a node that runs a retirement.                                                             *)
/// (*                                                                                                                      *)
/// (* Not in the memo's tables, added here and named in the writers table: `mark` (the leader-local mark of FACT "local"   *)
/// (* and "record"; FALSE under every other FACT), a receipt that is a token under FACT "arm2", and the properties         *)
/// (* FactWithholds, RetiredWhole and SpecStep. Marked choices of this model where the memo leaves one: the first term names the     *)
/// (* least leader (the leaders are alike); a leader change may name any leader; `join` keeps one in-flight slot per leader *)
/// (* for both of its arms, `retire` one for its mirror read (W8, W9) and one for its prefix read (W10); P1 is weak        *)
/// (* fairness of the commit of each single transaction.                                                                   *)
/// EXTENDS Integers, Sequences, FiniteSets
///
/// CONSTANTS FACT, MUT,
///           Roles,       \* the roles Init may choose from: {"WORKER"}, {"CORE"} or both (the role is then free)
///           Leaders,     \* the leaders (numbers); any of them may act, a deposed one too
///           MaxTerm,     \* leader terms
///           MaxToken,    \* processes under the id
///           MaxRet,      \* termination retirements in progress at once (the memo: two)
///           E_max,       \* the top epoch of a record
///           H_max,       \* the entries the commit history may hold
///           Off          \* the model actions this config switches off
///
/// VARIABLES role,        \* "WORKER" or "CORE"; no action writes it
///           history,     \* the committed states in commit order; the last entry is the committed record, directive, receipt
///           inflight,    \* the submitted transactions, at most one per row instance
///           term,        \* the leader term and the leader it names
///           mirror,      \* per leader: the index of the history entry its mirror holds
///           pending,     \* per leader: a join of the id is pending there
///           view,        \* per leader: the first process token it saw (0: none yet)
///           token,       \* the token of the id's current process
///           runs,        \* the current process runs
///           live,        \* the leader holds evidence of the process's life
///           directed,    \* the current process has received a directive
///           retirements, \* the termination retirements in progress: each a first read
///           sticky,      \* history variable of Safety: "clear", "issued", "removed"
///           ranSince,    \* history variable of StickyWhileRunning
///           mark         \* per leader, FACT "local" and "record" only: the leader-local mark
///
/// vars == <<role, history, inflight, term, mirror, pending, view, token, runs, live, directed, retirements, sticky,
///           ranSince, mark>>
///
/// On(a) == a \notin Off
/// Receipts == FACT \in {"unguarded", "arm2", "receipt"}
/// Marks == FACT \in {"local", "record"}
/// RetIds == 1..MaxRet
///
/// \* The record's domain (fact 1): absent, ADMITTED(e), DEPARTED(e, stale). An absent record counts as epoch 0.
/// NoRec == [st |-> "none", e |-> 0, stale |-> FALSE]
/// Adm(e) == [st |-> "A", e |-> e, stale |-> FALSE]
/// Dep(e, s) == [st |-> "D", e |-> e, stale |-> s]
/// RecDom == {NoRec} \cup {Adm(e) : e \in 1..E_max} \cup {Dep(e, s) : e \in 0..E_max, s \in BOOLEAN}
/// \* A receipt is 0 (absent) or 1; under FACT "arm2" it is the token it names.
/// RcptDom == IF FACT = "arm2" THEN 0..MaxToken ELSE IF Receipts THEN {0, 1} ELSE {0}
/// EntryDom == [rec : RecDom, dir : BOOLEAN, rcpt : RcptDom]
/// NoRet == [on |-> FALSE, e |-> 0, dir |-> FALSE]
///
/// Committed == history[Len(history)]
/// record == Committed.rec
/// directive == Committed.dir
/// receipt == Committed.rcpt
/// M(L) == history[mirror[L]]      \* what leader L's mirror holds: a prefix of the commit history, maybe an old one
///
/// \* A transaction: per key what it expects (c: it checks, e: the value; a check with no write is a witness) and what it
/// \* writes ("put", "del", "keep"); k is its row instance; issue marks a leader issue (arm J-4).
/// NoOp(k) == [k |-> k, cRec |-> FALSE, eRec |-> NoRec, wRec |-> "keep", nRec |-> NoRec,
///             cDir |-> FALSE, eDir |-> FALSE, wDir |-> "keep",
///             cRcpt |-> FALSE, eRcpt |-> 0, wRcpt |-> "keep", nRcpt |-> 0, issue |-> FALSE]
/// RecTx(k, x, n) == [NoOp(k) EXCEPT !.cRec = TRUE, !.eRec = x, !.wRec = "put", !.nRec = n]
/// DepartTx(L, x, s) == RecTx(<<IF s THEN "infer" ELSE "depart", L>>, x, Dep(x.e, s))
/// J1Tx(L, x, w) == [RecTx(<<"join", L>>, x, Adm(x.e + 1)) EXCEPT !.cDir = w, !.eDir = w]
/// AdmitTx(L, x) == RecTx(<<"admit", L>>, x, Adm(x.e + 1))
/// \* The issue: directive and receipt, each expecting absent. The rejected designs differ here and in MayIssue only.
/// IssueTx(L, er, nr) == [NoOp(<<"join", L>>) EXCEPT !.cDir = TRUE, !.eDir = FALSE, !.wDir = "put",
///                            !.cRcpt = (FACT \in {"arm2", "receipt"}), !.eRcpt = er,
///                            !.wRcpt = IF Receipts THEN "put" ELSE "keep", !.nRcpt = nr, !.issue = TRUE]
/// \* The retirement unit over a read: each member the read holds is removed at its exact value, each member it does not
/// \* hold is witnessed absent.
/// Wit == MUT # "nowitness"
/// UnitTx(k, en) == [k |-> k, cRec |-> (en.rec.st # "none" \/ Wit), eRec |-> en.rec,
///                   wRec |-> IF en.rec.st # "none" THEN "del" ELSE "keep", nRec |-> NoRec,
///                   cDir |-> (en.dir \/ Wit), eDir |-> en.dir, wDir |-> IF en.dir THEN "del" ELSE "keep",
///                   cRcpt |-> (en.rcpt # 0 \/ Wit), eRcpt |-> en.rcpt, wRcpt |-> IF en.rcpt # 0 THEN "del" ELSE "keep",
///                   nRcpt |-> 0, issue |-> FALSE]
/// OpRemTx == [NoOp(<<"operatorRemoval", 0>>) EXCEPT !.wDir = "del"]
/// FputTx == [NoOp(<<"foreignPut", 0>>) EXCEPT !.wDir = "put"]
///
/// \* Every transaction an action can build: the set P1 quantifies over (TypeOK holds inflight inside it).
/// Admissible == {x \in RecDom : x.e < E_max}
/// TxSpace ==
///        {DepartTx(L, x, s) : L \in Leaders, x \in RecDom, s \in BOOLEAN}
///   \cup {J1Tx(L, x, w) : L \in Leaders, x \in {y \in Admissible : y.st = "D"}, w \in BOOLEAN}
///   \cup {AdmitTx(L, x) : L \in Leaders, x \in Admissible}
///   \cup {IssueTx(L, er, nr) : L \in Leaders, er \in IF FACT = "arm2" THEN RcptDom ELSE {0},
///                               nr \in IF FACT = "arm2" THEN 1..MaxToken ELSE IF Receipts THEN {1} ELSE {0}}
///   \cup {UnitTx(<<n, L>>, en) : n \in {"retire", "retireP"}, L \in Leaders, en \in {y \in EntryDom : y.rec.st = "D"}}
///   \cup {UnitTx(<<"attempt", r>>, en) : r \in RetIds, en \in {y \in EntryDom : y.rec.st # "A"}}
///   \cup {OpRemTx, FputTx}
///
/// \* The bag rule: a submit takes the place of its own instance's earlier transaction.
/// Submit(t) == inflight' = {u \in inflight : u.k # t.k} \cup {t}
///
/// \* The token condition T (the head of the join table). It limits fairness and Dom 3, never an enabling.
/// T(L) == \/ role = "CORE"
///         \/ M(L).rec.st = "none"
///         \/ \A L2 \in Leaders : (view[L2] # 0 /\ view[L] # 0) => view[L2] = view[L]
/// Retired == \E i \in 1..(Len(history) - 1) : history[i].rcpt # 0 /\ history[i + 1].rcpt = 0
///
/// \* mayIssue, read from the acting leader's mirror.
/// MayIssue(L) ==
///   CASE FACT = "none" -> TRUE
///     [] Marks -> ~mark[L]
///     [] FACT = "arm2" -> (M(L).rcpt = 0 \/ M(L).rcpt # view[L])
///     [] OTHER -> M(L).rcpt = 0
/// \* Arm J-4's condition on the acting leader's own state (row W16's expressed part, without T).
/// J4Cond(L) == pending[L] /\ ~M(L).dir /\ (role = "CORE" \/ M(L).rec.st # "D") /\ MayIssue(L)
/// ```
///
/// ```tla DirectiveReceipt:tail
/// \* W1, W3 (read: mirror): DEPARTED(e, stale = FALSE) at the epoch of the record read, expecting that record. Not fair.
/// Depart(L) ==
///   /\ On("depart")
///   /\ Submit(DepartTx(L, M(L).rec, FALSE))
///   /\ UNCHANGED <<role, history, term, mirror, pending, view, token, runs, live, directed, retirements, sticky, ranSince, mark>>
///
/// \* W2, W3 (read: mirror): the same with stale = TRUE. Not fair.
/// Infer(L) ==
///   /\ On("infer")
///   /\ Submit(DepartTx(L, M(L).rec, TRUE))
///   /\ UNCHANGED <<role, history, term, mirror, pending, view, token, runs, live, directed, retirements, sticky, ranSince, mark>>
///
/// \* W4: arm J-1 of `join` (read: mirror): ADMITTED(e + 1) over a DEPARTED record that is stale, or of a CORE, or of a
/// \* WORKER with a directive; it witnesses a present directive. The epoch's bound is its conjunct (Dom 7).
/// ArmJ1(L) ==
///   /\ On("join") /\ MUT # "noJ1"
///   /\ pending[L]
///   /\ M(L).rec.st = "D"
///   /\ (M(L).rec.stale \/ role = "CORE" \/ M(L).dir)
///   /\ M(L).rec.e < E_max
///   /\ Submit(J1Tx(L, M(L).rec, M(L).dir))
///   /\ UNCHANGED <<role, history, term, mirror, pending, view, token, runs, live, directed, retirements, sticky, ranSince, mark>>
///
/// \* W10 (read: prefix): placement COMPLETE beside a DEPARTED record removes the unit. Not fair.
/// RetireP(L) ==
///   /\ On("retire")
///   /\ \E i \in 1..Len(history) : history[i].rec.st = "D" /\ Submit(UnitTx(<<"retireP", L>>, history[i]))
///   /\ UNCHANGED <<role, history, term, mirror, pending, view, token, runs, live, directed, retirements, sticky, ranSince, mark>>
///
/// \* W11, W18 (read: any): the directive alone is removed, whatever is committed; the receipt stays. Not fair.
/// OpRemove ==
///   /\ On("operatorRemoval")
///   /\ directive
///   /\ Submit(OpRemTx)
///   /\ UNCHANGED <<role, history, term, mirror, pending, view, token, runs, live, directed, retirements, sticky, ranSince, mark>>
///
/// \* W18 (read: any): a directive put by another writer, with no receipt. Not fair.
/// ForeignPut ==
///   /\ On("foreignPut")
///   /\ Submit(FputTx)
///   /\ UNCHANGED <<role, history, term, mirror, pending, view, token, runs, live, directed, retirements, sticky, ranSince, mark>>
///
/// \* A termination retirement starts with its first read (prefix): the record's epoch and the directive.
/// StartRet ==
///   \E r \in RetIds, i \in 1..Len(history) :
///     /\ retirements[r] = NoRet
///     /\ \A q \in RetIds : q < r => retirements[q] # NoRet
///     /\ retirements' = [retirements EXCEPT ![r] = [on |-> TRUE, e |-> history[i].rec.e, dir |-> history[i].dir]]
///
/// \* W12: a confirmed reap, in any state of the process. Not fair.
/// Terminate ==
///   /\ On("terminate")
///   /\ StartRet
///   /\ UNCHANGED <<role, history, inflight, term, mirror, pending, view, token, runs, live, directed, sticky, ranSince, mark>>
///
/// \* W13: the EXTERNAL branch, for a process that is not live. Not fair.
/// ExternalConfirm ==
///   /\ On("externalConfirm")
///   /\ ~live
///   /\ StartRet
///   /\ UNCHANGED <<role, history, inflight, term, mirror, pending, view, token, runs, live, directed, sticky, ranSince, mark>>
///
/// \* W14: a forced clear, for a process that is not live. Not fair.
/// ForcedClear ==
///   /\ On("forcedClear")
///   /\ ~live
///   /\ StartRet
///   /\ UNCHANGED <<role, history, inflight, term, mirror, pending, view, token, runs, live, directed, sticky, ranSince, mark>>
///
/// \* W15 (read: prefix, once per attempt), its two paths: REMOVE submits the unit; DONE and END drop the retirement; WAIT
/// \* is no step. Fair (weak), with no term condition.
/// AttemptRemove(r) ==
///   /\ On("attempt")
///   /\ retirements[r] # NoRet
///   /\ \E i \in 1..Len(history) :
///        /\ Decide(retirements[r], history[i]) = "REMOVE"
///        /\ Submit(UnitTx(<<"attempt", r>>, history[i]))
///   /\ UNCHANGED <<role, history, term, mirror, pending, view, token, runs, live, directed, retirements, sticky, ranSince, mark>>
///
/// AttemptEnd(r) ==
///   /\ On("attempt")
///   /\ retirements[r] # NoRet
///   /\ \E i \in 1..Len(history) : Decide(retirements[r], history[i]) \in {"DONE", "END"}
///   /\ retirements' = [retirements EXCEPT ![r] = NoRet]
///   /\ UNCHANGED <<role, history, inflight, term, mirror, pending, view, token, runs, live, directed, sticky, ranSince, mark>>
///
/// \* W21 (reads the committed directive): a directed, live WORKER halts on the delivered removal. Not fair.
/// Halt ==
///   /\ On("halt")
///   /\ role = "WORKER" /\ runs /\ live /\ directed /\ ~directive
///   /\ runs' = FALSE /\ live' = FALSE /\ ranSince' = FALSE
///   /\ UNCHANGED <<role, history, inflight, term, mirror, pending, view, token, directed, retirements, sticky, mark>>
///
/// \* The applier's expected-value rule (KVStore; assumed): all of a transaction's writes, or none.
/// Accepts(t) == /\ t.cRec => record = t.eRec
///               /\ t.cDir => directive = t.eDir
///               /\ t.cRcpt => receipt = t.eRcpt
/// After(t) ==
///   IF Accepts(t)
///   THEN [rec |-> CASE t.wRec = "put" -> t.nRec [] t.wRec = "del" -> NoRec [] OTHER -> record,
///         dir |-> CASE t.wDir = "put" -> TRUE [] t.wDir = "del" -> FALSE [] OTHER -> directive,
///         rcpt |-> CASE t.wRcpt = "put" -> t.nRcpt [] t.wRcpt = "del" -> 0 [] OTHER -> receipt]
///   ELSE Committed
///
/// \* W23: the commit step of every writer row; a refused transaction leaves the bag and writes nothing. The bound of
/// \* `history` is its conjunct (Dom 8). `directed` as row W20: a directive put while the process runs. `sticky`: a commit
/// \* that removes the receipt is a retirement and clears it; a committed leader issue sets "issued"; a commit that removes
/// \* the directive takes "issued" to "removed". Not fair: it is P1 of the liveness sentences.
/// Commit(t) ==
///   LET acc == Accepts(t)
///       new == After(t)
///       nst == IF acc /\ receipt # 0 /\ new.rcpt = 0 THEN "clear"
///              ELSE IF acc /\ t.issue THEN "issued"
///              ELSE IF sticky = "issued" /\ directive /\ ~new.dir THEN "removed"
///              ELSE sticky
///   IN /\ t \in inflight
///      /\ (new # Committed) => Len(history) < H_max
///      /\ history' = IF new # Committed THEN Append(history, new) ELSE history
///      /\ inflight' = inflight \ {t}
///      /\ directed' = (directed \/ (acc /\ t.wDir = "put" /\ runs))
///      /\ sticky' = nst
///      /\ ranSince' = IF nst = "removed" /\ sticky # "removed" /\ runs THEN TRUE ELSE ranSince
///      /\ UNCHANGED <<role, term, mirror, pending, view, token, runs, live, retirements, mark>>
///
/// \* S6: a transaction is lost. (`delay` is no step: a delayed transaction is one that stays in the bag.) Not fair.
/// Lose(t) ==
///   /\ On("lose")
///   /\ t \in inflight
///   /\ inflight' = inflight \ {t}
///   /\ UNCHANGED <<role, history, term, mirror, pending, view, token, runs, live, directed, retirements, sticky, ranSince, mark>>
///
/// \* FACT "local", "record": the mark is taken when the mirror passes a removal of the directive ("record": not while it
/// \* holds a DEPARTED record there).
/// MarkTaken(i, j) ==
///   /\ Marks
///   /\ \E n \in (i + 1)..j : history[n - 1].dir /\ ~history[n].dir /\ (FACT = "record" => history[n].rec.st # "D")
///
/// \* S1: a leader's mirror moves to any later prefix of the commit history. Not fair: it is P2 of the liveness sentences.
/// Advance(L) ==
///   /\ \E j \in (mirror[L] + 1)..Len(history) :
///        /\ mirror' = [mirror EXCEPT ![L] = j]
///        /\ mark' = [mark EXCEPT ![L] = mark[L] \/ MarkTaken(mirror[L], j)]
///   /\ UNCHANGED <<role, history, inflight, term, pending, view, token, runs, live, directed, retirements, sticky, ranSince>>
///
/// \* S3: the term advances and names a leader; every leader keeps its mirror and its pending joins. The named leader's
/// \* mark is gone (Rebuild builds a new model; FACT "local" and "record"). Not fair.
/// LeaderChange ==
///   /\ On("leaderChange")
///   /\ term.n < MaxTerm
///   /\ \E L \in Leaders :
///        /\ term' = [n |-> term.n + 1, ldr |-> L]
///        /\ mark' = [mark EXCEPT ![L] = FALSE]
///   /\ UNCHANGED <<role, history, inflight, mirror, pending, view, token, runs, live, directed, retirements, sticky, ranSince>>
///
/// \* S4's write: a join becomes pending at one leader, whose view becomes the current token if it is not set.
/// Raise(L) ==
///   /\ pending' = [pending EXCEPT ![L] = TRUE]
///   /\ view' = [view EXCEPT ![L] = IF view[L] = 0 THEN token ELSE view[L]]
///
/// \* S4: the join input, in any state and at any leader. Not fair.
/// JoinInput(L) ==
///   /\ On("joinInput")
///   /\ Raise(L)
///   /\ UNCHANGED <<role, history, inflight, term, mirror, token, runs, live, directed, retirements, sticky, ranSince, mark>>
///
/// \* S5, its four actions (facts 3 and 4). None is fair.
/// Restart ==
///   /\ On("processRestart")
///   /\ token < MaxToken
///   /\ token' = token + 1 /\ runs' = TRUE /\ live' = FALSE /\ directed' = FALSE /\ ranSince' = FALSE
///   /\ UNCHANGED <<role, history, inflight, term, mirror, pending, view, retirements, sticky, mark>>
///
/// Partition ==
///   /\ On("partition")
///   /\ runs /\ live
///   /\ live' = FALSE
///   /\ UNCHANGED <<role, history, inflight, term, mirror, pending, view, token, runs, directed, retirements, sticky, ranSince, mark>>
///
/// Reconnect ==
///   /\ On("reconnect")
///   /\ runs /\ ~live
///   /\ live' = TRUE
///   /\ directed' = (directed \/ directive)
///   /\ \E L \in Leaders : Raise(L)
///   /\ UNCHANGED <<role, history, inflight, term, mirror, token, runs, retirements, sticky, ranSince, mark>>
///
/// \* S5 and W21: the process's own stop, and a halt delivered to a worker that is not live.
/// Stop ==
///   /\ On("stop")
///   /\ runs
///   /\ runs' = FALSE /\ live' = FALSE /\ ranSince' = FALSE
///   /\ UNCHANGED <<role, history, inflight, term, mirror, pending, view, token, directed, retirements, sticky, mark>>
///
/// MinLeader == CHOOSE L \in Leaders : \A L2 \in Leaders : L <= L2
///
/// \* The empty store, and a first process in the state fact 4 gives a restarted one. The leaders are alike, so the first
/// \* term names the least.
/// Init ==
///   /\ role \in Roles
///   /\ history = <<[rec |-> NoRec, dir |-> FALSE, rcpt |-> 0]>>
///   /\ inflight = {}
///   /\ term = [n |-> 1, ldr |-> MinLeader]
///   /\ mirror = [L \in Leaders |-> 1]
///   /\ pending = [L \in Leaders |-> FALSE]
///   /\ view = [L \in Leaders |-> 0]
///   /\ token = 1 /\ runs = TRUE /\ live = FALSE /\ directed = FALSE
///   /\ retirements = [r \in RetIds |-> NoRet]
///   /\ sticky = "clear" /\ ranSince = FALSE
///   /\ mark = [L \in Leaders |-> FALSE]
///
/// \* The 21 actions of the memo's map (`join` is ArmJ1 and Issue, `retire` is Retire and RetireP, `attempt` is AttemptRemove
/// \* and AttemptEnd). A plain disjunction: no conjunct of its own, so no bound hides here (writers-check.py holds it so).
/// Next ==
///   \/ \E L \in Leaders : Depart(L) \/ Infer(L) \/ ArmJ1(L) \/ Issue(L) \/ Admit(L) \/ Retire(L) \/ RetireP(L)
///                         \/ Advance(L) \/ JoinInput(L)
///   \/ OpRemove \/ ForeignPut \/ Terminate \/ ExternalConfirm \/ ForcedClear \/ Halt
///   \/ \E r \in RetIds : AttemptRemove(r) \/ AttemptEnd(r)
///   \/ \E t \in inflight : Commit(t) \/ Lose(t)
///   \/ LeaderChange \/ Restart \/ Partition \/ Reconnect \/ Stop
///
/// \* Fairness, by the one rule of the writer table: weak fairness exactly where a fairness cell says "weak", on the row's
/// \* expressed part and on the tick's (S2: the acting leader is the one the term names). W4, W16 (not beside a DEPARTED
/// \* record), W8, and W15 (no term condition). Nothing else is fair.
/// Attempt(r) == AttemptRemove(r) \/ AttemptEnd(r)
/// J1Fair(L) == term.ldr = L /\ T(L) /\ ArmJ1(L)
/// J4Fair(L) == term.ldr = L /\ T(L) /\ M(L).rec.st # "D" /\ Issue(L)
/// RetireFair(L) == term.ldr = L /\ role = "WORKER" /\ (M(L).dir \/ M(L).rcpt # 0) /\ Retire(L)
///
/// Fairness ==
///   /\ \A L \in Leaders : WF_vars(J1Fair(L)) /\ WF_vars(J4Fair(L)) /\ WF_vars(RetireFair(L))
///   /\ \A r \in RetIds : WF_vars(Attempt(r))
///
/// Spec == Init /\ [][Next]_vars /\ Fairness
///
/// \* Not in the memo: the commit step alone, from EVERY committed state and EVERY single transaction of TxSpace (config
/// \* `step`). Safety, RetiredWhole and FactWithholds are properties of one commit; StepInv is what a commit needs of the state
/// \* before it. A commit that keeps them from every such state keeps them in every interleaving of every writer, which is why
/// \* the per-authority split of the `receipt_*` rows cannot hide a breach of these three.
/// StepInv == sticky \in {"issued", "removed"} => receipt # 0
/// InitStep ==
///   /\ role \in Roles
///   /\ history \in {<<e>> : e \in EntryDom}
///   /\ inflight \in {{t} : t \in TxSpace}
///   /\ sticky \in {"clear", "issued", "removed"}
///   /\ StepInv
///   /\ term = [n |-> 1, ldr |-> MinLeader]
///   /\ mirror = [L \in Leaders |-> 1]
///   /\ pending = [L \in Leaders |-> TRUE]
///   /\ view = [L \in Leaders |-> 1]
///   /\ token = 1 /\ runs = TRUE /\ live = FALSE /\ directed = FALSE
///   /\ retirements = [r \in RetIds |-> NoRet]
///   /\ ranSince = FALSE
///   /\ mark = [L \in Leaders |-> FALSE]
/// SpecStep == InitStep /\ [][\E t \in inflight : Commit(t)]_vars
///
/// TypeOK ==
///   /\ role \in Roles
///   /\ Len(history) \in 1..H_max
///   /\ \A i \in 1..Len(history) : history[i] \in EntryDom
///   /\ inflight \subseteq TxSpace
///   /\ \A t, u \in inflight : t.k = u.k => t = u
///   /\ term.n \in 1..MaxTerm /\ term.ldr \in Leaders
///   /\ \A L \in Leaders : /\ mirror[L] \in 1..Len(history) /\ pending[L] \in BOOLEAN
///                         /\ view[L] \in 0..MaxToken /\ mark[L] \in BOOLEAN
///   /\ token \in 1..MaxToken
///   /\ runs \in BOOLEAN /\ live \in BOOLEAN /\ directed \in BOOLEAN /\ (live => runs)
///   /\ \A r \in RetIds : retirements[r] \in [on : BOOLEAN, e : 0..E_max, dir : BOOLEAN]
///   /\ sticky \in {"clear", "issued", "removed"} /\ ranSince \in BOOLEAN
///   /\ (~Marks => \A L \in Leaders : ~mark[L])
///
/// \* Safety, per id and over committed facts alone: no leader issue commits while a directive that a leader issue put
/// \* stands removed since the id's last retirement. Only such a commit takes `sticky` from "removed" to "issued".
/// Safety == [][sticky = "removed" => sticky' # "issued"]_vars
///
/// \* The claim fix round 3 of the memo deleted, kept as the property of the residual config: no leader issue commits for
/// \* a process under which a leader-issued directive was removed and that has run ever since.
/// StickyWhileRunning == [][(ranSince /\ sticky # "issued") => sticky' # "issued"]_vars
///
/// \* Not in the memo. What the fact itself must do: while a leader-issued directive stands removed, no leader whose mirror
/// \* holds the whole history may be let through by mayIssue. A stale mirror and a late commit break Safety under every FACT
/// \* but "receipt"; this invariant tells the named inputs F3 (the mark lost at a leader change) and F1 (the mark not taken
/// \* beside a DEPARTED record) from them.
/// FactWithholds == sticky = "removed" => \A L \in Leaders : mirror[L] = Len(history) => ~J4Cond(L)
///
/// \* Not in section 7 of the memo (the r2 model's property, for the sixth known-bad config): a commit that removes the
/// \* record leaves no receipt.
/// RetiredWhole == [][(record.st # "none" /\ history'[Len(history')].rec.st = "none")
///                    => history'[Len(history')].rcpt = 0]_vars
///
/// \* The stability claims the two sentences rest on (the memo states each in prose).
/// RetiredStays == [][Retired => Retired']_vars
/// TopHistoryStays == [][Len(history) = H_max => Len(history') = H_max]_vars
/// PendingStays == [][\A L \in Leaders : pending[L] => pending'[L]]_vars
/// MirrorForward == [][\A L \in Leaders : mirror'[L] >= mirror[L]]_vars
/// ViewStays == [][\A L \in Leaders : view[L] # 0 => view'[L] = view[L]]_vars
///
/// \* The liveness sentences (invariant 2): for every leader L, <>[]Dom_k(L) /\ Settle /\ P1 /\ P2(L) => []<>Goal. The parts
/// \* are the memo's parts table; they are parts of the two sentences and never fairness of the specification.
/// Held == role = "WORKER" /\ record.st = "D" /\ ~record.stale /\ ~directive /\ receipt = 0
/// Unreleased == receipt # 0 /\ ~directive
/// TopEpoch == record.st = "D" /\ record.e = E_max /\ (record.stale \/ role = "CORE")
/// TopHistory == Len(history) = H_max
/// Goal == directive
///
/// \* Dom 1 to 5, 7 and 8 (d drops one line, for the probes); Dom 6 tells the sentences apart; Dom 7 and Dom 8 are the
/// \* instrument's bounds that can stop a needed step.
/// DomLines(L, d) ==
///   /\ (d = 1 \/ term.ldr = L) /\ (d = 2 \/ pending[L]) /\ (d = 3 \/ T(L)) /\ (d = 4 \/ ~Held) /\ (d = 5 \/ ~Unreleased)
///   /\ (d = 7 \/ ~TopEpoch) /\ (d = 8 \/ ~TopHistory)
/// Dom1(L) == DomLines(L, 0) /\ ~Retired
/// Dom2(L) == DomLines(L, 0) /\ Retired
/// Dom1no(L, d) == DomLines(L, d) /\ ~Retired
///
/// \* Settle: from some point on every step is a commit, an advance, or a fair row's step where its fairness is.
/// SettleStep ==
///   \/ \E t \in inflight : Commit(t)
///   \/ \E L \in Leaders : Advance(L) \/ J1Fair(L) \/ J4Fair(L) \/ RetireFair(L)
///   \/ \E r \in RetIds : Attempt(r)
/// Settle == <>[][SettleStep]_vars
/// P1 == \A t \in TxSpace : WF_vars(Commit(t))
/// P2(L) == WF_vars(Advance(L))
///
/// Live1 == \A L \in Leaders : (<>[]Dom1(L) /\ Settle /\ P1 /\ P2(L)) => []<>Goal
/// Live2 == \A L \in Leaders : (<>[]Dom2(L) /\ Settle /\ P1 /\ P2(L)) => []<>Goal
///
/// \* Edge run E4, a safety probe that must FAIL: the commit of a leader issue reaches Goal from inside Dom_k and stays inside.
/// IssueCommit == \E t \in inflight : t.issue /\ Accepts(t) /\ Commit(t)
/// NoEntry1 == [][~\E L \in Leaders : Dom1(L) /\ ~Goal /\ IssueCommit /\ Dom1(L)']_vars
/// NoEntry2 == [][~\E L \in Leaders : Dom2(L) /\ ~Goal /\ IssueCommit /\ Dom2(L)']_vars
///
/// \* The necessity probes, each Live1 without one line of Dom, each must FAIL: without the line a stuck class is inside.
/// Live1no1 == \A L \in Leaders : (<>[]Dom1no(L, 1) /\ Settle /\ P1 /\ P2(L)) => []<>Goal
/// Live1no2 == \A L \in Leaders : (<>[]Dom1no(L, 2) /\ Settle /\ P1 /\ P2(L)) => []<>Goal
/// Live1no3 == \A L \in Leaders : (<>[]Dom1no(L, 3) /\ Settle /\ P1 /\ P2(L)) => []<>Goal
/// Live1no4 == \A L \in Leaders : (<>[]Dom1no(L, 4) /\ Settle /\ P1 /\ P2(L)) => []<>Goal
/// Live1no5 == \A L \in Leaders : (<>[]Dom1no(L, 5) /\ Settle /\ P1 /\ P2(L)) => []<>Goal
/// Live1no7 == \A L \in Leaders : (<>[]Dom1no(L, 7) /\ Settle /\ P1 /\ P2(L)) => []<>Goal
/// Live1no8 == \A L \in Leaders : (<>[]Dom1no(L, 8) /\ Settle /\ P1 /\ P2(L)) => []<>Goal
/// \* The probes of Goal, each must FAIL: Live1 with the goal read as a committed leader issue (a foreign put keeps it away),
/// \* and as the flag `directed` (a stopped process never gets it).
/// Live1GoalIssue == \A L \in Leaders : (<>[]Dom1(L) /\ Settle /\ P1 /\ P2(L)) => []<>(sticky = "issued")
/// Live1GoalFlag == \A L \in Leaders : (<>[]Dom1(L) /\ Settle /\ P1 /\ P2(L)) => []<>directed
/// ```
///
/// ```tlacfg DirectiveReceipt:common
/// INVARIANT TypeOK
/// ```
///
/// ```tlacfg DirectiveReceipt:base
/// SPECIFICATION Spec
/// CONSTANT FACT = "none"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "depart", "infer", "admit", "retire", "terminate", "foreignPut"}
/// PROPERTY Safety
/// ```
///
/// ```tlacfg DirectiveReceipt:local_f3
/// SPECIFICATION Spec
/// CONSTANT FACT = "local"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "depart", "infer", "admit", "retire", "terminate", "foreignPut"}
/// INVARIANT FactWithholds
/// ```
///
/// ```tlacfg DirectiveReceipt:local_ctl
/// SPECIFICATION Spec
/// CONSTANT FACT = "local"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "depart", "infer", "admit", "retire", "terminate", "foreignPut"}
/// INVARIANT FactWithholds
/// ```
///
/// ```tlacfg DirectiveReceipt:record_f1
/// SPECIFICATION Spec
/// CONSTANT FACT = "record"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "infer", "retire", "terminate", "foreignPut"}
/// INVARIANT FactWithholds
/// ```
///
/// ```tlacfg DirectiveReceipt:record_ctl
/// SPECIFICATION Spec
/// CONSTANT FACT = "record"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "depart", "infer", "retire", "terminate", "foreignPut"}
/// INVARIANT FactWithholds
/// ```
///
/// ```tlacfg DirectiveReceipt:unguarded_late
/// SPECIFICATION Spec
/// CONSTANT FACT = "unguarded"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "depart", "infer", "admit", "retire", "terminate", "foreignPut"}
/// PROPERTY Safety
/// ```
///
/// ```tlacfg DirectiveReceipt:unguarded_ctl
/// SPECIFICATION Spec
/// CONSTANT FACT = "unguarded"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "depart", "infer", "admit", "retire", "terminate", "foreignPut"}
/// INVARIANT FactWithholds
/// ```
///
/// ```tlacfg DirectiveReceipt:arm2_f2
/// SPECIFICATION Spec
/// CONSTANT FACT = "arm2"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "depart", "infer", "admit", "retire", "terminate", "foreignPut"}
/// PROPERTY Safety
/// ```
///
/// ```tlacfg DirectiveReceipt:arm2_n1
/// SPECIFICATION Spec
/// CONSTANT FACT = "arm2"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "stop", "leaderChange", "lose", "externalConfirm", "forcedClear", "depart", "infer", "admit", "retire", "terminate", "foreignPut"}
/// PROPERTY StickyWhileRunning
/// ```
///
/// ```tlacfg DirectiveReceipt:mut_nowitness
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "nowitness"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "infer", "admit", "terminate", "foreignPut"}
/// PROPERTY RetiredWhole
/// ```
///
/// ```tlacfg DirectiveReceipt:residual_forced
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"terminate", "externalConfirm", "retire", "lose", "leaderChange", "foreignPut", "infer", "admit", "depart", "stop", "processRestart", "halt"}
/// PROPERTY StickyWhileRunning
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_depart
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "infer", "admit", "retire", "terminate"}
/// INVARIANT StepInv
/// INVARIANT FactWithholds
/// PROPERTY Safety
/// PROPERTY RetiredWhole
/// PROPERTY RetiredStays
/// PROPERTY TopHistoryStays
/// PROPERTY PendingStays
/// PROPERTY MirrorForward
/// PROPERTY ViewStays
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_infer
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "depart", "admit", "retire", "terminate"}
/// INVARIANT StepInv
/// INVARIANT FactWithholds
/// PROPERTY Safety
/// PROPERTY RetiredWhole
/// PROPERTY RetiredStays
/// PROPERTY TopHistoryStays
/// PROPERTY PendingStays
/// PROPERTY MirrorForward
/// PROPERTY ViewStays
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_admit
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "depart", "infer", "retire", "terminate"}
/// INVARIANT StepInv
/// INVARIANT FactWithholds
/// PROPERTY Safety
/// PROPERTY RetiredWhole
/// PROPERTY RetiredStays
/// PROPERTY TopHistoryStays
/// PROPERTY PendingStays
/// PROPERTY MirrorForward
/// PROPERTY ViewStays
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_terminate
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "depart", "infer", "admit", "retire"}
/// INVARIANT StepInv
/// INVARIANT FactWithholds
/// PROPERTY Safety
/// PROPERTY RetiredWhole
/// PROPERTY RetiredStays
/// PROPERTY TopHistoryStays
/// PROPERTY PendingStays
/// PROPERTY MirrorForward
/// PROPERTY ViewStays
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_process
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"depart", "infer", "admit", "retire", "terminate", "externalConfirm", "forcedClear"}
/// INVARIANT StepInv
/// INVARIANT FactWithholds
/// PROPERTY Safety
/// PROPERTY StickyWhileRunning
/// PROPERTY RetiredStays
/// PROPERTY TopHistoryStays
/// PROPERTY PendingStays
/// PROPERTY MirrorForward
/// PROPERTY ViewStays
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_forced
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"terminate", "externalConfirm", "retire", "lose", "leaderChange", "foreignPut", "infer", "admit", "depart", "stop", "processRestart", "halt"}
/// PROPERTY Safety
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_retire_one
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "infer", "admit", "terminate"}
/// INVARIANT StepInv
/// INVARIANT FactWithholds
/// PROPERTY Safety
/// PROPERTY RetiredWhole
/// PROPERTY RetiredStays
/// PROPERTY TopHistoryStays
/// PROPERTY PendingStays
/// PROPERTY MirrorForward
/// PROPERTY ViewStays
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_retire_worker
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "infer", "admit", "terminate"}
/// INVARIANT StepInv
/// INVARIANT FactWithholds
/// PROPERTY Safety
/// PROPERTY RetiredWhole
/// PROPERTY RetiredStays
/// PROPERTY TopHistoryStays
/// PROPERTY PendingStays
/// PROPERTY MirrorForward
/// PROPERTY ViewStays
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_retire_core
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear", "infer", "admit", "terminate"}
/// INVARIANT StepInv
/// INVARIANT FactWithholds
/// PROPERTY Safety
/// PROPERTY RetiredWhole
/// PROPERTY RetiredStays
/// PROPERTY TopHistoryStays
/// PROPERTY PendingStays
/// PROPERTY MirrorForward
/// PROPERTY ViewStays
/// ```
///
/// ```tlacfg DirectiveReceipt:residual_forced_memo
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"terminate", "externalConfirm", "retire"}
/// PROPERTY StickyWhileRunning
/// ```
///
/// ```tlacfg DirectiveReceipt:e1_core
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "noJ4"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "terminate", "foreignPut", "operatorRemoval"}
/// PROPERTY Live1
/// ```
///
/// ```tlacfg DirectiveReceipt:e1_worker
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "noJ4"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "terminate", "foreignPut", "operatorRemoval"}
/// PROPERTY Live1
/// ```
///
/// ```tlacfg DirectiveReceipt:e2_core
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "noJ4ret"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "foreignPut", "operatorRemoval"}
/// PROPERTY Live2
/// ```
///
/// ```tlacfg DirectiveReceipt:e2_worker
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "noJ4ret"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "foreignPut", "operatorRemoval"}
/// PROPERTY Live2
/// ```
///
/// ```tlacfg DirectiveReceipt:e3_core_live1
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "noJ1"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "admit", "retire", "terminate", "foreignPut", "operatorRemoval"}
/// PROPERTY Live1
/// ```
///
/// ```tlacfg DirectiveReceipt:e3_worker_live1
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "noJ1"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "admit", "retire", "terminate", "foreignPut", "operatorRemoval"}
/// PROPERTY Live1
/// ```
///
/// ```tlacfg DirectiveReceipt:e3_core_live2
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "noJ1"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "admit", "retire", "foreignPut", "operatorRemoval"}
/// PROPERTY Live2
/// ```
///
/// ```tlacfg DirectiveReceipt:e3_worker_live2
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "noJ1"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "admit", "retire", "foreignPut", "operatorRemoval"}
/// PROPERTY Live2
/// ```
///
/// ```tlacfg DirectiveReceipt:e4_core_k1
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "terminate", "foreignPut", "operatorRemoval"}
/// PROPERTY NoEntry1
/// ```
///
/// ```tlacfg DirectiveReceipt:e4_worker_k1
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "terminate", "foreignPut", "operatorRemoval"}
/// PROPERTY NoEntry1
/// ```
///
/// ```tlacfg DirectiveReceipt:e4_core_k2
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "foreignPut", "operatorRemoval"}
/// PROPERTY NoEntry2
/// ```
///
/// ```tlacfg DirectiveReceipt:e4_worker_k2
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "foreignPut", "operatorRemoval"}
/// PROPERTY NoEntry2
/// ```
///
/// ```tlacfg DirectiveReceipt:e5_nodom7
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "infer", "retire", "terminate", "foreignPut", "operatorRemoval"}
/// PROPERTY Live1no7
/// ```
///
/// ```tlacfg DirectiveReceipt:e6_nodom8
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "terminate"}
/// PROPERTY Live1no8
/// ```
///
/// ```tlacfg DirectiveReceipt:e5_nodom1
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "depart", "infer", "admit", "retire", "terminate", "foreignPut", "operatorRemoval"}
/// PROPERTY Live1no1
/// ```
///
/// ```tlacfg DirectiveReceipt:e5_nodom2
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "terminate", "foreignPut", "operatorRemoval"}
/// PROPERTY Live1no2
/// ```
///
/// ```tlacfg DirectiveReceipt:e5a_nodom3
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "leaderChange", "depart", "infer", "retire", "terminate", "foreignPut", "operatorRemoval"}
/// PROPERTY Live1no3
/// ```
///
/// ```tlacfg DirectiveReceipt:e5b_nodom4
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "infer", "admit", "retire", "terminate", "foreignPut", "operatorRemoval"}
/// PROPERTY Live1no4
/// ```
///
/// ```tlacfg DirectiveReceipt:e5c_nodom5_core
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "terminate", "foreignPut"}
/// PROPERTY Live1no5
/// ```
///
/// ```tlacfg DirectiveReceipt:e5c_nodom5_worker
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "terminate", "foreignPut"}
/// PROPERTY Live1no5
/// ```
///
/// ```tlacfg DirectiveReceipt:goal_issue
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "terminate", "operatorRemoval"}
/// PROPERTY Live1GoalIssue
/// ```
///
/// ```tlacfg DirectiveReceipt:goal_flag
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "admit", "retire", "terminate", "foreignPut", "operatorRemoval"}
/// PROPERTY Live1GoalFlag
/// ```
///
/// ```tlacfg DirectiveReceipt:step
/// SPECIFICATION SpecStep
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {}
/// INVARIANT StepInv
/// INVARIANT FactWithholds
/// PROPERTY Safety
/// PROPERTY RetiredWhole
/// ```
///
/// ```tlacfg DirectiveReceipt:step_unguarded
/// SPECIFICATION SpecStep
/// CONSTANT FACT = "unguarded"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {}
/// PROPERTY Safety
/// ```
///
/// ```tlacfg DirectiveReceipt:step_nowitness
/// SPECIFICATION SpecStep
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "nowitness"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {}
/// PROPERTY RetiredWhole
/// ```
///
/// ```tlacfg DirectiveReceipt:e7_core
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "depart", "infer", "admit", "retire", "terminate"}
/// PROPERTY Live1
/// PROPERTY Live2
/// ```
///
/// ```tlacfg DirectiveReceipt:e7_depart
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "foreignPut", "infer", "admit", "retire", "terminate"}
/// PROPERTY Live1
/// PROPERTY Live2
/// ```
///
/// ```tlacfg DirectiveReceipt:e7_infer
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "foreignPut", "depart", "admit", "retire", "terminate"}
/// PROPERTY Live1
/// PROPERTY Live2
/// ```
///
/// ```tlacfg DirectiveReceipt:e7_admit
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "foreignPut", "depart", "infer", "retire", "terminate"}
/// PROPERTY Live1
/// PROPERTY Live2
/// ```
///
/// ```tlacfg DirectiveReceipt:e7_terminate
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "foreignPut", "depart", "infer", "admit", "retire"}
/// PROPERTY Live1
/// PROPERTY Live2
/// ```
///
/// ```tlacfg DirectiveReceipt:e7_retire
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "infer", "admit", "terminate"}
/// PROPERTY Live1
/// PROPERTY Live2
/// ```
///
/// ```tlacfg DirectiveReceipt:e7_admit_foreign
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1, 2}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 5
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "depart", "infer", "retire", "terminate"}
/// PROPERTY Live1
/// PROPERTY Live2
/// ```
///
/// ```tlacfg DirectiveReceipt:receipt_composed
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER", "CORE"}
/// CONSTANT Leaders = {1}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 2
/// CONSTANT E_max = 1
/// CONSTANT H_max = 4
/// CONSTANT Off = {"processRestart", "partition", "reconnect", "stop", "halt", "leaderChange", "lose", "externalConfirm", "forcedClear"}
/// INVARIANT StepInv
/// INVARIANT FactWithholds
/// PROPERTY Safety
/// PROPERTY RetiredWhole
/// PROPERTY RetiredStays
/// PROPERTY TopHistoryStays
/// PROPERTY PendingStays
/// PROPERTY MirrorForward
/// PROPERTY ViewStays
/// ```
///
/// ```tlacfg DirectiveReceipt:e7_composed_worker
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"WORKER"}
/// CONSTANT Leaders = {1}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 1
/// CONSTANT E_max = 1
/// CONSTANT H_max = 4
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "foreignPut"}
/// PROPERTY Live1
/// PROPERTY Live2
/// ```
///
/// ```tlacfg DirectiveReceipt:e7_composed_core
/// SPECIFICATION Spec
/// CONSTANT FACT = "receipt"
/// CONSTANT MUT = "none"
/// CONSTANT Roles = {"CORE"}
/// CONSTANT Leaders = {1}
/// CONSTANT MaxTerm = 2
/// CONSTANT MaxToken = 2
/// CONSTANT MaxRet = 1
/// CONSTANT E_max = 1
/// CONSTANT H_max = 4
/// CONSTANT Off = {"partition", "reconnect", "stop", "halt", "lose", "externalConfirm", "forcedClear", "processRestart", "leaderChange", "foreignPut"}
/// PROPERTY Live1
/// PROPERTY Live2
/// ```
package org.pragmatica.aether.deployment.cluster.fsm2;
