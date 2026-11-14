// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
/// Rabia consensus engine.
///
/// The TLA+ models `RabiaSlot` (slot sequence, #3164), `RabiaEpoch` (in-log reconfiguration) and `RabiaAdopt` (recovery and state
/// adoption) live in doc-comment fragments: this skeleton carries each model's constants, variables, helpers, `Init`, `Next`,
/// fairness, properties and configs; each action sits on the method that implements it ([RabiaEngine], [PhaseData]).
/// `tools/tla/check.sh <Module> <variant> integrations/consensus/src/main/java` extracts and checks one config;
/// `tools/tla/run.sh` runs every config with its expected verdict (`tools/tla/models.tsv`). Results are bounds, not
/// proofs: a passing config says no violation exists within its constants.
///
/// Deviations from the Rabia paper that the models pin:
/// - D1 The coin is deterministic and common (a function of slot, epoch and round); the paper's is random.
/// - D2 A decision is accepted from any trusted peer when its epoch equals the voter epoch at the slot.
/// - D3 Ballots are not persisted; a restart under the same node id is refused by every peer (boot-token fence).
/// - D4 State adoption after a pause or restart needs n/2 responders when the node vouches for its own durable history
///   or a marker proves it never voted, and n/2+1 otherwise.
/// - D5 A newer epoch is adopted from a single live responder when all live responders claim one configuration.
/// - D6 A reconfiguration is decided in a slot and governs from the next; a ballot counts only from a member of the
///   installed roster at the exact epoch, and ballots for a newer epoch are deferred.
/// - D8 A proposal is the lowest pending batch id; a voter with nothing pending proposes an empty batch.
/// - D9 Decided slots are garbage-collected; a ballot for a past slot is answered by repair and never counted again.
/// - D10 Quorum-loss pause: modelled as Pause/Resume in RabiaAdopt. The far-future resync and the decision-gap timeout
///   are excluded (liveness only).
/// - D11 A node activates only after its participation marker is durably recorded; the receiver counts only installed
///   roster members.
/// - D12 The cluster leader is a value committed through Rabia; the models treat it as at most two competing
///   reconfiguration requests.
///
/// Reading the configs: a `pass` row named as a removal (`noexactepoch`, `norelayepoch`, `nopastguard`, `nooneconfig`,
/// `noselfvouch`, `paperD4`, `paperD5`) is a known-bad config that did not go red: the removed rule is redundant at
/// those bounds, which does not show the rule is needed. `paper_*` and `paperD*` swap one rule for the paper's. `ctl_*`
/// are instrument checks (a failing `Reach` shows an adoption path is reachable). `rjT_*` pass vacuously for rejoin
/// (`ctl_rjTAny` shows no rejoined incarnation completes an adoption), so only `rjT_noamnesiafence` is informative;
/// `rjA_*` and `rjW_*` admit an old or wiped incarnation by construction and fail on Agreement. Configs whose result is
/// an incomplete run or a random-simulation hit have no row.
///
/// ```tla RabiaSlot:head
/// \* One Rabia log slot sequence (weak-MVC per slot) at ONE fixed roster and epoch, with our deviations
/// \* D1 (deterministic coin), D8 (proposal selection, empty proposals), D9 (slot GC and the past-slot guard),
/// \* D11 (activation marker, receiver-enforced electorate). pragmatica#3164.
/// \*
/// \* Fixed: the fixes the modelled code carries. The code as it stands carries AllFixes; a config that drops one is a
/// \* known-bad config that must fail on the property it protects:
/// \*   pastguard   - D9: a ballot for a slot below the current one is answered by repair, never tallied
/// \*                 (isPastPhase keyed on `< current`); the known-bad keys it on the slot's data being present.
/// \*   curslot     - the current-slot gates: canVoteRound1/canVoteRound2/canMakeDecision require currentPhase = phase,
/// \*                 handleDecision drops a Decision below the current slot and buffers one above it. Modelled as a
/// \*                 member because D9's safety claim rests on it too.
/// \*   rosterballot- D11: acceptsBallot counts only installed roster members (isVoter(sender)).
/// \*   markerfirst - D11/#1212: activate refuses unless the participation marker is durably recorded first.
/// \*   emptyprop   - D8 (liveness): a voter with an empty queue joins (or opens, after activation) a slot with an
/// \*                 empty proposal instead of staying silent.
/// \*
/// \* Environment and assumption constants. RogueVoter has cfgs that set it TRUE; the ctl_byz_* cfgs set Byz (negative
/// \* controls for Agreement and Validity); every cfg fixes CommonCoin = TRUE, so the FALSE coin arm is never checked:
/// \*   RogueVoter  - the observer runs the voter logic: a non-roster node whose stale local roster says it votes
/// \*                 (RabiaVoterReconfigurationTest.removedVoter_ballotAtCurrentEpoch_isNotCounted). Adversity, not a fix.
/// \*   CommonCoin  - D1: TRUE = coinFlip, a function of (slot, epoch, round) only; FALSE = any coin per replica (control).
/// \*   Byz         - voters that may equivocate (send two values for one ballot) or send a Decision nobody decided.
/// \*                 The code assumes Byz = {}.
/// \*
/// \* Abstractions (what each loses): one epoch, no reconfiguration (RabiaEpoch); no sync, snapshot,
/// \* crash or amnesia (RabiaAdopt); a message is a member of a set that is never removed, so it may be delivered
/// \* to each receiver any number of times, at any time (the code's re-broadcasts and replays make duplicates real);
/// \* registration is putIfAbsent, so a duplicate changes nothing unless the slot's data was dropped. Repair is a
/// \* no-op: Decision messages are already in the set. No learnProposal (D7): queues are never repaired.
/// EXTENDS Naturals, FiniteSets, TLC
///
/// CONSTANTS Voters, Observers, MaxSlot, MaxRound, GcDepth, NVals, Fixed, RogueVoter, CommonCoin, Byz
///
/// AllFixes == {"pastguard", "curslot", "rosterballot", "markerfirst", "emptyprop"}
/// Fix(f) == f \in Fixed
/// ASSUME Fixed \subseteq AllFixes
/// ASSUME Byz \subseteq Voters /\ Voters \cap Observers = {}
/// ASSUME NVals \in {1, 2}
/// ASSUME RogueVoter \in BOOLEAN /\ CommonCoin \in BOOLEAN
///
/// Nodes == Voters \cup Observers
/// Slots == 1..MaxSlot
/// Rounds == 0..(MaxRound - 1)
/// Values == IF NVals = 1 THEN {"a"} ELSE {"a", "b"}   \* NVals = 1: one batch id (size bound)
/// Empty == "empty"
/// NoVal == "none"
/// Pick(S) == IF "a" \in S THEN "a" ELSE "b"        \* lowest pending id (nextProposal: pendingBatches.firstEntry)
///
/// Q == Cardinality(Voters) \div 2 + 1             \* voterQuorum
/// F1 == (Cardinality(Voters) - 1) \div 2 + 1      \* voterFPlusOne
///
/// \* A node runs the voter logic: a roster member, or the observer when it is rogue.
/// Acts(n) == n \in Voters \/ RogueVoter
///
/// VARIABLES
///     msgs,     \* every message ever sent: [t, src, slot, rnd, val]; never removed
///     reg,      \* node -> registered messages (PhaseData proposals and ballots, own included)
///     has,      \* node -> slots with PhaseData (phases.keySet())
///     rnd,      \* node -> slot -> PhaseData.round
///     dflag,    \* node -> slots whose PhaseData.decided is set
///     capped,   \* node -> slots that hit the MaxRound bound (model artefact, not code)
///     cur,      \* node -> currentPhase
///     pending,  \* node -> pending batch ids
///     active,   \* node -> activated (InPhase/Idle allowed)
///     fresh,    \* node -> activated and has not proposed since (openSlotAfterChange)
///     marker,   \* node -> participation marker durably recorded
///     logv,     \* node -> slot -> applied value (state machine; survives GC)
///     napp,     \* node -> slot -> times the slot was applied (capped at 2)
///     pend0     \* ghost: initial pending per node (Validity)
///
/// vars == <<msgs, reg, has, rnd, dflag, capped, cur, pending, active, fresh, marker, logv, napp, pend0>>
///
/// Key(m) == <<m.t, m.src, m.slot, m.rnd>>
/// Unseen(n, m) == \A x \in reg[n] : Key(x) # Key(m)
/// PropsAt(n, s) == {x \in reg[n] : x.t = "P" /\ x.slot = s}
/// Ballots(n, t, s, r) == {x \in reg[n] : x.t = t /\ x.slot = s /\ x.rnd = r}
/// Count(n, t, s, r, v) == Cardinality({x \in Ballots(n, t, s, r) : x.val = v})
/// Own(n, t, s, r) == {x \in Ballots(n, t, s, r) : x.src = n}
/// \* agreedValue: a non-empty proposal held by a quorum (proposalValues filters empty ones).
/// Agreed(n, s) == {v \in Values : Cardinality({x \in PropsAt(n, s) : x.val = v}) >= Q}
///
/// \* isPastPhase: the code keys it on `< current`; the known-bad also needs the slot's data present.
/// PastRoute(n, s) == IF Fix("pastguard") THEN s < cur[n] ELSE s < cur[n] /\ s \in has[n]
/// \* acceptsBallot. The rogue observer counts every sender (its stale roster includes itself).
/// Accepts(n, src) == IF n \in Observers \/ ~Fix("rosterballot") THEN TRUE ELSE src \in Voters
/// \* Slots a node may vote, decide or apply in.
/// VoteSlots(n) == IF Fix("curslot") THEN {cur[n]} \cap Slots ELSE has[n]
///
/// Coin(s, r) == IF (s + r) % 2 = 0 THEN "V0" ELSE "V1"   \* coinFlip: parity of phase + epoch + round, epoch 0
/// CoinVals(s, r) == IF CommonCoin THEN {Coin(s, r)} ELSE {"V0", "V1"}
///
/// Bcast(n, m) ==
///     /\ msgs' = msgs \cup {m}
///     /\ reg' = [reg EXCEPT ![n] = @ \cup {m}]
///     /\ has' = [has EXCEPT ![n] = @ \cup {m.slot}]
///
/// \* commitDecision: tryMarkDecided on the slot's PhaseData, apply, advancePhase (max). Decide broadcasts the Decision.
/// Commit(n, s, v, bcast) ==
///     /\ dflag' = [dflag EXCEPT ![n] = @ \cup {s}]
///     /\ has' = [has EXCEPT ![n] = @ \cup {s}]
///     /\ logv' = [logv EXCEPT ![n][s] = v]
///     /\ napp' = [napp EXCEPT ![n][s] = IF @ < 2 THEN @ + 1 ELSE 2]
///     /\ pending' = [pending EXCEPT ![n] = @ \ {v}]
///     /\ cur' = [cur EXCEPT ![n] = IF s + 1 > @ THEN s + 1 ELSE @]
///     /\ msgs' = IF bcast THEN msgs \cup {[t |-> "D", src |-> n, slot |-> s, rnd |-> 0, val |-> v]} ELSE msgs
///
/// \* Environment moves (not code; they sit in the skeleton).
/// \* Assumption removal only: a voter that sends two different ballots for one (slot, round). The code
/// \* assumes none.
/// Equivocate(n) ==
///     /\ n \in Byz /\ active[n]
///     /\ \E x \in msgs, v \in {"V0", "V1"} :
///         /\ x.src = n /\ x.t \in {"R1", "R2"} /\ x.val # v
///         /\ [x EXCEPT !.val = v] \notin msgs
///         /\ msgs' = msgs \cup {[x EXCEPT !.val = v]}
///     /\ UNCHANGED <<reg, has, rnd, dflag, capped, cur, pending, active, fresh, marker, logv, napp, pend0>>
///
/// \* Assumption removal only: a Byzantine voter sends a Decision for any value, decided or not.
/// ForgeDecision(n) ==
///     /\ n \in Byz
///     /\ \E s \in Slots, v \in Values :
///         /\ [t |-> "D", src |-> n, slot |-> s, rnd |-> 0, val |-> v] \notin msgs
///         /\ msgs' = msgs \cup {[t |-> "D", src |-> n, slot |-> s, rnd |-> 0, val |-> v]}
///     /\ UNCHANGED <<reg, has, rnd, dflag, capped, cur, pending, active, fresh, marker, logv, napp, pend0>>
/// ```
///
/// ```tla RabiaSlot:tail
/// Init ==
///     /\ msgs = {}
///     /\ reg = [n \in Nodes |-> {}]
///     /\ has = [n \in Nodes |-> {}]
///     /\ rnd = [n \in Nodes |-> [s \in Slots |-> 0]]
///     /\ dflag = [n \in Nodes |-> {}]
///     /\ capped = [n \in Nodes |-> {}]
///     /\ cur = [n \in Nodes |-> 1]
///     /\ pending \in [Nodes -> SUBSET Values]
///     /\ \A n \in Nodes : Cardinality(pending[n]) <= 1 /\ (n \in Observers => pending[n] = {})   \* size bound
///     /\ pend0 = pending
///     /\ active = [n \in Nodes |-> FALSE]
///     /\ fresh = [n \in Nodes |-> FALSE]
///     /\ marker = [n \in Nodes |-> FALSE]
///     /\ logv = [n \in Nodes |-> [s \in Slots |-> NoVal]]
///     /\ napp = [n \in Nodes |-> [s \in Slots |-> 0]]
///
/// Step(n) ==
///     \/ Activate(n) \/ RecordMarker(n) \/ Propose(n) \/ Gc(n)
///     \/ \E m \in msgs : Receive(n, m) \/ Learn(n, m)
///     \/ \E s \in Slots : Round1(n, s) \/ Round2(n, s) \/ Decide(n, s)
///
/// Next == \/ \E n \in Nodes : Step(n)
///         \/ \E n \in Byz : Equivocate(n) \/ ForgeDecision(n)
///
/// Sym == Permutations(Voters)   \* voters are interchangeable (pending is chosen per node); safety cfgs without Byz only
///
/// Spec == Init /\ [][Next]_vars
/// \* Fairness for the voters only; the observer and equivocation are adversity, never forced.
/// LiveSpec == Spec /\ \A n \in Voters : WF_vars(Step(n))
///
/// \* ---------------- properties ----------------
/// \* Agreement, NoRedecide, Validity and LogPrefix range over every node: an observer applies the log too.
/// Agreement == \A n, m \in Nodes, s \in Slots :
///                 logv[n][s] # NoVal /\ logv[m][s] # NoVal => logv[n][s] = logv[m][s]
/// NoRedecide == \A n \in Nodes, s \in Slots : napp[n][s] <= 1
/// Validity == \A n \in Nodes, s \in Slots :
///                 logv[n][s] \in Values => logv[n][s] \in UNION {pend0[v] : v \in Voters}
/// LogPrefix == \A n \in Nodes, s \in Slots : s < cur[n] => logv[n][s] # NoVal
/// VotedImpliesMarker == \A m \in msgs : m.src \in Voters => marker[m.src]
///
/// Opened(s) == \E m \in msgs : m.t = "P" /\ m.slot = s /\ m.src \in Voters
/// CapHit(s) == \E n \in Voters : s \in capped[n]
/// Live == \A s \in Slots : Opened(s) ~> ((\A n \in Voters : cur[n] > s) \/ CapHit(s))
/// ```
///
/// ```tla RabiaEpoch:head
/// \* Rabia deviations D2 (decision relay), D6 (in-log reconfiguration: epoch per slot boundary, exact-epoch ballot
/// \* counting, newer-epoch deferral) and D12 (leader layer as a requester set) of pragmatica#3164.
/// \*
/// \* Fixed (each fix a member; every cfg assigns it):
/// \*   "exactepoch" acceptsBallot counts a ballot only at epoch == voterEpoch() (known-bad: epoch <= current)
/// \*   "relayepoch" handleDecision refuses a current-slot Decision whose epoch != voterEpoch()
/// \*   "retainmaj"  ReconfigurationCommand.retainsTargetMajority in admission and in appliesTo (liveness guard)
/// \* Assume (code-relied environment; every cfg assigns it):
/// \*   "prefix"     a replica enters slot s+1 only by committing slot s (code comment at handleDecision: "the epoch
/// \*                governing a slot is a function of the applied prefix"). Removed: Jump (JumpShape "code":
/// \*                applyRestoredState as written) or FreeJump + FreeLearn (JumpShape "free": slot and roster
/// \*                arrive separately, a superset of the code).
/// \*   "crashstop"  a relayed Decision is a decision some replica made (no fabricated evidence). Removed: Forge.
///
/// EXTENDS Naturals, FiniteSets, TLC
///
/// CONSTANTS Fixed, Assume, Paper, Roster0, TargetSet, Batches, MaxSlot, MaxRequests, RequestEpochs, MaxJump, MaxForge, Slow, JumpShape, ColdShapes
///
/// KnownFixes  == {"exactepoch", "relayepoch", "retainmaj"}
/// KnownAssume == {"prefix", "crashstop"}
/// KnownPaper  == {"norelay", "anyepoch", "nopreferred"}
/// ASSUME Fixed \subseteq KnownFixes /\ Assume \subseteq KnownAssume /\ Paper \subseteq KnownPaper
/// ASSUME JumpShape \in {"code", "free"} /\ ColdShapes \subseteq BOOLEAN   \* ColdShapes: {FALSE} = LIVE responses only
///
/// \* Paper-faithful switches (report whether our rule closes a gap the paper leaves). Each
/// \* replaces ONE of our rules by what weak-MVC with fixed membership does; every other fix stays on.
/// \*   "norelay"     D2: a replica decides only from its own tally (no handleDecision relay)
/// \*   "anyepoch"    D6: a ballot counts by (slot, sender in roster) with no epoch tag at all
/// \*   "nopreferred" D12: no preferredOver convergence; a replica keeps the first command it adopted
///
/// Fix(f) == f \in Fixed
/// Has(a) == a \in Assume
/// Pap(p) == p \in Paper
///
/// NodeSeq == <<"A", "B", "C", "D">>
/// Nodes   == {NodeSeq[i] : i \in 1..4}
/// Rank(n) == CHOOSE i \in 1..4 : NodeSeq[i] = n
///
/// Slots == 1..MaxSlot
/// \* Every value is one record shape so TLC never compares a string with a record.
/// V(k, e, t, x) == [k |-> k, base |-> e, target |-> t, b |-> x]
/// None  == V("none", 0, {}, "-")
/// Null  == V("null", 0, {}, "-")                                  \* the V0 decision
/// Cmds  == {V("cmd", e, t, "-") : e \in RequestEpochs, t \in TargetSet}
/// BatchVals == {V("batch", 0, {}, x) : x \in Batches}
/// Values == Cmds \cup BatchVals \cup {Null}
/// Quorum(r) == Cardinality(r) \div 2 + 1
///
/// \* ReconfigurationCommand.key(): comma-joined sorted ids; ids are one letter, so lexicographic order on the sorted
/// \* member list (a proper prefix first) is the String order.
/// MinRank(s) == CHOOSE n \in s : \A m \in s : Rank(n) <= Rank(m)
/// RECURSIVE KeyLess(_, _)
/// KeyLess(s, t) ==
///     IF s = {} THEN t # {}
///     ELSE IF t = {} THEN FALSE
///     ELSE LET a == MinRank(s)
///              b == MinRank(t)
///          IN IF Rank(a) # Rank(b) THEN Rank(a) < Rank(b) ELSE KeyLess(s \ {a}, t \ {b})
///
/// \* ReconfigurationCommand.preferredOver (:56)
/// Preferred(c1, c2) == c1.base > c2.base \/ (c1.base = c2.base /\ KeyLess(c1.target, c2.target))
///
/// \* ReconfigurationCommand.appliesTo (:40): base epoch, a real change, retained majority.
/// AppliesTo(e, r, c) ==
///     /\ c.base = e
///     /\ c.target # r
///     /\ Fix("retainmaj") => Cardinality(c.target \cap r) >= Quorum(c.target)
///
/// VARIABLES slot,       \* currentPhase per replica (MaxSlot+1 = done)
///           epoch,      \* voterEpoch()
///           roster,     \* voters.members()
///           requested,  \* requestedConfiguration (a Cmd or None)
///           decided,    \* committed value per slot (None = not committed)
///           decEpoch,   \* epoch the replica held when it committed the slot (Decision.epoch it relays)
///           ballots,    \* every proposal ever sent: [n, s, e, v]; a set, so each is delivered and never lost
///           requests,   \* ReconfigurationRequests broadcast so far
///           installs,   \* ghost: every <<epoch, roster>> any replica installed
///           jumpers,    \* ghost: replicas that left a slot without committing it (assumption "prefix" removed)
///           forged,     \* ghost: fabricated relays so far (assumption "crashstop" removed)
///           mixed       \* ghost: some tally counted a ballot of an epoch other than the tallier's
///
/// vars == <<slot, epoch, roster, requested, decided, decEpoch, ballots, requests, installs, jumpers, forged, mixed>>
///
/// Voter(n)  == n \in roster[n]
/// Active(n) == slot[n] \in Slots /\ decided[n][slot[n]] = None
///
/// \* settleRequests: a request whose base epoch has passed is dropped.
/// Settled(c, e) == IF c # None /\ c.base = e THEN c ELSE None
///
/// \* commitDecision -> applyReconfiguration -> installAppliedChange -> advancePhase. Only a V1 command applies.
/// Commit(n, v) ==
///     LET s     == slot[n]
///         e     == epoch[n]
///         apply == v.k = "cmd" /\ AppliesTo(e, roster[n], v)
///         e2    == IF apply THEN e + 1 ELSE e
///         r2    == IF apply THEN v.target ELSE roster[n]
///     IN /\ decided'   = [decided EXCEPT ![n][s] = v]
///        /\ decEpoch'  = [decEpoch EXCEPT ![n][s] = e]
///        /\ slot'      = [slot EXCEPT ![n] = s + 1]
///        /\ epoch'     = [epoch EXCEPT ![n] = e2]
///        /\ roster'    = [roster EXCEPT ![n] = r2]
///        /\ requested' = [requested EXCEPT ![n] = Settled(requested[n], e2)]
///        /\ installs'  = IF apply THEN installs \cup {<<e2, r2>>} ELSE installs
///
/// \* acceptsBallot (:964) at the current slot (past slots go to repair). A newer-epoch ballot fails both branches and
/// \* stays in `ballots`: that is deferIfNewerEpoch + drainDeferredBallots with an unbounded queue.
/// Accepts(n, b) ==
///     /\ b.s = slot[n]
///     /\ b.n \in roster[n]
///     /\ IF Pap("anyepoch") THEN TRUE ELSE IF Fix("exactepoch") THEN b.e = epoch[n] ELSE b.e <= epoch[n]
///
/// Seen(n)       == {b \in ballots : Accepts(n, b)}
/// Backers(n, v) == {m \in roster[n] : \E b \in Seen(n) : b.n = m /\ b.v = v}
///
/// \* --- environment moves (not code), enabled only when an assumption is removed ---
///
/// \* JumpShape "free": a replica reaches slot s+1 without committing s and without the roster that governs it.
/// FreeJump(n) ==
///     /\ ~Has("prefix")
///     /\ JumpShape = "free"
///     /\ Cardinality(jumpers) < MaxJump
///     /\ Active(n)
///     /\ slot[n] < MaxSlot
///     /\ slot'    = [slot EXCEPT ![n] = @ + 1]
///     /\ jumpers' = jumpers \cup {n}
///     /\ UNCHANGED <<epoch, roster, requested, decided, decEpoch, ballots, requests, installs, forged, mixed>>
///
/// \* JumpShape "free": adoptSyncedConfiguration separated from its slot install (the epoch never regresses).
/// FreeLearn(n, p) ==
///     /\ ~Has("prefix")
///     /\ JumpShape = "free"
///     /\ slot[n] = slot[p]
///     /\ epoch[p] > epoch[n]
///     /\ epoch'     = [epoch EXCEPT ![n] = epoch[p]]
///     /\ roster'    = [roster EXCEPT ![n] = roster[p]]
///     /\ requested' = [requested EXCEPT ![n] = Settled(requested[n], epoch[p])]
///     /\ UNCHANGED <<slot, decided, decEpoch, ballots, requests, installs, jumpers, forged, mixed>>
/// ```
///
/// ```tla RabiaEpoch:tail
/// Init ==
///     /\ slot      = [n \in Nodes |-> 1]
///     /\ epoch     = [n \in Nodes |-> 0]
///     /\ roster    = [n \in Nodes |-> Roster0]
///     /\ requested = [n \in Nodes |-> None]
///     /\ decided   = [n \in Nodes |-> [s \in Slots |-> None]]
///     /\ decEpoch  = [n \in Nodes |-> [s \in Slots |-> 0]]
///     /\ ballots   = {}
///     /\ requests  = {}
///     /\ installs  = {<<0, Roster0>>}
///     /\ jumpers   = {}
///     /\ forged    = 0
///     /\ mixed     = FALSE
///
/// Next ==
///     \/ \E c \in Cmds : Request(c)
///     \/ \E n \in Nodes, c \in Cmds : Adopt(n, c)
///     \/ \E n \in Nodes, v \in Cmds \cup BatchVals : Vote(n, v)
///     \/ \E n \in Nodes : Decide(n) \/ DecideNull(n) \/ Jump(n) \/ FreeJump(n) \/ Forge(n)
///     \/ \E p, q \in Nodes : Relay(p, q) \/ FreeLearn(p, q)
///
/// Spec == Init /\ [][Next]_vars
///
/// \* Liveness: replicas outside Slow are weakly fair (Slow = a joiner that may lag without bound).
/// Fairness ==
///     \A n \in Nodes \ Slow :
///         /\ WF_vars(\E v \in Cmds \cup BatchVals : Vote(n, v))
///         /\ WF_vars(Decide(n))
///         /\ WF_vars(DecideNull(n))
///         /\ WF_vars(\E p \in Nodes : Relay(p, n))
///
/// LiveSpec == Spec /\ Fairness
///
/// \* --- properties ---
///
/// TypeOK ==
///     /\ slot \in [Nodes -> 1..(MaxSlot + 1)]
///     /\ requested \in [Nodes -> Cmds \cup {None}]
///     /\ decided \in [Nodes -> [Slots -> Values \cup {None}]]
///
/// \* Agreement across epochs: no slot is committed with two values.
/// Agreement ==
///     \A n, m \in Nodes, s \in Slots :
///         decided[n][s] # None /\ decided[m][s] # None => decided[n][s] = decided[m][s]
///
/// \* One roster per epoch, over every install any replica ever made.
/// OneRosterPerEpoch == \A x, y \in installs : x[1] = y[1] => x[2] = y[2]
///
/// \* No tally ever counted a ballot of an epoch other than the tallier's (reachability witness for the exactepoch guard).
/// NoOldEpochTally == ~mixed
///
/// \* Every proposal carries the epoch that governs its slot, as fixed by any replica that committed the slot in order.
/// GovernedVote ==
///     \A b \in ballots, m \in Nodes \ jumpers :
///         decided[m][b.s] # None => b.e = decEpoch[m][b.s]
///
/// \* Liveness: the retained voter A commits every slot even when the joiner never takes a step.
/// Progress == <>(\A s \in Slots : decided["A"][s] # None)
/// ```
///
/// ```tla RabiaAdopt:head
/// \* Rabia variant: recovery and sync adoption (deviations D3, D4, D5 from the Rabia paper, listed in the
/// \* package documentation above).
/// \*
/// \* Fixed (each member a fix; a cfg that drops one is a known-bad):
/// \*   "amnesiafence" (D3)  peers refuse every message from a restarted process under the same NodeId
/// \*   "selfvouch"    (D4)  self counts toward n/2 only with durable history (floor > ZERO) or marker NEVER; else n/2+1
/// \*   "oneconfig"    (D5)  newer-epoch adoption needs >= 1 LIVE responder and all live responders claim ONE configuration;
/// \*                        a same-epoch response with a different roster is ineligible
/// \* Paper (paper-faithful / naive rule replacing ours, other fixes kept):
/// \*   "D3"  crash-recovery with no fence: a restarted replica rejoins directly with its durable state
/// \*   "D4"  adopt from ANY single response (no live/self/observer distinction)
/// \*   "D5"  newer epoch adopted from any single response, live or not, no configuration check
/// \*   "D5q" (comparison, not paper) newer epoch needs a LIVE majority (n/2+1) claiming one configuration;
/// \*         the response-quorum rule the code rejects because it wedges #1526 (liveness not checked here)
/// \* Assumptions as constants:
/// \*   KeepBallots  the process keeps its ballot memory across pause and adoption (phases.clear() runs only in
/// \*                shutdownAndReset, the stop path; GC drops only expired past slots)
/// \*   FaultyResp   node "C" may answer sync with an arbitrary well-formed log (a non-crash fault)
/// \*   Probe        which adoption branch the sticky ghost `reached` records ("none" in property cfgs)
/// \*   SameEpochAdopt  FALSE disables the same-epoch adoption branches, isolating the D5 newer-epoch path (diagnostic)
/// \*   SaveFails    TRUE lets saveState on Pause fail (doPauseForQuorumLoss logs the failure and stays paused)
/// EXTENDS Naturals, FiniteSets
///
/// CONSTANTS Fixed, Paper, KeepBallots, FaultyResp, SameEpochAdopt, Probe, MaxCrash, MaxRestart, MaxPause, Rejoin, SaveFails
///
/// ASSUME Fixed \subseteq {"amnesiafence", "selfvouch", "oneconfig"}
/// ASSUME Paper \subseteq {"D3", "D4", "D5", "D5q"}
/// ASSUME Rejoin \in {"none", "wiped", "any", "tokens"}
///
/// Ids    == {"A", "B", "C", "X"}          \* X: a fresh id (new ULID), never balloted
/// R1     == {"A", "B", "C"}               \* epoch-1 roster
/// Slots  == {1, 2}
/// None   == "none"
/// \* Slot 1 decides a reconfiguration (epoch 1 -> 2); slot 2 is a data slot in epoch 2.
/// Vals(s) == IF s = 1 THEN {"r1", "r2"} ELSE {"a", "b"}
/// RosterOf(c) == IF c = "r1" THEN {"A", "B", "X"} ELSE {"A", "C", "X"}
///
/// Empty      == [s \in Slots |-> None]
/// Prefix(l, k) == [s \in Slots |-> IF s <= k THEN l[s] ELSE None]
/// Len(l)     == Cardinality({s \in Slots : l[s] # None})
/// AllLogs    == {Empty} \cup {[s \in Slots |-> IF s = 1 THEN c ELSE None] : c \in Vals(1)}
///                      \cup {[s \in Slots |-> IF s = 1 THEN c ELSE d] : c \in Vals(1), d \in Vals(2)}
/// Epoch(l)   == IF l[1] = None THEN 1 ELSE 2
/// Roster(l)  == IF l[1] = None THEN R1 ELSE RosterOf(l[1])
/// Max(S)     == CHOOSE m \in S : \A x \in S : m >= x
///
/// VARIABLES st, log, saved, marker, admit, known, reinc, voted, ballots, chosen, crashes, restarts, pauses, reached
/// \* reached: sticky ghost, TRUE once an adoption matching Probe happened (reachability controls only)
/// vars == <<st, log, saved, marker, admit, known, reinc, voted, ballots, chosen, crashes, restarts, pauses, reached>>
///
/// TypeOK ==
///   /\ st \in [Ids -> {"down", "active", "paused", "syncing"}]
///   /\ log \in [Ids -> AllLogs] /\ saved \in [Ids -> AllLogs]
///   /\ marker \in [Ids -> {"never", "participated", "unknown"}]
///   /\ admit \in [Ids -> SUBSET Ids] /\ known \in [Ids -> SUBSET Ids] /\ reinc \in [Ids -> BOOLEAN]
///   /\ ballots \subseteq (Ids \X Slots \X {"r1", "r2", "a", "b"} \X BOOLEAN)
///
/// \* The slot this node may work on, and that slot's roster (exact epoch: derived from the decided prefix).
/// Open(n)   == IF log[n][1] = None THEN 1 ELSE IF log[n][2] = None THEN 2 ELSE 0
/// \* Link: p and q exchange messages. A restarted incarnation q is refused by every peer that still holds the old
/// \* boot token (BootTokens.java:34-37); admit[q] = the peers that do NOT (restarted since, or never saw the old token).
/// Link(p, q) == p = q \/ (p \in admit[q] /\ q \in admit[p])
/// \* Ballot b cast by k (flag i = cast by a restarted incarnation) is known to node m.
/// Counted(m, k, i) == IF m = k THEN (i \/ ~reinc[m]) ELSE (Link(m, k) \/ (~i /\ ~reinc[m]))
///
/// \* New process under the same NodeId: volatile state lost (D3: ballots never persisted).
/// \* wipe: in-memory persistence or a wiped disk (durable floor ZERO, marker unreadable).
/// \* A = the peers that admit the new incarnation. Without the fence every peer does. With it, only peers that lost the
/// \* old token do (Rejoin = "none": none, a permanent fence; "wiped": also for a wiped incarnation; "any": also one with its last save).
/// \* Abstraction of per-peer token memory (process memory only, BootTokens.java:43-46) by a nondeterministic subset: one peer or all.
/// Allowed(n, wipe, A) ==
///   IF "amnesiafence" \notin Fixed THEN A = Ids
///   ELSE IF Rejoin = "tokens"
///     THEN A = Ids \ ({n} \cup {p \in Ids \ {n} : n \in known[p] /\ st[p] # "down"})
///   ELSE \/ A = {}
///        \/ /\ Rejoin # "none" /\ (Rejoin = "any" \/ wipe)
///           /\ A \subseteq Ids \ {n} /\ (Cardinality(A) = 1 \/ A = Ids \ {n})
///
/// \* What responder r may answer: its log as of some earlier moment (lag), or anything if faulty.
/// Options(r) == IF FaultyResp /\ r = "C" THEN AllLogs ELSE {Prefix(log[r], k) : k \in 0..2}
/// Live(r) == st[r] = "active"
///
/// \* ownStateFloor: the more advanced of the persisted and the LIVE phase. They differ only after a Pause whose save
/// \* failed (SaveFails) and a Resume into syncing; Restart sets log to saved (or wipes both).
/// Floor(n) == IF Len(saved[n]) >= Len(log[n]) THEN Len(saved[n]) ELSE Len(log[n])
///
/// \* Install an adopted state: persisted (saveState at restore), active iff self is in the adopted roster.
/// \* A response set wholly behind Floor(n) disables Adopt; the code's activateWithoutAdoption branch (own state, no
/// \* adoption) is not modelled (under-approximation).
/// Install(n, ad) ==
///   /\ Len(ad) >= Floor(n)                                 \* adoptCollectedState: never behind own state
///   /\ log' = [log EXCEPT ![n] = ad]
///   /\ saved' = [saved EXCEPT ![n] = ad]
///   /\ IF n \in Roster(ad)
///        THEN /\ st' = [st EXCEPT ![n] = "active"]
///             /\ marker' = [marker EXCEPT ![n] = "participated"]   \* recordParticipation before activate
///        ELSE /\ st' = st /\ marker' = marker
///   /\ voted' = IF KeepBallots THEN voted ELSE [voted EXCEPT ![n] = Empty]
///   /\ UNCHANGED <<admit, known, reinc, ballots, chosen, crashes, restarts, pauses>>
///
/// Hit(tg) ==
///   CASE Probe = "newerlag"     -> tg.path = "newer" /\ tg.lag
///     [] Probe = "live2novouch" -> tg.path = "live" /\ tg.n = 2 /\ ~tg.vouch
///     [] Probe = "live1novouch" -> tg.path = "live" /\ tg.n = 1 /\ ~tg.vouch
///     [] Probe = "claimed2"     -> tg.path = "newer" /\ tg.claimed > 1
///     [] Probe = "newernonlive" -> tg.path = "newer" /\ tg.claimed = 0
///     [] Probe = "rejoinamnesiac" -> tg.rj /\ ~tg.vouch      \* rejoined wiped incarnation completes an adoption
///     [] Probe = "rejoinamnesiac1" -> tg.rj /\ ~tg.vouch /\ tg.path = "live" /\ tg.n = 1  \* ... on ONE response through the live rule (the self-vouch bound)
///     [] Probe = "rejoinstale" -> tg.rj /\ tg.dur              \* rejoined incarnation with its last save adopts
///     [] Probe = "rejoin" -> tg.rj                          \* ANY adoption completed by a rejoined incarnation (non-vacuity of the Rejoin modes)
///     [] Probe = "rejoinwiped" -> tg.rj /\ ~tg.dur          \* ... by a rejoined incarnation holding no durable state
///     [] Probe = "rejoinstale1" -> tg.rj /\ tg.dur /\ tg.path = "live" /\ tg.n = 1
///     [] OTHER                  -> FALSE
///
/// PickMax(n, rl, CS, tag) ==
///   /\ CS # {}
///   /\ LET m == Max({Len(rl[r]) : r \in CS}) IN
///        \E r \in CS : /\ Len(rl[r]) = m /\ Install(n, rl[r])
///                      /\ reached' = (reached \/ Hit([tag EXCEPT !.lag = Len(rl[r]) < Len(log[r])]))
/// ```
///
/// ```tla RabiaAdopt:tail
/// Init ==
///   /\ st = [n \in Ids |-> IF n = "X" THEN "syncing" ELSE "active"]
///   /\ log = [n \in Ids |-> Empty]
///   /\ saved = [n \in Ids |-> Empty]
///   /\ marker = [n \in Ids |-> IF n = "X" THEN "never" ELSE "participated"]
///   /\ admit = [n \in Ids |-> Ids] /\ known = [n \in Ids |-> Ids \ {n}] /\ reinc = [n \in Ids |-> FALSE]
///   /\ voted = [n \in Ids |-> Empty]
///   /\ ballots = {}
///   /\ chosen = [s \in Slots |-> {}]
///   /\ crashes = 0 /\ restarts = 0 /\ pauses = 0
///   /\ reached = FALSE
///
/// Next ==
///   \/ /\ \/ \E n \in Ids : \E v \in {"r1", "r2", "a", "b"} : Vote(n, v) \/ Decide(n, v)
///         \/ \E n \in Ids, b \in BOOLEAN : Pause(n, b) \/ Resume(n, b)
///         \/ \E n \in Ids, g \in BOOLEAN : Crash(n, g) \/ (\E A \in SUBSET Ids : Restart(n, g, A))
///      /\ UNCHANGED reached
///   \/ \E n \in Ids : Adopt(n)
///
/// Spec == Init /\ [][Next]_vars
///
/// \* Agreement: one value per slot, ever.
/// Agreement == \A s \in Slots : Cardinality(chosen[s]) <= 1
/// \* Every value a node holds was decided, and a decided slot a running process holds never regresses.
/// Held == \A n \in Ids, s \in Slots : log[n][s] # None => log[n][s] \in chosen[s]
/// DurableDecision ==
///   [][\A n \in Ids, s \in Slots : (st[n] # "down" /\ log[n][s] # None) => log'[n][s] = log[n][s]]_vars
///
/// \* Reachability controls: each control cfg sets Probe and checks Reach (or ReachX2); EXPECTED violated
/// \* (the branch is reached), except Probe "claimed2" which is the unreachability claim.
/// Reach   == ~reached
/// ReachX2 == ~(st["X"] = "active" /\ log["X"][2] # None)
/// ```
///
/// ```tlacfg RabiaSlot:code
/// \* RabiaSlot config code: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {}
///     MaxSlot = 1
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 1
///     Fixed = {"pastguard", "curslot", "rosterballot", "markerfirst", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {}
/// INVARIANTS Agreement NoRedecide Validity LogPrefix VotedImpliesMarker
/// SYMMETRY Sym
/// ```
///
/// ```tlacfg RabiaSlot:nocurslot
/// \* RabiaSlot config nocurslot: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {}
///     MaxSlot = 1
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 1
///     Fixed = {"pastguard", "rosterballot", "markerfirst", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {}
/// INVARIANTS Agreement NoRedecide Validity LogPrefix VotedImpliesMarker
/// SYMMETRY Sym
/// ```
///
/// ```tlacfg RabiaSlot:noemptyprop
/// \* RabiaSlot config noemptyprop: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION LiveSpec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {}
///     MaxSlot = 1
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 1
///     Fixed = {"pastguard", "curslot", "rosterballot", "markerfirst"}
///     RogueVoter = FALSE
///     CommonCoin = TRUE
///     Byz = {}
/// PROPERTIES Live
/// ```
///
/// ```tlacfg RabiaSlot:nomarkerfirst
/// \* RabiaSlot config nomarkerfirst: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {o}
///     MaxSlot = 1
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 1
///     Fixed = {"pastguard", "curslot", "rosterballot", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {}
/// INVARIANTS Agreement NoRedecide Validity LogPrefix VotedImpliesMarker
/// SYMMETRY Sym
/// ```
///
/// ```tlacfg RabiaSlot:nopastcur
/// \* RabiaSlot config nopastcur: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {}
///     MaxSlot = 1
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 1
///     Fixed = {"rosterballot", "markerfirst", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {}
/// INVARIANTS Agreement NoRedecide Validity LogPrefix VotedImpliesMarker
/// SYMMETRY Sym
/// ```
///
/// ```tlacfg RabiaSlot:nopastguard
/// \* RabiaSlot config nopastguard: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {}
///     MaxSlot = 1
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 1
///     Fixed = {"curslot", "rosterballot", "markerfirst", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {}
/// INVARIANTS Agreement NoRedecide Validity LogPrefix VotedImpliesMarker
/// SYMMETRY Sym
/// ```
///
/// ```tlacfg RabiaSlot:code_r2
/// \* RabiaSlot config code_r2: the expected verdict is its row in tools/tla/models.tsv
/// \* the code at two rounds and two batch ids: carry-forward and the coin (D1) are exercised
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {}
///     MaxSlot = 1
///     MaxRound = 2
///     GcDepth = 0
///     NVals = 2
///     Fixed = {"pastguard", "curslot", "rosterballot", "markerfirst", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {}
/// INVARIANTS Agreement NoRedecide Validity LogPrefix VotedImpliesMarker
/// SYMMETRY Sym
/// ```
///
/// ```tlacfg RabiaSlot:code_obs
/// \* RabiaSlot config code_obs: the expected verdict is its row in tools/tla/models.tsv
/// \* the code with a rogue observer whose Decisions are learned (trusted peer)
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {o}
///     MaxSlot = 1
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 2
///     Fixed = {"pastguard", "curslot", "rosterballot", "markerfirst", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {}
/// INVARIANTS Agreement NoRedecide Validity LogPrefix VotedImpliesMarker
/// SYMMETRY Sym
/// ```
///
/// ```tlacfg RabiaSlot:norosterballot
/// \* RabiaSlot config norosterballot: the expected verdict is its row in tools/tla/models.tsv
/// \* D11 receiver-side roster check dropped, with a rogue observer voting
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {o}
///     MaxSlot = 1
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 2
///     Fixed = {"pastguard", "curslot", "markerfirst", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {}
/// INVARIANTS Agreement NoRedecide Validity LogPrefix VotedImpliesMarker
/// SYMMETRY Sym
/// ```
///
/// ```tlacfg RabiaSlot:nopastguard2
/// \* RabiaSlot config nopastguard2: the expected verdict is its row in tools/tla/models.tsv
/// \* D9 past-slot guard dropped alone, two slots, no GC
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {}
///     MaxSlot = 2
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 1
///     Fixed = {"curslot", "rosterballot", "markerfirst", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {}
/// INVARIANTS Agreement NoRedecide Validity LogPrefix VotedImpliesMarker
/// SYMMETRY Sym
/// ```
///
/// ```tlacfg RabiaSlot:live
/// \* RabiaSlot config live: the expected verdict is its row in tools/tla/models.tsv
/// \* positive control for noemptyprop: every fix on, Live holds
/// SPECIFICATION LiveSpec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {}
///     MaxSlot = 1
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 1
///     Fixed = {"pastguard", "curslot", "rosterballot", "markerfirst", "emptyprop"}
///     RogueVoter = FALSE
///     CommonCoin = TRUE
///     Byz = {}
/// PROPERTIES Live
/// ```
///
/// ```tlacfg RabiaSlot:ctl_byz_agree
/// \* RabiaSlot config ctl_byz_agree: the expected verdict is its row in tools/tla/models.tsv
/// \* negative control: a Byzantine voter (equivocation, forged Decision) breaks Agreement
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {}
///     MaxSlot = 1
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 2
///     Fixed = {"pastguard", "curslot", "rosterballot", "markerfirst", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {n1}
/// INVARIANTS Agreement
/// ```
///
/// ```tlacfg RabiaSlot:ctl_byz_valid
/// \* RabiaSlot config ctl_byz_valid: the expected verdict is its row in tools/tla/models.tsv
/// \* negative control: a Byzantine voter's forged Decision breaks Validity
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {}
///     MaxSlot = 1
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 2
///     Fixed = {"pastguard", "curslot", "rosterballot", "markerfirst", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {n1}
/// INVARIANTS Validity
/// ```
///
/// ```tlacfg RabiaSlot:ctl_logprefix
/// \* RabiaSlot config ctl_logprefix: the expected verdict is its row in tools/tla/models.tsv
/// \* negative control: current-slot gates dropped at two slots, a later slot is applied before an earlier one
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {}
///     MaxSlot = 2
///     MaxRound = 1
///     GcDepth = 0
///     NVals = 1
///     Fixed = {"pastguard", "rosterballot", "markerfirst", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {}
/// INVARIANTS LogPrefix
/// SYMMETRY Sym
/// ```
///
/// ```tlacfg RabiaSlot:paper_d9
/// \* RabiaSlot config paper_d9: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Voters = {n1, n2, n3}
///     Observers = {}
///     MaxSlot = 1
///     MaxRound = 1
///     GcDepth = 9
///     NVals = 1
///     Fixed = {"curslot", "rosterballot", "markerfirst", "emptyprop"}
///     RogueVoter = TRUE
///     CommonCoin = TRUE
///     Byz = {}
/// INVARIANTS Agreement NoRedecide Validity LogPrefix VotedImpliesMarker
/// SYMMETRY Sym
/// ```
///
/// ```tlacfg RabiaEpoch:code
/// \* RabiaEpoch config code: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop", "prefix"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
///     NoOldEpochTally
///     GovernedVote
/// ```
///
/// ```tlacfg RabiaEpoch:live
/// \* RabiaEpoch config live: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION LiveSpec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop", "prefix"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "D"}}
///     Batches = {"b"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {"D"}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
/// PROPERTIES
///     Progress
/// ```
///
/// ```tlacfg RabiaEpoch:nocrashstop
/// \* RabiaEpoch config nocrashstop: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"prefix"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
/// ```
///
/// ```tlacfg RabiaEpoch:noexactepoch
/// \* RabiaEpoch config noexactepoch: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"relayepoch", "retainmaj"}
///     Assume = {"crashstop", "prefix"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
///     NoOldEpochTally
///     GovernedVote
/// ```
///
/// ```tlacfg RabiaEpoch:noexactepoch_noprefix
/// \* RabiaEpoch config noexactepoch_noprefix: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"relayepoch", "retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
/// ```
///
/// ```tlacfg RabiaEpoch:noprefix
/// \* RabiaEpoch config noprefix: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     GovernedVote
/// ```
///
/// ```tlacfg RabiaEpoch:noprefixsafe
/// \* RabiaEpoch config noprefixsafe: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
///     NoOldEpochTally
/// ```
///
/// ```tlacfg RabiaEpoch:codesync_live
/// \* RabiaEpoch config codesync_live: the expected verdict is its row in tools/tla/models.tsv
/// \* the code without prefix, LIVE responses only: applyRestoredState carries slot and roster together
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 2
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "code"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     GovernedVote
/// ```
///
/// ```tlacfg RabiaEpoch:codesync_gov
/// \* RabiaEpoch config codesync_gov: the expected verdict is its row in tools/tla/models.tsv
/// \* the code without prefix, LIVE and COLD responses (a COLD response carries the phase without the roster)
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 2
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "code"
///     ColdShapes = {FALSE, TRUE}
/// INVARIANTS
///     TypeOK
///     GovernedVote
/// ```
///
/// ```tlacfg RabiaEpoch:codesync_agree
/// \* RabiaEpoch config codesync_agree: the expected verdict is its row in tools/tla/models.tsv
/// \* as codesync_gov, checking Agreement
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 2
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "code"
///     ColdShapes = {FALSE, TRUE}
/// INVARIANTS
///     TypeOK
///     Agreement
/// ```
///
/// ```tlacfg RabiaEpoch:norelayexact_noprefix
/// \* RabiaEpoch config norelayexact_noprefix: the expected verdict is its row in tools/tla/models.tsv
/// \* D2 relay epoch check AND D6 exact-epoch counting dropped under sync adoption
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
/// ```
///
/// ```tlacfg RabiaEpoch:ctl_oldtally
/// \* RabiaEpoch config ctl_oldtally: the expected verdict is its row in tools/tla/models.tsv
/// \* negative control for NoOldEpochTally: exact-epoch counting dropped under sync adoption
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"relayepoch", "retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     NoOldEpochTally
/// ```
///
/// ```tlacfg RabiaEpoch:ctl_oneroster
/// \* RabiaEpoch config ctl_oneroster: the expected verdict is its row in tools/tla/models.tsv
/// \* negative control for OneRosterPerEpoch: exact-epoch counting dropped under sync adoption
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"relayepoch", "retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     OneRosterPerEpoch
/// ```
///
/// ```tlacfg RabiaEpoch:ctl_oneroster_cs
/// \* RabiaEpoch config ctl_oneroster_cs: the expected verdict is its row in tools/tla/models.tsv
/// \* negative control for OneRosterPerEpoch: crash-stop assumption removed
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"prefix"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     OneRosterPerEpoch
/// ```
///
/// ```tlacfg RabiaEpoch:norelayepoch
/// \* RabiaEpoch config norelayepoch: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "retainmaj"}
///     Assume = {"crashstop", "prefix"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
///     NoOldEpochTally
///     GovernedVote
/// ```
///
/// ```tlacfg RabiaEpoch:norelayepoch_noprefix
/// \* RabiaEpoch config norelayepoch_noprefix: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
///     NoOldEpochTally
/// ```
///
/// ```tlacfg RabiaEpoch:noretainmaj
/// \* RabiaEpoch config noretainmaj: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION LiveSpec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch"}
///     Assume = {"crashstop", "prefix"}
///     Paper = {}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "D"}}
///     Batches = {"b"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {"D"}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
/// PROPERTIES
///     Progress
/// ```
///
/// ```tlacfg RabiaEpoch:paper_d12
/// \* RabiaEpoch config paper_d12: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop", "prefix"}
///     Paper = {"nopreferred"}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
/// ```
///
/// ```tlacfg RabiaEpoch:paper_d12_np
/// \* RabiaEpoch config paper_d12_np: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {"nopreferred"}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
/// ```
///
/// ```tlacfg RabiaEpoch:paper_d2
/// \* RabiaEpoch config paper_d2: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop", "prefix"}
///     Paper = {"norelay"}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
/// ```
///
/// ```tlacfg RabiaEpoch:paper_d2_np
/// \* RabiaEpoch config paper_d2_np: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {"norelay"}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
/// ```
///
/// ```tlacfg RabiaEpoch:paper_d6
/// \* RabiaEpoch config paper_d6: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop", "prefix"}
///     Paper = {"anyepoch"}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
/// ```
///
/// ```tlacfg RabiaEpoch:paper_d6_np
/// \* RabiaEpoch config paper_d6_np: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///     Fixed = {"exactepoch", "relayepoch", "retainmaj"}
///     Assume = {"crashstop"}
///     Paper = {"anyepoch"}
///     Roster0 = {"A", "B", "C"}
///     TargetSet = {{"A", "B", "D"}, {"A", "C", "D"}}
///     Batches = {"b1", "b2"}
///     MaxSlot = 2
///     MaxRequests = 2
///     RequestEpochs = {0, 1}
///     MaxJump = 1
///     MaxForge = 1
///     Slow = {}
///     JumpShape = "free"
///     ColdShapes = {FALSE}
/// INVARIANTS
///     TypeOK
///     Agreement
///     OneRosterPerEpoch
/// ```
///
/// ```tlacfg RabiaAdopt:allfixes
/// \* RabiaAdopt config allfixes: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:allfixes_savefail
/// \* RabiaAdopt config allfixes_savefail: the expected verdict is its row in tools/tla/models.tsv
/// \* the code with a failing saveState on Pause (R3)
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = TRUE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:allfixesD5
/// \* RabiaAdopt config allfixesD5: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = FALSE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:allfixesD5q
/// \* RabiaAdopt config allfixesD5q: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {"D5q"}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = FALSE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:ballotloss
/// \* RabiaAdopt config ballotloss: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = FALSE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:ballotlossD5
/// \* RabiaAdopt config ballotlossD5: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = FALSE
///   FaultyResp = FALSE
///   SameEpochAdopt = FALSE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:ballotlossD5_nooneconfig
/// \* RabiaAdopt config ballotlossD5_nooneconfig: the expected verdict is its row in tools/tla/models.tsv
/// \* D5 one-configuration check dropped, ballots lost on pause, newer-epoch path only (ballotlossD5 is the green base)
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch"}
///   Paper = {}
///   KeepBallots = FALSE
///   FaultyResp = FALSE
///   SameEpochAdopt = FALSE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:ballotlossD5_noselfvouch
/// \* RabiaAdopt config ballotlossD5_noselfvouch: the expected verdict is its row in tools/tla/models.tsv
/// \* D4 self-vouch dropped, ballots lost on pause, newer-epoch path only (ballotlossD5 is the green base)
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "oneconfig"}
///   Paper = {}
///   KeepBallots = FALSE
///   FaultyResp = FALSE
///   SameEpochAdopt = FALSE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_reachClaimed2
/// \* RabiaAdopt config ctl_reachClaimed2: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "claimed2"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_reachClaimed2_nofence
/// \* RabiaAdopt config ctl_reachClaimed2_nofence: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"selfvouch"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "claimed2"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_reachLive1NoVouch
/// \* RabiaAdopt config ctl_reachLive1NoVouch: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "live1novouch"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_reachLive2NoVouch
/// \* RabiaAdopt config ctl_reachLive2NoVouch: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "live2novouch"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_reachNewerLag
/// \* RabiaAdopt config ctl_reachNewerLag: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "newerlag"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_reachNewerNonLive
/// \* RabiaAdopt config ctl_reachNewerNonLive: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {"D5"}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "newernonlive"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_reachX2
/// \* RabiaAdopt config ctl_reachX2: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS ReachX2
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_rjAmnesiac
/// \* RabiaAdopt config ctl_rjAmnesiac: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "rejoinamnesiac"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "wiped"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_rjAmnesiac1
/// \* RabiaAdopt config ctl_rjAmnesiac1: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "rejoinamnesiac1"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "wiped"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_rjStale
/// \* RabiaAdopt config ctl_rjStale: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "rejoinstale"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "any"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_rjStale1
/// \* RabiaAdopt config ctl_rjStale1: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "rejoinstale1"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "any"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_rjTAmnesiac
/// \* RabiaAdopt config ctl_rjTAmnesiac: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "rejoinamnesiac"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_rjTAmnesiac1
/// \* RabiaAdopt config ctl_rjTAmnesiac1: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "rejoinamnesiac1"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_rjTAmnesiac1_selfvouch
/// \* RabiaAdopt config ctl_rjTAmnesiac1_selfvouch: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "rejoinamnesiac1"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_rjTAny
/// \* RabiaAdopt config ctl_rjTAny: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "rejoin"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_rjTClaimed2
/// \* RabiaAdopt config ctl_rjTClaimed2: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "claimed2"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_rjTWiped
/// \* RabiaAdopt config ctl_rjTWiped: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "rejoinwiped"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = FALSE
/// INVARIANTS Reach
/// ```
///
/// ```tlacfg RabiaAdopt:faultyresp
/// \* RabiaAdopt config faultyresp: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = TRUE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:ctl_held
/// \* RabiaAdopt config ctl_held: the expected verdict is its row in tools/tla/models.tsv
/// \* negative control for Held: a responder answering with an arbitrary log hands over a value nobody chose
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = TRUE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Held
/// ```
///
/// ```tlacfg RabiaAdopt:faultyrespD5
/// \* RabiaAdopt config faultyrespD5: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = TRUE
///   SameEpochAdopt = FALSE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:faultyrespD5q
/// \* RabiaAdopt config faultyrespD5q: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {"D5q"}
///   KeepBallots = TRUE
///   FaultyResp = TRUE
///   SameEpochAdopt = FALSE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:noamnesiafence
/// \* RabiaAdopt config noamnesiafence: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:noamnesiafenceD5
/// \* RabiaAdopt config noamnesiafenceD5: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = FALSE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:nooneconfig
/// \* RabiaAdopt config nooneconfig: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:nooneconfigD5
/// \* RabiaAdopt config nooneconfigD5: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = FALSE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:noselfvouch
/// \* RabiaAdopt config noselfvouch: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:noselfvouch_savefail
/// \* RabiaAdopt config noselfvouch_savefail: the expected verdict is its row in tools/tla/models.tsv
/// \* D4 self-vouch dropped, with a failing saveState on Pause
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = TRUE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:paperD3
/// \* RabiaAdopt config paperD3: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"selfvouch", "oneconfig"}
///   Paper = {"D3"}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:paperD4
/// \* RabiaAdopt config paperD4: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {"D4"}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:paperD5
/// \* RabiaAdopt config paperD5: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {"D5"}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:paperD5iso
/// \* RabiaAdopt config paperD5iso: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {"D5"}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = FALSE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "none"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjA_allfixes
/// \* RabiaAdopt config rjA_allfixes: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "any"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjA_noamnesiafence
/// \* RabiaAdopt config rjA_noamnesiafence: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "any"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjA_nooneconfig
/// \* RabiaAdopt config rjA_nooneconfig: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "any"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjA_noselfvouch
/// \* RabiaAdopt config rjA_noselfvouch: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "any"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjA_paperD4
/// \* RabiaAdopt config rjA_paperD4: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {"D4"}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "any"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjA_paperD5
/// \* RabiaAdopt config rjA_paperD5: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {"D5"}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "any"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjT_allfixes
/// \* RabiaAdopt config rjT_allfixes: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjT_allfixes_savefail
/// \* RabiaAdopt config rjT_allfixes_savefail: the expected verdict is its row in tools/tla/models.tsv
/// \* token-memory rejoin, all fixes, failing saveState on Pause
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = TRUE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjT_noamnesiafence
/// \* RabiaAdopt config rjT_noamnesiafence: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjT_nooneconfig
/// \* RabiaAdopt config rjT_nooneconfig: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjT_noselfvouch
/// \* RabiaAdopt config rjT_noselfvouch: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjT_noselfvouch_savefail
/// \* RabiaAdopt config rjT_noselfvouch_savefail: the expected verdict is its row in tools/tla/models.tsv
/// \* token-memory rejoin, D4 self-vouch dropped, failing saveState on Pause
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = TRUE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjT_paperD4
/// \* RabiaAdopt config rjT_paperD4: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {"D4"}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjT_paperD5
/// \* RabiaAdopt config rjT_paperD5: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {"D5"}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 2
///   MaxRestart = 2
///   MaxPause = 1
///   Rejoin = "tokens"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjW_allfixes
/// \* RabiaAdopt config rjW_allfixes: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "wiped"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjW_noamnesiafence
/// \* RabiaAdopt config rjW_noamnesiafence: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"selfvouch", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "wiped"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjW_nooneconfig
/// \* RabiaAdopt config rjW_nooneconfig: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "wiped"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjW_noselfvouch
/// \* RabiaAdopt config rjW_noselfvouch: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "oneconfig"}
///   Paper = {}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "wiped"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjW_paperD4
/// \* RabiaAdopt config rjW_paperD4: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {"D4"}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "wiped"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
///
/// ```tlacfg RabiaAdopt:rjW_paperD5
/// \* RabiaAdopt config rjW_paperD5: the expected verdict is its row in tools/tla/models.tsv
/// SPECIFICATION Spec
/// CONSTANTS
///   Fixed = {"amnesiafence", "selfvouch", "oneconfig"}
///   Paper = {"D5"}
///   KeepBallots = TRUE
///   FaultyResp = FALSE
///   SameEpochAdopt = TRUE
///   Probe = "none"
///   MaxCrash = 1
///   MaxRestart = 1
///   MaxPause = 1
///   Rejoin = "wiped"
///   SaveFails = FALSE
/// INVARIANTS TypeOK Agreement Held
/// PROPERTIES DurableDecision
/// ```
package org.pragmatica.consensus.rabia;
