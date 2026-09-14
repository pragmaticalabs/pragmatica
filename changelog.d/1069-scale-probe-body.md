### Fixed (2026-09-13 — #1069: Heavy 5→7 scale probes sent the pre-#581 scale body and reported the HTTP 400 as a #336 stall)
- **`ScaleUpFiveToSevenProbeTest` and `ArtifactChurnSurvival5to7to5ProbeTest` still posted `{coreCount, expectedVersion}`** to
  `POST /api/v1/cluster/scale`. Since #581 the request is `ScaleRequest(source, role, count, expectedVersion)`, so the server
  refused every call with HTTP 400 `Type mismatch: expected int, got unknown`. Neither probe read the status: the rejected
  call left the configured count at 5, and the probes waited out their budget and reported a "5→7 stall … #336 reproducing
  IN-JVM". Every conclusion about #336/#467 drawn from them since #581 was measuring a refused HTTP call.
- Both probes now send `{source:"", role:"core", count, expectedVersion}` (the shape `PostRestartSlowRejoinDeficitFillProbeTest`
  already sends) and fail before any membership wait unless the response is 2xx, reports the requested `newCount`, and
  reports `configVersion` one past the fencing version it was sent. A transport failure also fails immediately.
- **`03-scaling/test-01-quorum-safety.sh` had the same stale body and passed without testing anything.** Its three rejection
  tests sent `{coreCount}` and accepted any `>= 400`, so the decoder's 400 satisfied them before the quorum or max check ran.
  They now send `{role, count, expectedVersion}` and require the refusal detail to name the validator's own message
  (`Quorum safety violation`, `Invalid core max`). The above-max case moves from 20 to 21, because 20 is even and the
  validator refuses it as `Invalid core count` before it compares against `coreMax`.
- `cluster-management-spec.md` documented a `{core_count, expected_version}` request and a 400 for quorum violations. It now
  shows the current request and response and the statuses the route actually returns (quorum violation is 409).
- **`EmberCluster.currentLeader()` answered from whichever node `ConcurrentHashMap` iterated first** (its leader view),
  and `status()` / `getLeaderManagementPort()` derive from it. After `addNode()` the first entry is the newborn, which
  holds no leader view, so every leader accessor said "no leader" while the elected leader was running; the two probes
  then counted membership on that newborn — which `MembershipFsm.seed` fills with the whole configured core set before a
  packet moves — and the ScaleUp probe declared 5→7 complete ~300 ms after `addNode()` while the leader still counted 5,
  and the churn probe's down-leg POST went to the newborn's management port and was refused 503 `No leader elected`.
  `currentLeader()` now names the running node whose own `isLeader()` holds
  `[verified: EmberClusterCurrentLeaderTest.currentLeader_isTheNodeClaimingLeadership_notTheFirstMapEntry — reverting to
  findFirst() reddens it with currentLeader()=None() while the leader is running]`.
- Both probes read the counted-core denominator from, and address the scale POST to, that leader only — no fallback to
  the first map entry — and a 7 is accepted only from a node that was a member at 5, so a count taken from a node the
  scale created fails the probe naming that node and what the leader counted at that instant
  `[verified: ScaleUpFiveToSevenProbeTest, ArtifactChurnSurvival5to7to5ProbeTest — with the first-entry read restored
  both fail "counted 7 cores on scale-7 … the node claiming leadership (scale-1) counts 5"; with that guard also removed
  the ScaleUp probe passes 279 ms after addNode() with leader=none, which is the pass the head shipped]`. With the leader
  read, 5→7 completes on the leader's FSM in 17.2–18.1 s in-JVM (n=2) and the churn's 7→5 down-leg is accepted (HTTP 200).
- **The churn probe's 7→5 leg was then accepted at the DEPARTING edge** (~0.5 s after the POST): `MembershipFsm` prunes a
  drainer from `coreCountedMembers()` the instant `drainNode` is requested, before its departure push has moved a chunk, and
  the count was read on the leader — itself a drain victim. A #427 regression would have stayed green. The down-leg is now
  accepted only at the terminal edge (both victims stopped and removed by `EmberCluster.handleSelfDrain`, a surviving leader
  counting exactly the survivor set, every victim `Dead` in its view, nothing `Departing`), with the survival read taken from
  that named survivor `[verified: with the terminal clauses removed the tripwire `requireTerminalOnSurvivor` fails at 511 ms —
  "accepted at the DEPARTING edge … churn-1 still sees departing=[churn-2, churn-1]"]`.
- **#1089 — the reconciler selects the LEADER as a drain victim and the DRAIN command can never reach it.**
  `LeaderReconciler.selectDrainVictims` does not exclude the leader (in Ember no id is ephemeral, the slice owners are
  excluded and the added nodes are inside the 30 s drain-safety grace, so the reversed-id mature seeds — the leader among
  them — are chosen first), and the command is delivered only on the leader's broadcast `ClusterSyncPing`, which
  `ClusterSyncState.dispatchPing` never sends to `self`. The leader never runs its `DrainProcedure`; it marks itself
  `Departing`, #1058 withdraws it to MEMBER when no acknowledgement arrives after the 15 s DEPARTING window, and it is
  selected again — until an added node passes the grace and is drained instead. Measured in-JVM at the merged head (n=3):
  `drainNode requested` for the leader **twice**, `DrainProcedure` for it **zero** times, **two** self-`Departing`→MEMBER
  withdrawals (~15 s each), the second victim (`churn-7`) requested only at ~+44 s, terminal edge at
  **49,972 / 50,853 / 51,213 ms** — a ~45 s detour against the ~5 s the non-leader victim needs. Before #1058 the leader
  wedged in DEPARTING and the edge was unreachable; in cloud the CTM's 60 s grace-terminate kills the leader ungracefully,
  no departure push — the #427 loss mode. No product change here (ruling C). `ArtifactChurnSurvival5to7to5ProbeTest` therefore carries an ENABLED tripwire,
  `leaderSelectedAsDrainVictim_neverDrains_tripwireUntil1089`, asserting that behaviour precisely (the leader listed ITSELF
  as `Departing` during the down-leg and is still a survivor at the terminal edge; its failure message says "#1089 landed —
  delete me") beside the enabled terminal-edge probe; both read one churn cycle
  `[verified: with the leader shielded from selection in the clone (shape B simulated, not the real fix) the tripwire
  reddens on "must have marked ITSELF Departing" while the probe stays green; at head both are green, down-leg terminal at
  50.0–51.2 s with victims churn-2 and churn-7 Dead and the leader intact]`. `ScaleUpFiveToSevenProbeTest` is unaffected
  and stays strict.
