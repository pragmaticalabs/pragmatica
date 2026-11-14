----------------------------- MODULE RabiaSlot -----------------------------
\* One Rabia log slot sequence (weak-MVC per slot) at ONE fixed roster and epoch, with our deviations
\* D1 (deterministic coin), D8 (proposal selection, empty proposals), D9 (slot GC and the past-slot guard),
\* D11 (activation marker, receiver-enforced electorate). pragmatica#3164.
\* Read point: 3cee1467931d301bd59b283375cec54acb5517d1 (source at that SHA, no build).
\* Paths are relative to integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/.
\*
\* Action -> file:method @3cee146793
\*   Activate      -> RabiaEngine.java:activate (:3412), recordParticipation (:3379)
\*   RecordMarker  -> (known-bad only: the marker written after activation; no such code at the SHA)
\*   Propose       -> RabiaEngine.java:broadcastOwnProposalIfNeeded (:2571), nextProposal (:2591),
\*                    startPhase (:2601), openSlotAfterChange (:1881), enterPhaseIfNeeded (:4087)
\*   Receive       -> RabiaEngine.java:handlePropose (:4008), handleVoteRound1 (:4169), handleVoteRound2 (:4329);
\*                    guards isPastPhase (:4083) -> repairPastSlot (:1963), acceptsBallot (:964);
\*                    registration PhaseData.java:registerProposal (:156), registerRound1Vote/registerRound2Vote (:212/:218)
\*   Round1        -> RabiaEngine.java:canVoteRound1 (:4139) + PhaseData.java:evaluateInitialVote (:323)
\*   Round2        -> RabiaEngine.java:canVoteRound2 (:4310) + PhaseData.java:evaluateRound2Vote (:334)
\*   Decide        -> RabiaEngine.java:canMakeDecision (:4397), makeAndBroadcastDecision (:4404), commitDecision (:4418)
\*                    + PhaseData.java:processRound2Completion (:367), coinFlip (:400)
\*   Learn         -> RabiaEngine.java:handleDecision (:4505) -> commitDecision (:4418)
\*   Gc            -> RabiaEngine.java:doCleanupOldPhases (:3989), isExpiredPhase (:4000)
\*   Equivocate    -> (assumption removal only: a voter that sends two different ballots for one (slot, round))
\*
\* Fixed: the fixes the modelled code carries. The code as it stands carries AllFixes; a config that drops one is a
\* known-bad config that must fail on the property it protects:
\*   pastguard   - D9: a ballot for a slot below the current one is answered by repair, never tallied
\*                 (isPastPhase keyed on `< current`); the known-bad keys it on the slot's data being present.
\*   curslot     - the current-slot gates: canVoteRound1/canVoteRound2/canMakeDecision require currentPhase = phase,
\*                 handleDecision drops a Decision below the current slot and buffers one above it. Not in the
\*                 deviation inventory's Fixed list; modelled as a member because D9's safety claim rests on it too.
\*   rosterballot- D11: acceptsBallot counts only installed roster members (isVoter(sender)).
\*   markerfirst - D11/#1212: activate refuses unless the participation marker is durably recorded first.
\*   emptyprop   - D8 (liveness): a voter with an empty queue joins (or opens, after activation) a slot with an
\*                 empty proposal instead of staying silent.
\*
\* Environment and assumption constants (each has a cfg that changes it):
\*   RogueVoter  - the observer runs the voter logic: a non-roster node whose stale local roster says it votes
\*                 (RabiaVoterReconfigurationTest.removedVoter_ballotAtCurrentEpoch_isNotCounted). Adversity, not a fix.
\*   CommonCoin  - D1: TRUE = coinFlip, a function of (slot, epoch, round) only; FALSE = any coin per replica (control).
\*   Byz         - voters that may equivocate (send two values for one ballot). The code assumes Byz = {}.
\*
\* Abstractions (README.md says what they lose): one epoch, no reconfiguration (RabiaEpoch); no sync, snapshot,
\* crash or amnesia (RabiaRecovery); a message is a member of a set that is never removed, so it may be delivered
\* to each receiver any number of times, at any time (the code's re-broadcasts and replays make duplicates real);
\* registration is putIfAbsent, so a duplicate changes nothing unless the slot's data was dropped. Repair is a
\* no-op: Decision messages are already in the set. No learnProposal (D7): queues are never repaired.
EXTENDS Naturals, FiniteSets, TLC

CONSTANTS Voters, Observers, MaxSlot, MaxRound, GcDepth, NVals, Fixed, RogueVoter, CommonCoin, Byz

AllFixes == {"pastguard", "curslot", "rosterballot", "markerfirst", "emptyprop"}
Fix(f) == f \in Fixed
ASSUME Fixed \subseteq AllFixes
ASSUME Byz \subseteq Voters /\ Voters \cap Observers = {}
ASSUME NVals \in {1, 2}
ASSUME RogueVoter \in BOOLEAN /\ CommonCoin \in BOOLEAN

Nodes == Voters \cup Observers
Slots == 1..MaxSlot
Rounds == 0..(MaxRound - 1)
Values == IF NVals = 1 THEN {"a"} ELSE {"a", "b"}   \* NVals = 1: one batch id (size bound)
Empty == "empty"
NoVal == "none"
Pick(S) == IF "a" \in S THEN "a" ELSE "b"        \* lowest pending id (nextProposal: pendingBatches.firstEntry)

Q == Cardinality(Voters) \div 2 + 1             \* voterQuorum
F1 == (Cardinality(Voters) - 1) \div 2 + 1      \* voterFPlusOne

\* A node runs the voter logic: a roster member, or the observer when it is rogue.
Acts(n) == n \in Voters \/ RogueVoter

VARIABLES
    msgs,     \* every message ever sent: [t, src, slot, rnd, val]; never removed
    reg,      \* node -> registered messages (PhaseData proposals and ballots, own included)
    has,      \* node -> slots with PhaseData (phases.keySet())
    rnd,      \* node -> slot -> PhaseData.round
    dflag,    \* node -> slots whose PhaseData.decided is set
    capped,   \* node -> slots that hit the MaxRound bound (model artefact, not code)
    cur,      \* node -> currentPhase
    pending,  \* node -> pending batch ids
    active,   \* node -> activated (InPhase/Idle allowed)
    fresh,    \* node -> activated and has not proposed since (openSlotAfterChange)
    marker,   \* node -> participation marker durably recorded
    logv,     \* node -> slot -> applied value (state machine; survives GC)
    napp,     \* node -> slot -> times the slot was applied (capped at 2)
    pend0     \* ghost: initial pending per node (Validity)

vars == <<msgs, reg, has, rnd, dflag, capped, cur, pending, active, fresh, marker, logv, napp, pend0>>

Key(m) == <<m.t, m.src, m.slot, m.rnd>>
Unseen(n, m) == \A x \in reg[n] : Key(x) # Key(m)
PropsAt(n, s) == {x \in reg[n] : x.t = "P" /\ x.slot = s}
Ballots(n, t, s, r) == {x \in reg[n] : x.t = t /\ x.slot = s /\ x.rnd = r}
Count(n, t, s, r, v) == Cardinality({x \in Ballots(n, t, s, r) : x.val = v})
Own(n, t, s, r) == {x \in Ballots(n, t, s, r) : x.src = n}
\* agreedValue: a non-empty proposal held by a quorum (proposalValues filters empty ones).
Agreed(n, s) == {v \in Values : Cardinality({x \in PropsAt(n, s) : x.val = v}) >= Q}

\* isPastPhase: the code keys it on `< current`; the known-bad also needs the slot's data present.
PastRoute(n, s) == IF Fix("pastguard") THEN s < cur[n] ELSE s < cur[n] /\ s \in has[n]
\* acceptsBallot. The rogue observer counts every sender (its stale roster includes itself).
Accepts(n, src) == IF n \in Observers \/ ~Fix("rosterballot") THEN TRUE ELSE src \in Voters
\* Slots a node may vote, decide or apply in.
VoteSlots(n) == IF Fix("curslot") THEN {cur[n]} \cap Slots ELSE has[n]

Coin(s, r) == IF (s + r) % 2 = 0 THEN "V0" ELSE "V1"   \* coinFlip: parity of phase + epoch + round, epoch 0
CoinVals(s, r) == IF CommonCoin THEN {Coin(s, r)} ELSE {"V0", "V1"}

Init ==
    /\ msgs = {}
    /\ reg = [n \in Nodes |-> {}]
    /\ has = [n \in Nodes |-> {}]
    /\ rnd = [n \in Nodes |-> [s \in Slots |-> 0]]
    /\ dflag = [n \in Nodes |-> {}]
    /\ capped = [n \in Nodes |-> {}]
    /\ cur = [n \in Nodes |-> 1]
    /\ pending \in [Nodes -> SUBSET Values]
    /\ \A n \in Nodes : Cardinality(pending[n]) <= 1 /\ (n \in Observers => pending[n] = {})   \* size bound, see README
    /\ pend0 = pending
    /\ active = [n \in Nodes |-> FALSE]
    /\ fresh = [n \in Nodes |-> FALSE]
    /\ marker = [n \in Nodes |-> FALSE]
    /\ logv = [n \in Nodes |-> [s \in Slots |-> NoVal]]
    /\ napp = [n \in Nodes |-> [s \in Slots |-> 0]]

Bcast(n, m) ==
    /\ msgs' = msgs \cup {m}
    /\ reg' = [reg EXCEPT ![n] = @ \cup {m}]
    /\ has' = [has EXCEPT ![n] = @ \cup {m.slot}]

\* commitDecision: tryMarkDecided on the slot's PhaseData, apply, advancePhase (max). Decide broadcasts the Decision.
Commit(n, s, v, bcast) ==
    /\ dflag' = [dflag EXCEPT ![n] = @ \cup {s}]
    /\ has' = [has EXCEPT ![n] = @ \cup {s}]
    /\ logv' = [logv EXCEPT ![n][s] = v]
    /\ napp' = [napp EXCEPT ![n][s] = IF @ < 2 THEN @ + 1 ELSE 2]
    /\ pending' = [pending EXCEPT ![n] = @ \ {v}]
    /\ cur' = [cur EXCEPT ![n] = IF s + 1 > @ THEN s + 1 ELSE @]
    /\ msgs' = IF bcast THEN msgs \cup {[t |-> "D", src |-> n, slot |-> s, rnd |-> 0, val |-> v]} ELSE msgs

Activate(n) ==
    /\ Acts(n) /\ ~active[n]
    /\ marker' = IF Fix("markerfirst") THEN [marker EXCEPT ![n] = TRUE] ELSE marker
    /\ active' = [active EXCEPT ![n] = TRUE]
    /\ fresh' = [fresh EXCEPT ![n] = TRUE]
    /\ UNCHANGED <<msgs, reg, has, rnd, dflag, capped, cur, pending, logv, napp, pend0>>

RecordMarker(n) ==
    /\ ~Fix("markerfirst") /\ active[n] /\ ~marker[n]
    /\ marker' = [marker EXCEPT ![n] = TRUE]
    /\ UNCHANGED <<msgs, reg, has, rnd, dflag, capped, cur, pending, active, fresh, logv, napp, pend0>>

Propose(n) ==
    LET s == cur[n]
        v == IF pending[n] # {} THEN Pick(pending[n]) ELSE Empty
    IN /\ Acts(n) /\ active[n] /\ s \in Slots
       /\ \A x \in PropsAt(n, s) : x.src # n
       /\ \/ pending[n] # {}                          \* startPhase
          \/ fresh[n]                                 \* openSlotAfterChange
          \/ \E x \in PropsAt(n, s) : x.src # n       \* joining a slot another voter opened
       /\ v = Empty => Fix("emptyprop")
       /\ Bcast(n, [t |-> "P", src |-> n, slot |-> s, rnd |-> 0, val |-> v])
       /\ fresh' = [fresh EXCEPT ![n] = FALSE]
       /\ UNCHANGED <<rnd, dflag, capped, cur, pending, active, marker, logv, napp, pend0>>

Receive(n, m) ==
    /\ m.t \in {"P", "R1", "R2"} /\ m.src # n
    /\ ~PastRoute(n, m.slot)
    /\ Accepts(n, m.src)
    /\ m.t = "R1" => m.val # "VQ"
    /\ m.t # "P" => m.rnd >= rnd[n][m.slot]
    /\ Unseen(n, m)
    /\ reg' = [reg EXCEPT ![n] = @ \cup {m}]
    /\ has' = [has EXCEPT ![n] = @ \cup {m.slot}]
    /\ UNCHANGED <<msgs, rnd, dflag, capped, cur, pending, active, fresh, marker, logv, napp, pend0>>

Round1(n, s) ==
    /\ Acts(n) /\ active[n] /\ s \in VoteSlots(n)
    /\ rnd[n][s] = 0 /\ Own(n, "R1", s, 0) = {}
    /\ Cardinality(PropsAt(n, s)) >= Q
    /\ Bcast(n, [t |-> "R1", src |-> n, slot |-> s, rnd |-> 0,
                 val |-> IF Agreed(n, s) # {} THEN "V1" ELSE "V0"])
    /\ UNCHANGED <<rnd, dflag, capped, cur, pending, active, fresh, marker, logv, napp, pend0>>

Round2(n, s) ==
    LET r == rnd[n][s] IN
    /\ Acts(n) /\ active[n] /\ s \in VoteSlots(n)
    /\ Own(n, "R1", s, r) # {} /\ Own(n, "R2", s, r) = {}
    /\ Cardinality(Ballots(n, "R1", s, r)) >= Q
    /\ Bcast(n, [t |-> "R2", src |-> n, slot |-> s, rnd |-> r,
                 val |-> IF Count(n, "R1", s, r, "V0") >= Q THEN "V0"
                         ELSE IF Count(n, "R1", s, r, "V1") >= Q THEN "V1" ELSE "VQ"])
    /\ UNCHANGED <<rnd, dflag, capped, cur, pending, active, fresh, marker, logv, napp, pend0>>

Decide(n, s) ==
    LET r == rnd[n][s]
        c1 == Count(n, "R2", s, r, "V1")
        c0 == Count(n, "R2", s, r, "V0")
    IN
    /\ Acts(n) /\ active[n] /\ s \in VoteSlots(n)
    /\ s \notin dflag[n] /\ s \notin capped[n]
    /\ Own(n, "R2", s, r) # {}
    /\ Cardinality(Ballots(n, "R2", s, r)) >= Q
    /\ IF c1 >= F1
       THEN \* Decided V1 on the agreed value; AwaitingProposal (no step) when none is held yet
            /\ \E v \in Agreed(n, s) : Commit(n, s, v, TRUE)
            /\ UNCHANGED <<reg, rnd, capped, active, fresh, marker, pend0>>
       ELSE IF c0 >= F1
       THEN /\ Commit(n, s, Empty, TRUE)
            /\ UNCHANGED <<reg, rnd, capped, active, fresh, marker, pend0>>
       ELSE \E c \in (IF c1 > 0 THEN {"V1"} ELSE IF c0 > 0 THEN {"V0"} ELSE CoinVals(s, r)) :
            IF r + 1 < MaxRound
            THEN /\ rnd' = [rnd EXCEPT ![n][s] = r + 1]
                 /\ Bcast(n, [t |-> "R1", src |-> n, slot |-> s, rnd |-> r + 1, val |-> c])
                 /\ UNCHANGED <<dflag, capped, cur, pending, active, fresh, marker, logv, napp, pend0>>
            ELSE /\ capped' = [capped EXCEPT ![n] = @ \cup {s}]
                 /\ UNCHANGED <<msgs, reg, has, rnd, dflag, cur, pending, active, fresh, marker, logv, napp, pend0>>

Learn(n, d) ==
    /\ Acts(n) /\ active[n]
    /\ d.t = "D" /\ d.src \in Voters /\ d.src # n
    /\ IF Fix("curslot") THEN d.slot = cur[n] ELSE TRUE
    /\ d.slot \notin dflag[n]
    /\ Commit(n, d.slot, d.val, FALSE)
    /\ UNCHANGED <<reg, rnd, capped, active, fresh, marker, pend0>>

Gc(n) ==
    /\ Acts(n) /\ active[n]
    /\ \E s \in has[n] :
        /\ s < cur[n] /\ cur[n] - s > GcDepth
        /\ reg' = [reg EXCEPT ![n] = {x \in @ : x.slot # s}]
        /\ has' = [has EXCEPT ![n] = @ \ {s}]
        /\ rnd' = [rnd EXCEPT ![n][s] = 0]
        /\ dflag' = [dflag EXCEPT ![n] = @ \ {s}]
        /\ capped' = [capped EXCEPT ![n] = @ \ {s}]
    /\ UNCHANGED <<msgs, cur, pending, active, fresh, marker, logv, napp, pend0>>

Equivocate(n) ==
    /\ n \in Byz /\ active[n]
    /\ \E x \in msgs, v \in {"V0", "V1"} :
        /\ x.src = n /\ x.t \in {"R1", "R2"} /\ x.val # v
        /\ [x EXCEPT !.val = v] \notin msgs
        /\ msgs' = msgs \cup {[x EXCEPT !.val = v]}
    /\ UNCHANGED <<reg, has, rnd, dflag, capped, cur, pending, active, fresh, marker, logv, napp, pend0>>

Step(n) ==
    \/ Activate(n) \/ RecordMarker(n) \/ Propose(n) \/ Gc(n)
    \/ \E m \in msgs : Receive(n, m) \/ Learn(n, m)
    \/ \E s \in Slots : Round1(n, s) \/ Round2(n, s) \/ Decide(n, s)

Next == \/ \E n \in Nodes : Step(n)
        \/ \E n \in Byz : Equivocate(n)

Sym == Permutations(Voters)   \* voters are interchangeable (pending is chosen per node); safety cfgs without Byz only

Spec == Init /\ [][Next]_vars
\* Fairness for the voters only; the observer and equivocation are adversity, never forced.
LiveSpec == Spec /\ \A n \in Voters : WF_vars(Step(n))

\* ---------------- properties ----------------
Agreement == \A n, m \in Voters, s \in Slots :
                logv[n][s] # NoVal /\ logv[m][s] # NoVal => logv[n][s] = logv[m][s]
NoRedecide == \A n \in Voters, s \in Slots : napp[n][s] <= 1
Validity == \A n \in Voters, s \in Slots :
                logv[n][s] \in Values => logv[n][s] \in UNION {pend0[v] : v \in Voters}
LogPrefix == \A n \in Voters, s \in Slots : s < cur[n] => logv[n][s] # NoVal
VotedImpliesMarker == \A m \in msgs : m.src \in Voters => marker[m.src]

Opened(s) == \E m \in msgs : m.t = "P" /\ m.slot = s /\ m.src \in Voters
CapHit(s) == \E n \in Voters : s \in capped[n]
Live == \A s \in Slots : Opened(s) ~> ((\A n \in Voters : cur[n] > s) \/ CapHit(s))
=============================================================================
