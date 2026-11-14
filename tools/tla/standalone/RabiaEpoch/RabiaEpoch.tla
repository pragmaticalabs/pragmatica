----------------------------- MODULE RabiaEpoch -----------------------------
\* Rabia deviations D2 (decision relay), D6 (in-log reconfiguration: epoch per slot boundary, exact-epoch ballot
\* counting, newer-epoch deferral) and D12 (leader layer as a requester set) of pragmatica#3164.
\* Read point: source at 3cee146793 (no build). Paths relative to
\* integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/.
\*
\* Action -> file:method @3cee146793
\*   Request     -> RabiaEngine.java:reconfigure/admissibleCommand (:1713) + broadcastVoters; D12 requester set
\*   Adopt       -> RabiaEngine.java:handleReconfigurationRequest (:1771) and handlePropose (:4008) -> adoptRequested (:1797)
\*   Vote        -> RabiaEngine.java:broadcastOwnProposalIfNeeded (:2571) (proposal = command XOR batch, :4026)
\*   Decide      -> RabiaEngine.java:acceptsBallot (:964) as the tally guard + PhaseData.java:agreedValue (:295) /
\*                  processRound2Completion (:367); deferIfNewerEpoch (:1917) is the "b.e > epoch" branch of Accepts
\*   DecideNull  -> PhaseData.java:processRound2Completion (:380, the V0 decision)
\*   Commit      -> RabiaEngine.java:commitDecision (:4418) -> applyReconfiguration (:1813) -> installAppliedChange
\*                  (:1824), ReconfigurationCommand.java:successorOf/appliesTo (:34/:40), settleRequests
\*   Relay       -> RabiaEngine.java:handleDecision (:4505) (current-slot branch; buffering = relayer state stays readable)
\*   Jump, LearnEpoch, Forge -> NOT code: environment moves enabled only when an assumption is removed (see Assume).
\*                  LearnEpoch is the shape of adoptSyncedConfiguration (:1892) separated from its slot install.
\*
\* Fixed (each fix a member; every cfg assigns it):
\*   "exactepoch" acceptsBallot counts a ballot only at epoch == voterEpoch() (known-bad: epoch <= current)
\*   "relayepoch" handleDecision refuses a current-slot Decision whose epoch != voterEpoch()
\*   "retainmaj"  ReconfigurationCommand.retainsTargetMajority in admission and in appliesTo (liveness guard)
\* Assume (code-relied environment; every cfg assigns it):
\*   "prefix"     a replica enters slot s+1 only by committing slot s (code comment at handleDecision: "the epoch
\*                governing a slot is a function of the applied prefix"). Removed: Jump + LearnEpoch.
\*   "crashstop"  a relayed Decision is a decision some replica made (no fabricated evidence). Removed: Forge.

EXTENDS Naturals, FiniteSets, TLC

CONSTANTS Fixed, Assume, Paper, Roster0, TargetSet, Batches, MaxSlot, MaxRequests, RequestEpochs, MaxJump, MaxForge, Slow

KnownFixes  == {"exactepoch", "relayepoch", "retainmaj"}
KnownAssume == {"prefix", "crashstop"}
KnownPaper  == {"norelay", "anyepoch", "nopreferred"}
ASSUME Fixed \subseteq KnownFixes /\ Assume \subseteq KnownAssume /\ Paper \subseteq KnownPaper

\* Paper-faithful switches (report whether our rule closes a gap the paper leaves). Each
\* replaces ONE of our rules by what weak-MVC with fixed membership does; every other fix stays on.
\*   "norelay"     D2: a replica decides only from its own tally (no handleDecision relay)
\*   "anyepoch"    D6: a ballot counts by (slot, sender in roster) with no epoch tag at all
\*   "nopreferred" D12: no preferredOver convergence; a replica keeps the first command it adopted

Fix(f) == f \in Fixed
Has(a) == a \in Assume
Pap(p) == p \in Paper

NodeSeq == <<"A", "B", "C", "D">>
Nodes   == {NodeSeq[i] : i \in 1..4}
Rank(n) == CHOOSE i \in 1..4 : NodeSeq[i] = n

Slots == 1..MaxSlot
\* Every value is one record shape so TLC never compares a string with a record.
V(k, e, t, x) == [k |-> k, base |-> e, target |-> t, b |-> x]
None  == V("none", 0, {}, "-")
Null  == V("null", 0, {}, "-")                                  \* the V0 decision
Cmds  == {V("cmd", e, t, "-") : e \in RequestEpochs, t \in TargetSet}
BatchVals == {V("batch", 0, {}, x) : x \in Batches}
Values == Cmds \cup BatchVals \cup {Null}
Quorum(r) == Cardinality(r) \div 2 + 1

\* ReconfigurationCommand.key(): comma-joined sorted ids; ids are one letter, so lexicographic order on the sorted
\* member list (a proper prefix first) is the String order.
MinRank(s) == CHOOSE n \in s : \A m \in s : Rank(n) <= Rank(m)
RECURSIVE KeyLess(_, _)
KeyLess(s, t) ==
    IF s = {} THEN t # {}
    ELSE IF t = {} THEN FALSE
    ELSE LET a == MinRank(s)
             b == MinRank(t)
         IN IF Rank(a) # Rank(b) THEN Rank(a) < Rank(b) ELSE KeyLess(s \ {a}, t \ {b})

\* ReconfigurationCommand.preferredOver (:56)
Preferred(c1, c2) == c1.base > c2.base \/ (c1.base = c2.base /\ KeyLess(c1.target, c2.target))

\* ReconfigurationCommand.appliesTo (:40): base epoch, a real change, retained majority.
AppliesTo(e, r, c) ==
    /\ c.base = e
    /\ c.target # r
    /\ Fix("retainmaj") => Cardinality(c.target \cap r) >= Quorum(c.target)

VARIABLES slot,       \* currentPhase per replica (MaxSlot+1 = done)
          epoch,      \* voterEpoch()
          roster,     \* voters.members()
          requested,  \* requestedConfiguration (a Cmd or None)
          decided,    \* committed value per slot (None = not committed)
          decEpoch,   \* epoch the replica held when it committed the slot (Decision.epoch it relays)
          ballots,    \* every proposal ever sent: [n, s, e, v]; a set, so each is delivered and never lost
          requests,   \* ReconfigurationRequests broadcast so far
          installs,   \* ghost: every <<epoch, roster>> any replica installed
          jumpers,    \* ghost: replicas that left a slot without committing it (assumption "prefix" removed)
          forged,     \* ghost: fabricated relays so far (assumption "crashstop" removed)
          mixed       \* ghost: some tally counted a ballot of an epoch other than the tallier's

vars == <<slot, epoch, roster, requested, decided, decEpoch, ballots, requests, installs, jumpers, forged, mixed>>

Init ==
    /\ slot      = [n \in Nodes |-> 1]
    /\ epoch     = [n \in Nodes |-> 0]
    /\ roster    = [n \in Nodes |-> Roster0]
    /\ requested = [n \in Nodes |-> None]
    /\ decided   = [n \in Nodes |-> [s \in Slots |-> None]]
    /\ decEpoch  = [n \in Nodes |-> [s \in Slots |-> 0]]
    /\ ballots   = {}
    /\ requests  = {}
    /\ installs  = {<<0, Roster0>>}
    /\ jumpers   = {}
    /\ forged    = 0
    /\ mixed     = FALSE

Voter(n)  == n \in roster[n]
Active(n) == slot[n] \in Slots /\ decided[n][slot[n]] = None

\* settleRequests: a request whose base epoch has passed is dropped.
Settled(c, e) == IF c # None /\ c.base = e THEN c ELSE None

\* commitDecision -> applyReconfiguration -> installAppliedChange -> advancePhase. Only a V1 command applies.
Commit(n, v) ==
    LET s     == slot[n]
        e     == epoch[n]
        apply == v.k = "cmd" /\ AppliesTo(e, roster[n], v)
        e2    == IF apply THEN e + 1 ELSE e
        r2    == IF apply THEN v.target ELSE roster[n]
    IN /\ decided'   = [decided EXCEPT ![n][s] = v]
       /\ decEpoch'  = [decEpoch EXCEPT ![n][s] = e]
       /\ slot'      = [slot EXCEPT ![n] = s + 1]
       /\ epoch'     = [epoch EXCEPT ![n] = e2]
       /\ roster'    = [roster EXCEPT ![n] = r2]
       /\ requested' = [requested EXCEPT ![n] = Settled(requested[n], e2)]
       /\ installs'  = IF apply THEN installs \cup {<<e2, r2>>} ELSE installs

\* D12: the leader's reconciler (any active voter) requests a change computed from its own roster.
Request(c) ==
    /\ Cardinality(requests) < MaxRequests
    /\ c \notin requests
    /\ \E r \in Nodes : Voter(r) /\ slot[r] \in Slots /\ AppliesTo(epoch[r], roster[r], c)
    /\ requests' = requests \cup {c}
    /\ UNCHANGED <<slot, epoch, roster, requested, decided, decEpoch, ballots, installs, jumpers, forged, mixed>>

\* adoptRequested: a request (checked with appliesTo) or a proposal-borne command of the voter's own epoch.
Adopt(n, c) ==
    /\ Voter(n)
    /\ \/ c \in requests /\ AppliesTo(epoch[n], roster[n], c)
       \/ \E b \in ballots : b.v = c /\ b.e = epoch[n] /\ b.n \in roster[n]
    /\ c.base = epoch[n]
    /\ requested[n] = None \/ (~Pap("nopreferred") /\ Preferred(c, requested[n]))
    /\ requested[n] # c
    /\ requested' = [requested EXCEPT ![n] = c]
    /\ UNCHANGED <<slot, epoch, roster, decided, decEpoch, ballots, requests, installs, jumpers, forged, mixed>>

\* One proposal per replica per (slot, epoch): the requested command, else a batch.
Vote(n, v) ==
    /\ Voter(n)
    /\ Active(n)
    /\ ~\E b \in ballots : b.n = n /\ b.s = slot[n] /\ b.e = epoch[n]
    /\ v \in (IF requested[n] # None THEN {requested[n]} ELSE BatchVals)
    /\ ballots' = ballots \cup {[n |-> n, s |-> slot[n], e |-> epoch[n], v |-> v]}
    /\ UNCHANGED <<slot, epoch, roster, requested, decided, decEpoch, requests, installs, jumpers, forged, mixed>>

\* acceptsBallot (:964) at the current slot (past slots go to repair). A newer-epoch ballot fails both branches and
\* stays in `ballots`: that is deferIfNewerEpoch + drainDeferredBallots with an unbounded queue.
Accepts(n, b) ==
    /\ b.s = slot[n]
    /\ b.n \in roster[n]
    /\ IF Pap("anyepoch") THEN TRUE ELSE IF Fix("exactepoch") THEN b.e = epoch[n] ELSE b.e <= epoch[n]

Seen(n)       == {b \in ballots : Accepts(n, b)}
Backers(n, v) == {m \in roster[n] : \E b \in Seen(n) : b.n = m /\ b.v = v}

Decide(n) ==
    /\ Voter(n)
    /\ Active(n)
    /\ \E v \in Cmds \cup BatchVals :
          /\ Cardinality(Backers(n, v)) >= Quorum(roster[n])
          /\ Commit(n, v)
          /\ mixed' = (mixed \/ \E b \in Seen(n) : b.v = v /\ b.e # epoch[n])
    /\ UNCHANGED <<ballots, requests, jumpers, forged>>

\* V0 only when every installed voter's proposal is in and no value has a quorum: every tallier then holds the same
\* set, so V0 here can never contradict a V1 elsewhere (a sound under-approximation of weak-MVC's V0 outcomes).
DecideNull(n) ==
    /\ Voter(n)
    /\ Active(n)
    /\ \A m \in roster[n] : \E b \in Seen(n) : b.n = m
    /\ \A v \in Cmds \cup BatchVals : Cardinality(Backers(n, v)) < Quorum(roster[n])
    /\ Commit(n, Null)
    /\ UNCHANGED <<ballots, requests, jumpers, forged, mixed>>

\* handleDecision, current-slot branch: any trusted peer (every node is an admitted consensus member here).
Relay(p, q) ==
    /\ ~Pap("norelay")
    /\ p # q
    /\ Active(q)
    /\ decided[p][slot[q]] # None
    /\ Fix("relayepoch") => decEpoch[p][slot[q]] = epoch[q]
    /\ Commit(q, decided[p][slot[q]])
    /\ UNCHANGED <<ballots, requests, jumpers, forged, mixed>>

\* --- environment moves, enabled only when an assumption is removed ---

\* "prefix" removed: a replica reaches slot s+1 without committing s (a slot install that does not carry the roster).
Jump(n) ==
    /\ ~Has("prefix")
    /\ Cardinality(jumpers) < MaxJump
    /\ Active(n)
    /\ slot[n] < MaxSlot
    /\ slot'    = [slot EXCEPT ![n] = @ + 1]
    /\ jumpers' = jumpers \cup {n}
    /\ UNCHANGED <<epoch, roster, requested, decided, decEpoch, ballots, requests, installs, forged, mixed>>

\* "prefix" removed: adoptSyncedConfiguration without its slot (epoch never regresses).
LearnEpoch(n, p) ==
    /\ ~Has("prefix")
    /\ slot[n] = slot[p]
    /\ epoch[p] > epoch[n]
    /\ epoch'     = [epoch EXCEPT ![n] = epoch[p]]
    /\ roster'    = [roster EXCEPT ![n] = roster[p]]
    /\ requested' = [requested EXCEPT ![n] = Settled(requested[n], epoch[p])]
    /\ UNCHANGED <<slot, decided, decEpoch, ballots, requests, installs, jumpers, forged, mixed>>

\* "crashstop" removed: a trusted peer relays a Decision nobody decided, tagged with the receiver's epoch.
Forge(q) ==
    /\ ~Has("crashstop")
    /\ forged < MaxForge
    /\ Active(q)
    /\ \E v \in Values : Commit(q, v)
    /\ forged' = forged + 1
    /\ UNCHANGED <<ballots, requests, jumpers, mixed>>

Next ==
    \/ \E c \in Cmds : Request(c)
    \/ \E n \in Nodes, c \in Cmds : Adopt(n, c)
    \/ \E n \in Nodes, v \in Cmds \cup BatchVals : Vote(n, v)
    \/ \E n \in Nodes : Decide(n) \/ DecideNull(n) \/ Jump(n) \/ Forge(n)
    \/ \E p, q \in Nodes : Relay(p, q) \/ LearnEpoch(p, q)

Spec == Init /\ [][Next]_vars

\* Liveness: replicas outside Slow are weakly fair (Slow = a joiner that may lag without bound).
Fairness ==
    \A n \in Nodes \ Slow :
        /\ WF_vars(\E v \in Cmds \cup BatchVals : Vote(n, v))
        /\ WF_vars(Decide(n))
        /\ WF_vars(DecideNull(n))
        /\ WF_vars(\E p \in Nodes : Relay(p, n))

LiveSpec == Spec /\ Fairness

\* --- properties ---

TypeOK ==
    /\ slot \in [Nodes -> 1..(MaxSlot + 1)]
    /\ requested \in [Nodes -> Cmds \cup {None}]
    /\ decided \in [Nodes -> [Slots -> Values \cup {None}]]

\* Agreement across epochs: no slot is committed with two values.
Agreement ==
    \A n, m \in Nodes, s \in Slots :
        decided[n][s] # None /\ decided[m][s] # None => decided[n][s] = decided[m][s]

\* One roster per epoch, over every install any replica ever made.
OneRosterPerEpoch == \A x, y \in installs : x[1] = y[1] => x[2] = y[2]

\* No tally ever counted a ballot of an epoch other than the tallier's (reachability witness for the exactepoch guard).
NoOldEpochTally == ~mixed

\* Every proposal carries the epoch that governs its slot, as fixed by any replica that committed the slot in order.
GovernedVote ==
    \A b \in ballots, m \in Nodes \ jumpers :
        decided[m][b.s] # None => b.e = decEpoch[m][b.s]

\* Liveness: the retained voter A commits every slot even when the joiner never takes a step.
Progress == <>(\A s \in Slots : decided["A"][s] # None)
=============================================================================
