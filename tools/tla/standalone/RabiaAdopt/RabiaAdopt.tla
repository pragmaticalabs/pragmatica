----------------------------- MODULE RabiaAdopt -----------------------------
\* Rabia variant: recovery and sync adoption (deviations D3, D4, D5 from the Rabia
\* paper, described in README.md). Read point 3cee146793.
\*
\* Action -> file:method @3cee146793 (paths under integrations/consensus/src/main/java/org/pragmatica/consensus/rabia/)
\*   Vote          -> PhaseData.evaluateInitialVote/evaluateRound2Vote (abstracted to one ballot per id per slot)
\*   Decide        -> PhaseData.processRound2Completion (majority of the slot's roster, exact epoch)
\*   Pause         -> RabiaEngine.pauseForQuorumLoss (saveState at pause, RabiaEngine.java:1609)
\*   Crash         -> process death; graceful stop = RabiaEngine.stop saveState (:2344)
\*   Restart       -> new process under the same NodeId; fence = boot token at transport (#1528/#1545, doc :3827-3834)
\*   Adopt         -> RabiaEngine.adoptionCandidates (:3742) + responsesRequiredWithALiveResponder (:3885)
\*                    + newerEpochCandidates (:3835) + eligibleSyncResponse (:3047); install = adoptCollectedState /
\*                    adoptSyncedConfiguration (:1892); saveState at restore (:3158)
\*
\* Fixed (each member a fix; a cfg that drops one is a known-bad):
\*   "amnesiafence" (D3)  peers refuse every message from a restarted process under the same NodeId
\*   "selfvouch"    (D4)  self counts toward n/2 only with durable history (floor > ZERO) or marker NEVER; else n/2+1
\*   "oneconfig"    (D5)  newer-epoch adoption needs >= 1 LIVE responder and all live responders claim ONE configuration;
\*                        a same-epoch response with a different roster is ineligible
\* Paper (paper-faithful / naive rule replacing ours, other fixes kept):
\*   "D3"  crash-recovery with no fence: a restarted replica rejoins directly with its durable state
\*   "D4"  adopt from ANY single response (no live/self/observer distinction)
\*   "D5"  newer epoch adopted from any single response, live or not, no configuration check
\*   "D5q" (comparison, not paper) newer epoch needs a LIVE majority (n/2+1) claiming one configuration;
\*         the response-quorum rule the code rejects at :3822 because it wedges #1526 (liveness not checked here)
\* Assumptions as constants:
\*   KeepBallots  the process keeps its ballot memory across pause and adoption [unverified: not read in code]
\*   FaultyResp   node "C" may answer sync with an arbitrary well-formed log (a non-crash fault)
\*   Probe        which adoption branch the sticky ghost `reached` records ("none" in property cfgs)
\*   SameEpochAdopt  FALSE disables the same-epoch adoption branches, isolating the D5 newer-epoch path (diagnostic)
EXTENDS Naturals, FiniteSets

CONSTANTS Fixed, Paper, KeepBallots, FaultyResp, SameEpochAdopt, Probe, MaxCrash, MaxRestart, MaxPause, Rejoin

ASSUME Fixed \subseteq {"amnesiafence", "selfvouch", "oneconfig"}
ASSUME Paper \subseteq {"D3", "D4", "D5", "D5q"}
ASSUME Rejoin \in {"none", "wiped", "any", "tokens"}

Ids    == {"A", "B", "C", "X"}          \* X: a fresh id (new ULID), never balloted
R1     == {"A", "B", "C"}               \* epoch-1 roster
Slots  == {1, 2}
None   == "none"
\* Slot 1 decides a reconfiguration (epoch 1 -> 2); slot 2 is a data slot in epoch 2.
Vals(s) == IF s = 1 THEN {"r1", "r2"} ELSE {"a", "b"}
RosterOf(c) == IF c = "r1" THEN {"A", "B", "X"} ELSE {"A", "C", "X"}

Empty      == [s \in Slots |-> None]
Prefix(l, k) == [s \in Slots |-> IF s <= k THEN l[s] ELSE None]
Len(l)     == Cardinality({s \in Slots : l[s] # None})
AllLogs    == {Empty} \cup {[s \in Slots |-> IF s = 1 THEN c ELSE None] : c \in Vals(1)}
                     \cup {[s \in Slots |-> IF s = 1 THEN c ELSE d] : c \in Vals(1), d \in Vals(2)}
Epoch(l)   == IF l[1] = None THEN 1 ELSE 2
Roster(l)  == IF l[1] = None THEN R1 ELSE RosterOf(l[1])
Max(S)     == CHOOSE m \in S : \A x \in S : m >= x

VARIABLES st, log, saved, marker, admit, known, reinc, voted, ballots, chosen, crashes, restarts, pauses, reached
\* reached: sticky ghost, TRUE once an adoption matching Probe happened (reachability controls only)
vars == <<st, log, saved, marker, admit, known, reinc, voted, ballots, chosen, crashes, restarts, pauses, reached>>

TypeOK ==
  /\ st \in [Ids -> {"down", "active", "paused", "syncing"}]
  /\ log \in [Ids -> AllLogs] /\ saved \in [Ids -> AllLogs]
  /\ marker \in [Ids -> {"never", "participated", "unknown"}]
  /\ admit \in [Ids -> SUBSET Ids] /\ known \in [Ids -> SUBSET Ids] /\ reinc \in [Ids -> BOOLEAN]
  /\ ballots \subseteq (Ids \X Slots \X {"r1", "r2", "a", "b"} \X BOOLEAN)

Init ==
  /\ st = [n \in Ids |-> IF n = "X" THEN "syncing" ELSE "active"]
  /\ log = [n \in Ids |-> Empty]
  /\ saved = [n \in Ids |-> Empty]
  /\ marker = [n \in Ids |-> IF n = "X" THEN "never" ELSE "participated"]
  /\ admit = [n \in Ids |-> Ids] /\ known = [n \in Ids |-> Ids \ {n}] /\ reinc = [n \in Ids |-> FALSE]
  /\ voted = [n \in Ids |-> Empty]
  /\ ballots = {}
  /\ chosen = [s \in Slots |-> {}]
  /\ crashes = 0 /\ restarts = 0 /\ pauses = 0
  /\ reached = FALSE

\* The slot this node may work on, and that slot's roster (exact epoch: derived from the decided prefix).
Open(n)   == IF log[n][1] = None THEN 1 ELSE IF log[n][2] = None THEN 2 ELSE 0
\* Link: p and q exchange messages. A restarted incarnation q is refused by every peer that still holds the old
\* boot token (BootTokens.java:34-37); admit[q] = the peers that do NOT (restarted since, or never saw the old token).
Link(p, q) == p = q \/ (p \in admit[q] /\ q \in admit[p])
\* Ballot b cast by k (flag i = cast by a restarted incarnation) is known to node m.
Counted(m, k, i) == IF m = k THEN (i \/ ~reinc[m]) ELSE (Link(m, k) \/ (~i /\ ~reinc[m]))

Vote(n, v) ==
  LET s == Open(n) IN
  /\ st[n] = "active" /\ s # 0
  /\ n \in Roster(log[n]) /\ voted[n][s] = None /\ v \in Vals(s)
  /\ voted' = [voted EXCEPT ![n][s] = v]
  /\ ballots' = ballots \cup {<<n, s, v, reinc[n]>>}
  /\ UNCHANGED <<st, log, saved, marker, admit, known, reinc, chosen, crashes, restarts, pauses>>

Decide(n, v) ==
  LET s == Open(n)
      RS == Roster(log[n])
      Q == {k \in RS : \E i \in BOOLEAN : <<k, s, v, i>> \in ballots /\ Counted(n, k, i)}
  IN
  /\ st[n] = "active" /\ s # 0 /\ v \in Vals(s)
  /\ 2 * Cardinality(Q) > Cardinality(RS)
  /\ log' = [log EXCEPT ![n][s] = v]
  /\ chosen' = [chosen EXCEPT ![s] = @ \cup {v}]
  /\ UNCHANGED <<st, saved, marker, admit, known, reinc, voted, ballots, crashes, restarts, pauses>>

Pause(n) ==
  /\ st[n] = "active" /\ pauses < MaxPause
  /\ st' = [st EXCEPT ![n] = "paused"]
  /\ saved' = [saved EXCEPT ![n] = log[n]]
  /\ voted' = IF KeepBallots THEN voted ELSE [voted EXCEPT ![n] = Empty]
  /\ pauses' = pauses + 1
  /\ UNCHANGED <<log, marker, admit, known, reinc, ballots, chosen, crashes, restarts>>

Resume(n) ==
  /\ st[n] = "paused"
  /\ st' = [st EXCEPT ![n] = "syncing"]
  /\ UNCHANGED <<log, saved, marker, admit, known, reinc, voted, ballots, chosen, crashes, restarts, pauses>>

\* graceful = stop (saveState); otherwise a crash (no save).
Crash(n, graceful) ==
  /\ st[n] # "down" /\ crashes < MaxCrash
  /\ st' = [st EXCEPT ![n] = "down"]
  /\ saved' = IF graceful THEN [saved EXCEPT ![n] = log[n]] ELSE saved
  /\ crashes' = crashes + 1
  /\ UNCHANGED <<log, marker, admit, known, reinc, voted, ballots, chosen, restarts, pauses>>

\* New process under the same NodeId: volatile state lost (D3: ballots never persisted).
\* wipe: in-memory persistence or a wiped disk (durable floor ZERO, marker unreadable).
\* A = the peers that admit the new incarnation. Without the fence every peer does. With it, only peers that lost the
\* old token do (Rejoin = "none": none, a permanent fence; "wiped": also for a wiped incarnation; "any": also one with its last save).
\* Abstraction of per-peer token memory (process memory only, BootTokens.java:43-46) by a nondeterministic subset: one peer or all.
Allowed(n, wipe, A) ==
  IF "amnesiafence" \notin Fixed THEN A = Ids
  ELSE IF Rejoin = "tokens"
    THEN A = Ids \ ({n} \cup {p \in Ids \ {n} : n \in known[p] /\ st[p] # "down"})
  ELSE \/ A = {}
       \/ /\ Rejoin # "none" /\ (Rejoin = "any" \/ wipe)
          /\ A \subseteq Ids \ {n} /\ (Cardinality(A) = 1 \/ A = Ids \ {n})
Restart(n, wipe, A) ==
  /\ st[n] = "down" /\ restarts < MaxRestart /\ Allowed(n, wipe, A)
  /\ log' = [log EXCEPT ![n] = IF wipe THEN Empty ELSE saved[n]]
  /\ saved' = [saved EXCEPT ![n] = IF wipe THEN Empty ELSE saved[n]]
  /\ marker' = [marker EXCEPT ![n] = IF wipe THEN "unknown" ELSE marker[n]]
  /\ voted' = [voted EXCEPT ![n] = Empty]
  /\ admit' = [admit EXCEPT ![n] = A] /\ known' = [q \in Ids |-> IF q = n THEN A ELSE IF q \in A THEN known[q] \cup {n} ELSE known[q]] /\ reinc' = [reinc EXCEPT ![n] = TRUE]
  /\ st' = [st EXCEPT ![n] = IF "D3" \in Paper THEN "active" ELSE "syncing"]
  /\ restarts' = restarts + 1
  /\ UNCHANGED <<ballots, chosen, crashes, pauses>>

\* What responder r may answer: its log as of some earlier moment (lag), or anything if faulty.
Options(r) == IF FaultyResp /\ r = "C" THEN AllLogs ELSE {Prefix(log[r], k) : k \in 0..2}
Live(r) == st[r] = "active"

\* Install an adopted state: persisted (saveState at restore), active iff self is in the adopted roster.
Install(n, ad) ==
  /\ Len(ad) >= Len(saved[n])                            \* ownStateFloor: never behind own durable state
  /\ log' = [log EXCEPT ![n] = ad]
  /\ saved' = [saved EXCEPT ![n] = ad]
  /\ IF n \in Roster(ad)
       THEN /\ st' = [st EXCEPT ![n] = "active"]
            /\ marker' = [marker EXCEPT ![n] = "participated"]   \* recordParticipation before activate
       ELSE /\ st' = st /\ marker' = marker
  /\ voted' = IF KeepBallots THEN voted ELSE [voted EXCEPT ![n] = Empty]
  /\ UNCHANGED <<admit, known, reinc, ballots, chosen, crashes, restarts, pauses>>

Hit(tg) ==
  CASE Probe = "newerlag"     -> tg.path = "newer" /\ tg.lag
    [] Probe = "live2novouch" -> tg.path = "live" /\ tg.n = 2 /\ ~tg.vouch
    [] Probe = "live1novouch" -> tg.path = "live" /\ tg.n = 1 /\ ~tg.vouch
    [] Probe = "claimed2"     -> tg.path = "newer" /\ tg.claimed > 1
    [] Probe = "newernonlive" -> tg.path = "newer" /\ tg.claimed = 0
    [] Probe = "rejoinamnesiac" -> tg.rj /\ ~tg.vouch      \* rejoined wiped incarnation completes an adoption
    [] Probe = "rejoinamnesiac1" -> tg.rj /\ ~tg.vouch /\ tg.path = "live" /\ tg.n = 1  \* ... on ONE response through the live rule (the self-vouch bound)
    [] Probe = "rejoinstale" -> tg.rj /\ tg.dur              \* rejoined incarnation with its last save adopts
    [] Probe = "rejoin" -> tg.rj                          \* ANY adoption completed by a rejoined incarnation (non-vacuity of the Rejoin modes)
    [] Probe = "rejoinwiped" -> tg.rj /\ ~tg.dur          \* ... by a rejoined incarnation holding no durable state
    [] Probe = "rejoinstale1" -> tg.rj /\ tg.dur /\ tg.path = "live" /\ tg.n = 1
    [] OTHER                  -> FALSE

PickMax(n, rl, CS, tag) ==
  /\ CS # {}
  /\ LET m == Max({Len(rl[r]) : r \in CS}) IN
       \E r \in CS : /\ Len(rl[r]) = m /\ Install(n, rl[r])
                     /\ reached' = (reached \/ Hit([tag EXCEPT !.lag = Len(rl[r]) < Len(log[r])]))

Adopt(n) ==
  /\ st[n] = "syncing"
  /\ \E S \in (SUBSET {r \in Ids \ {n} : st[r] # "down" /\ Link(n, r)}) \ {{}} :
     \E rl \in {f \in [S -> AllLogs] : \A r \in S : f[r] \in Options(r)} :
       LET myE  == Epoch(log[n])
           E    == {r \in S : ~("oneconfig" \in Fixed /\ Epoch(rl[r]) = myE /\ Roster(rl[r]) # Roster(log[n]))}
           newest == IF E = {} THEN myE ELSE Max({Epoch(rl[r]) : r \in E})
           R    == {r \in E : Epoch(rl[r]) = newest}
           L    == {r \in R : Live(r)}
           size == Cardinality(Roster(log[n]))
           claimed == Cardinality({Roster(rl[r]) : r \in L})
           vouch == Len(saved[n]) > 0 \/ marker[n] = "never"
           req  == IF "selfvouch" \in Fixed /\ ~vouch THEN size \div 2 + 1 ELSE size \div 2
       IN
       /\ R # {}
       /\ IF newest > myE
            THEN LET tg == [path |-> "newer", n |-> Cardinality(R), vouch |-> vouch, claimed |-> claimed, rj |-> reinc[n], dur |-> Len(saved[n]) > 0, lag |-> FALSE] IN
                 IF "D5" \in Paper
                   THEN PickMax(n, rl, R, tg)
                   ELSE /\ L # {}
                        /\ ("oneconfig" \in Fixed => claimed = 1)
                        /\ ("D5q" \in Paper => Cardinality(L) >= size \div 2 + 1)
                        /\ PickMax(n, rl, L, tg)
          ELSE /\ SameEpochAdopt
               /\ LET tg(p) == [path |-> p, n |-> Cardinality(R), vouch |-> vouch, claimed |-> claimed, rj |-> reinc[n], dur |-> Len(saved[n]) > 0, lag |-> FALSE] IN
                  IF "D4" \in Paper
                    THEN PickMax(n, rl, R, tg("paper"))
                  ELSE IF n \notin Roster(log[n])                       \* observer: no self in the count
                    THEN Cardinality(R) >= size \div 2 + 1 /\ PickMax(n, rl, R, tg("observer"))
                  ELSE IF L = {}                                        \* cold rule (#660)
                    THEN Cardinality(R) >= size \div 2 /\ PickMax(n, rl, R, tg("cold"))
                  ELSE /\ Cardinality(R) >= req
                       /\ PickMax(n, rl, IF Cardinality(L) >= size \div 2 + 1 THEN L ELSE R, tg("live"))

Next ==
  \/ /\ \/ \E n \in Ids : \E v \in {"r1", "r2", "a", "b"} : Vote(n, v) \/ Decide(n, v)
        \/ \E n \in Ids : Pause(n) \/ Resume(n)
        \/ \E n \in Ids, g \in BOOLEAN : Crash(n, g) \/ (\E A \in SUBSET Ids : Restart(n, g, A))
     /\ UNCHANGED reached
  \/ \E n \in Ids : Adopt(n)

Spec == Init /\ [][Next]_vars

\* Agreement: one value per slot, ever.
Agreement == \A s \in Slots : Cardinality(chosen[s]) <= 1
\* Every value a node holds was decided, and a decided slot a running process holds never regresses.
Held == \A n \in Ids, s \in Slots : log[n][s] # None => log[n][s] \in chosen[s]
DurableDecision ==
  [][\A n \in Ids, s \in Slots : (st[n] # "down" /\ log[n][s] # None) => log'[n][s] = log[n][s]]_vars

\* Reachability controls: each control cfg sets Probe and checks Reach (or ReachX2); EXPECTED violated
\* (the branch is reached), except Probe "claimed2" which is the unreachability claim.
Reach   == ~reached
ReachX2 == ~(st["X"] = "active" /\ log["X"][2] # None)
=============================================================================
