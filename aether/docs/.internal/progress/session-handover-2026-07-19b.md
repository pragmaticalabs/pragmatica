<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->

# Session Handover — 2026-07-19b (aether-main): #491 batch EXECUTION INSTRUCTIONS

**Purpose of this file:** the owner ruled the #491 stop-point and then hit the session limit,
so this is a *complete, executable* handover for a fresh Opus session to finish the batch with
zero re-derivation. Read the parent handover `session-handover-2026-07-19.md` (ADDENDUM 2 is the
stop-point) for the full arc; this file is the marching orders on top of it.

---

## THE RULINGS (both made by the owner this session — do not re-litigate)

1. **Disposition = Option 3 ("2+3"):** land the four proven #491 fixes **as one batch** +
   re-soften the unpinned phase 9 into a non-gating **SWIM sensor** pointing at a new scoped
   issue + **add a membership-pinned variant proving 3× convergence**. The unpinned test stays
   as a live sensor for the deferred SWIM defect.
2. **Pin mechanism = Option #1:** achieve stable membership via **auto-heal-off** (a zero-non-test
   runtime toggle) FIRST; empirically run it; **only if that alone does not converge 3×**, escalate
   to a minimal `EmberCluster` harness seam that raises the SWIM `suspectTimeout` / membership
   `splitTimeout`. (Ember is the in-JVM test harness, not shipped production runtime, so that seam
   is harness-scoped — acceptable.)

---

## CURRENT STATE (exact, verified 2026-07-19)

- **Branch** `release-1.0.0-rc3`. **HEAD** = this handover commit (was `106bdcfc2` before it).
  Local == origin at write time (0/0). **PULL FIRST** next session — design-stream pushes to this
  same branch (#448/#443).
- **Working tree = 6 UNCOMMITTED batch files** (nothing of the batch is committed):
  1. `aether/aether-stream/src/main/java/org/pragmatica/aether/stream/replication/PartitionBackfill.java` (+210/−26)
  2. `aether/aether-stream/src/test/java/org/pragmatica/aether/stream/replication/PartitionBackfillTest.java` (+…)
  3. `aether/forge/forge-tests/src/test/java/org/pragmatica/aether/forge/StreamOwnerFailoverTest.java` (phase 9 currently HARD)
  4. `aether/node/src/main/java/org/pragmatica/aether/node/AetherNode.java` (+8/−3)
  5. `integrations/consensus/src/main/java/org/pragmatica/consensus/net/quic/QuicClusterNetwork.java` (+41/−2)
  6. `integrations/consensus/src/test/java/org/pragmatica/consensus/net/quic/QuicClusterNetworkReconcilerTest.java` (+167)
- **Unit state (all green):** consensus 699/0, aether-stream 628/0, PartitionBackfillTest 44/0,
  StreamFanoutConsumerTest 5/5.
- **The batch's durable win is already proven:** post-buffering gate run drops = **ZERO** — the
  unicast-to-absent-member buffering CLOSED the transport-loss class that opened this whole arc at
  #467. Phases 1–8 of `StreamOwnerFailoverTest` (empty-start, full pre-kill history, ordered tail,
  live post-repair batches) all PASS.
- **The ONLY residual** (why phase 9 times out): after the graceful owner-kill, the transient
  `QuorumLost → PASSIVE` window causes LIVE survivor↔survivor QUIC links to be `evictStaleConnection`'d
  and the survivors marked SWIM-**DEAD-stuck** (`swimDeadStuck=[sof-1]` in the gate log, sof-1 being
  the *alive leader*). The dial layer drops SWIM-dead peers from the FSM desired-dial set, so the
  (correctly-buffered) catch-up send never drains. This is the consciously-deferred #94-CLASS SWIM
  false-removal-under-churn defect. **Every layer above SWIM is fixed and proven.**

---

## WHAT THE BATCH ACTUALLY CHANGES (so review isn't blind)

Layer labels from `session-handover-2026-07-19.md` ADDENDUM §"five evidence-locked layers":

- **F1** (already committed earlier as `3d3e228b8`, NOT in this tree): higher-id dial grace scoped to
  never-connected peers; REMOVED-peer connect gate. *(Context only — not part of the 6-file batch.)*
- **F4 — committed-owner self-election gate** (`AetherNode.java` + `PartitionBackfill.java`): backfill
  promotion gated on committed ownership being none-or-self; a diverged/empty-ring node must not
  self-promote while a DIFFERENT node is the committed owner. `AetherNode` change is a 4-line HOIST of
  `streamCommittedOwnerSource = KvCommittedStreamOwnerSource.kvCommittedStreamOwnerSource(kvStore)`
  above the `PartitionBackfill.partitionBackfill(...)` call so it can be passed in (the #265 owner-release
  guard below reuses the same instance). **Reviewed: clean.**
- **m2 — cold-start promotion SAFETY gate** (`PartitionBackfill.java`): self-promotion gated when a
  committed owner exists (stay SYNCING; 5s re-pulls already existed). #445 distrust-empty gate untouched.
- **m3(i) — eager INIT PeerState in the reconciler** (`QuicClusterNetwork.java` :2230, :2275): both
  reconciler skip-branches materialise an INIT `PeerState` for a still-member peer before `continue`, so
  outbound BUFFERS instead of hard-dropping. Topology path gated on `swimMembershipAllows` (departed
  peers stay null → still hard-drop); FSM-desired path ungated (desired set is authoritative). **Reviewed: clean.**
- **m3(ii)a — probe-first re-verify** (`PartitionBackfill.java`, the +210 bulk): new `lastReverifyMs`
  field; `staleCaughtUpNonOwner = offsetMoved || reverifyIntervalElapsed` (null ⇒ elapsed); non-owner
  branch → `backfillOrReverify` (CAUGHT_UP → `reverifyFromOwner`, SYNCING → direct `backfillFromOwner`);
  `reverifyFromOwner`/`decideReverify`/`reverifyNoOp` trio (stamp `lastReverifyMs` at dispatch; probe HRW
  owner head; ahead ⇒ pull+promote, not-ahead ⇒ pure no-op); `selfIsCaughtUp` helper; **stamp
  `lastReverifyMs` at promote too** (a successful pull IS a re-verify). **← STILL TO REVIEW (the big one).**
- **FINAL LEG — unicast-to-absent-member buffering** (`QuicClusterNetwork.java` :1583–1627): the true
  root fix. `dispatchPayload` + `dispatchPayloadWithOutcome` null-peer branches → new `dispatchToAbsentPeer`:
  `swimMembershipAllows(peerId)` ⇒ `getOrCreatePeer` + `dispatchToPeer` (the exact broadcast `Queued` /
  offline-buffer path, `OFFLINE_BUFFER_MAX`-bounded, drained on attach, returns `Sent`); non-member ⇒
  `warnDroppedToUnknownPeer` + `NoPeerState`, unchanged. `offlineBufferSizeForTests` accessor added.
  **Reviewed: clean.** *Crux to understand:* the buffer accepts the stuck-dead survivor (still a member,
  `swimMembershipAllows`=true) so drops=0, but the DIALER reads stricter raw SWIM state and won't dial it
  — hence the residual. That asymmetry is the SWIM issue, not a batch bug.

Tests: `QuicClusterNetworkReconcilerTest` +6 named tests (eager-PeerState + unicast-buffering + null-non-member-drop
+ offline-buffer-bound). `PartitionBackfillTest` rewrote/added the `WriteIdleResidualReverify` arms.

---

## TASK 1 — pinned variant + re-soften phase 9

### 1a. Re-soften the UNPINNED phase 9 (in `StreamOwnerFailoverTest.java`)
Currently lines ~176–186 are a HARD `await().atMost(CONVERGE_TIMEOUT).until(this::convergedWithRfRestored)`.
Change to a **soft, non-failing SENSOR**:
- Poll `convergedWithRfRestored()` up to `CONVERGE_TIMEOUT` but **do not fail on timeout**. Log the outcome
  (converged? + the final `ownerView()` `ReplicaSetView`) at WARN if it did NOT converge, INFO if it did.
- Rewrite the phase-9 comment to: "SWIM sensor for #<NEW-SWIM-ISSUE> — post-kill RF-restoration can stall
  on the deferred SWIM false-removal-under-churn C-layer; convergence here is observed, not asserted. The
  membership-pinned variant asserts convergence hard." Keep phases 1–8 HARD.
- Shape (the pre-hardening helper `awaitConvergenceSoft` existed before — reinstate that spirit):
  a bounded poll loop using `deadline(CONVERGE_TIMEOUT)` + `LockSupport.parkNanos(POLL_GAP_NANOS)`, then a
  single `LOG.log(...)` of the result. No `assertThat` on convergence.

### 1b. Add the membership-pinned variant (auto-heal-off; zero non-test change)
**"3× convergence" = the pinned test run 3 CONSECUTIVE times, each a fresh cluster, all converge** — this
matches the prior gate semantics ("3 consecutive converged"). It is NOT 3 kills in one cluster (auto-heal
is off, so nodes are not replenished; repeated kills would exhaust the cluster). The gate runner invokes
the class 3×.

**Structure — RECOMMENDED (DRY):** extract the shared flow into a package-private base
`AbstractStreamOwnerFailover` with two hooks:
- `void pinMembership(EmberCluster cluster)` — no-op in the unpinned subclass; auto-heal-off in the pinned one.
- phase-9 mode — `boolean assertsConvergence()` (false = soft sensor, true = hard).
Then `StreamOwnerFailoverTest extends AbstractStreamOwnerFailover` (unpinned, soft) and new
`StreamOwnerFailoverPinnedTest extends AbstractStreamOwnerFailover` (pinned, hard). Separate classes ⇒
separate `EmberCluster`s ⇒ no shared-cluster interference (the class is `@TestInstance(PER_CLASS)` +
`@Execution(SAME_THREAD)` with a shared `@BeforeAll` cluster + one deployed blueprint, so a second
kill-test in the SAME class would run on the degraded cluster — do NOT co-locate).
**Fallback if the refactor is too large for the session:** duplicate the class as
`StreamOwnerFailoverPinnedTest` with the pin added and phase 9 hard. Duplication is acceptable; interference is not.

**The pin (robust to post-kill leader change):** disable auto-heal on EVERY node's CTM right after the
cluster is ready and members are full (in the pinned `@BeforeAll`/`setUp`, after `allNodesAreMembers(NODES)`):
```java
cluster.allNodes()
       .forEach(node -> node.clusterTopologyManager()
                            .onPresent(ctm -> ctm.setAutoHealEnabled(false, "membership-pin: #491 pinned convergence variant")));
```
Disable on ALL nodes (not just the current leader) because the killed HRW owner MAY be the leader, and only
the leader's CTM acts — so whichever node becomes leader post-failover must also have the flag off.
(API: `EmberCluster.allNodes()` :598 → `List<AetherNode>`; `AetherNode.clusterTopologyManager()` :1437 →
`Option<ClusterTopologyManager>`; `ClusterTopologyManager.setAutoHealEnabled(boolean,String)` :58.)

Everything else = the existing failover flow, phase 9 HARD (`convergedWithRfRestored`).

### 1b-gate. EMPIRICAL CHECK (this is the pivot the owner ruled on)
Run the pinned class 3× consecutively (see Task 3 verify commands). Read the per-run finalView.
- **If it converges 3×** → DONE. Batch stays test-only. Skip 1c.
- **If phase-9 still times out** (survivor false-FAULTY during PASSIVE persists — LIKELY, per the
  sof-1-stuck-DEAD evidence, because auto-heal-off removes only the *compounding* empty-replacement churn,
  not the *primary* PASSIVE-window eviction) → escalate to 1c. **Do NOT stack blind iterations** — if 1c
  also fails, STOP with fresh finalView forensics and re-consult (owner's stop-rule).

### 1c. (ONLY if 1b-gate fails) minimal EmberCluster SWIM-timeout seam
Add a builder-style seam on `EmberCluster` mirroring `withDataBaseDir(Path)` (:226), e.g.
`withRaisedSwimTimeouts()` (or a field set before `start()`), that threads a non-default `TimeoutsConfig`
(raised SWIM `suspectTimeout`, e.g. 30–60s ≫ the transient PASSIVE window) and a membership override
(raised `splitTimeout`) into `createNode`'s `AetherNodeConfig` construction — currently hardcoded at
`EmberCluster.java` :682 (`TimeoutsConfig.timeoutsConfig()`) and the membership `Option.empty()` at ~:691.
SWIM derives from `TimeoutsConfig` at `AetherNode.java:2270-2274`; config keys for reference:
`timeouts.swim.{period,probe_timeout,suspect_timeout}` (`ConfigLoader.java:667-671`),
`membership.split_timeout` (`ConfigLoader.java:144-149`, default 15s `MembershipConfig.java:36`).
Apply the seam ONLY to the pinned variant's cluster. The killed owner still departs via graceful SWIM
leave (`handlePeerLeft`, not suspect-timeout-driven), so raising `suspectTimeout` does NOT slow the real
owner failover — it only stops LIVE survivors from being falsely FAULTY during the transient. Re-run 3×.
This makes the batch a 7-file change (adds `EmberCluster.java`). Note it in the commit + review.

---

## TASK 2 — review the full diff (before landing)
- **Already pre-reviewed clean:** `QuicClusterNetwork.java` (unicast-buffering + eager-PeerState) and
  `AetherNode.java` (committed-owner hoist). See the layer notes above.
- **STILL TO REVIEW:** `PartitionBackfill.java` (+210 — the probe-first reverify; the substantive one),
  `PartitionBackfillTest.java`, `QuicClusterNetworkReconcilerTest.java`, and the FINAL
  `StreamOwnerFailoverTest.java` (after 1a) + `StreamOwnerFailoverPinnedTest.java` (1b) + any
  `EmberCluster.java` seam (1c).
- Use `jbct-reviewer` on the product files; the transport diff is the sensitivity point (already clean).
  This is transport/stream product code → **full reviewer round** (owner's instruction).

## TASK 3 — verify → land → file SWIM issue → close/scope #491 → re-point candidate
**Verify (build-runner owns all maven; NEVER run maven inline; NEVER with `HCLOUD_TOKEN` set — it spawns a
paid Hetzner server):**
```
env -u HCLOUD_TOKEN mvn -pl aether/node -am -DskipTests install     # consensus+stream+node → ~/.m2
env -u HCLOUD_TOKEN mvn -pl integrations/consensus test              # 699/0 incl. reconciler buffering
env -u HCLOUD_TOKEN mvn -pl aether/aether-stream test                # 628/0 incl. WriteIdleResidualReverify
# forge gate — pinned class 3× + fanout regression (detached; per-run converged/finalView):
env -u HCLOUD_TOKEN mvn -pl aether/forge/forge-tests test -Dtest=StreamOwnerFailoverPinnedTest   # ×3 consecutive
env -u HCLOUD_TOKEN mvn -pl aether/forge/forge-tests test -Dtest=StreamFanoutConsumerTest         # 5/5
```
(`jbct.skip` is handled by the POM hierarchy — NEVER pass `-Djbct.skip=true` for aether modules.)

**File the SWIM issue** — the FULL body is embedded at the bottom of this file ("SWIM ISSUE BODY").
`gh issue create --title "…" --body-file <that text> --label bug` (or paste). Capture its number `NNN`.
Then update the re-softened phase-9 comment (Task 1a) + any code comment to cite `#NNN`.

**Land as ONE commit** (single-line message per git rules — NO body, NO trailer):
```
git add <the 6 (or 7) files>
git commit -m "fix: buffer unicast to absent members + probe-first backfill reverify + committed-owner gate (#491)"
```

**Close/scope #491** (title: "Stream backfill catch-up stalls: promoted owner has no QUIC peer connection
to the survivor after owner-kill churn"): RECOMMEND **close #491** — the transport hard-drop + backfill
reverify + committed-owner layers it named are resolved (drops=0, phases 1–8 green, pinned variant converges).
Post a closing comment: (a) resolved-by SHA + which layers, (b) the residual "no QUIC peer connection"
symptom is the SWIM false-removal split out to `#NNN`, (c) link the pinned-variant proof. The phase-9
harden-comment on #491 already cites resolved-by — verify/refresh it.

**Re-point candidate + watch CI** (standing policy `feedback_candidate_tag_after_each_batch`): move the
moving tag `v1.0.0-rc3-candidate` to the new HEAD, force-push, watch the Release CI run to green.
```
git tag -f v1.0.0-rc3-candidate HEAD && git push -f origin v1.0.0-rc3-candidate
gh run watch  # or watch the Release workflow
```

---

## OPERATIONAL GOTCHAS (carry-over — all bit us this arc)
- **PULL FIRST** — design-stream (#448/#443) pushes to `release-1.0.0-rc3`; expect non-fast-forward.
  Rebase; beware dirty-tree refusals (the batch is uncommitted — `git stash` or land first, then pull).
- **`build-runner` owns every maven invocation.** A stray inline `mvn` once created a real Hetzner server.
  Never `mvn verify` with `HCLOUD_TOKEN` set (Failsafe → `HetznerCloudIT` → paid VM).
- **`jbct.skip` POM hierarchy** handles skip flags — never `-Djbct.skip=true` for aether.
- **Scratchpad ages out & is session-private.** This session's forensics live in prior-session scratchpads
  that WILL age out — everything durable is either in this file, `/tmp/gate-run-1.log` (also ages out), or
  the #491 issue comments. The SWIM issue body below is the durable copy of the evidence chain.
- **MAILBOX.md** (repo root, COMMITTED) = inter-stream coordination log. The Editor gap-drain transport
  `aether/docs/internal/coordination/MAILBOX.md` is UNTRACKED (distinct file).
- **`/api/streams/replicas` over HTTP is delegate-routed (#490)** — the forge test reads the
  owner-authoritative view IN-JVM via `node.streamReadRouter().replicaSnapshot(...)` where `servedByOwner()`
  is true (see `ownerView()` in the test). Do not switch it to HTTP.
- **Agents drop reports chronically** — if a spawned agent goes quiet, check the task board / tree /
  scratchpad before re-instructing; the work is usually done, only delivery failed. File-based handoff
  (agent writes to a scratchpad path you Monitor) is the reliable fallback.

## EVIDENCE POINTERS
- Terminal gate log: `/tmp/gate-run-1.log` (drops=0; `swimDeadStuck=[sof-1]` at 16:55:31.545). May age out.
- Prior-session scratchpads (may age out): `…/0c7baa2a-…/scratchpad/{gate-forensics.md, 491-verdict.md, 491-f2-impl.md}`.
- #491 issue comments carry the durable diagnosis + corrections trail.
- Dial-skip authority: falsely-FAULTY survivor dropped from FSM desired set
  (`AetherNode.java:2709` `setDesiredConnections(desiredDialTargets(membershipFsm))`) → reconciler never
  dials it (`QuicClusterNetwork.java:2426-2463`). Faulty routing: `SwimHealthState.java:147-153` →
  `SwimHealthContext.routeFaulty:291-303` → `MembershipFsm.onConfirmedDeparture` →
  `departurePermanent` (`AetherNode.java:2666-2669`).

---

## SWIM ISSUE BODY (file verbatim as the new scoped issue)

**Title:** SWIM false-removal of LIVE survivors during the post-kill PASSIVE window wedges stream RF-restoration (dial layer refuses SWIM-dead peers)

### Summary
When a cluster node is removed (graceful `killNode` / SWIM leave), the transient `QuorumLost → PASSIVE`
window causes **live** survivor↔survivor QUIC links to be evicted and the survivors to be marked
SWIM-**DEAD and stuck**. Because the dial layer consumes raw SWIM state and refuses to dial a SWIM-dead
peer, any buffered inter-node send to that survivor never drains until the stuck-dead state clears —
which, under the 60 s single-dialer higher-id grace, can exceed the convergence window. Net effect on
streaming: a promoted/replacement stream replica cannot catch up from a live survivor, so RF-restoration
stalls (`SYNCING@-1` indefinitely). Fresh manifestation of the SWIM-latency-under-churn defect **class**
(cf. the since-closed #94 NODE_FAILED-under-load work). Surfaced by `StreamOwnerFailoverTest` phase 9
while validating the #491 batch.

### Precise guarantee gap (not a one-bit label)
- **Expected:** after a single-node loss in a 5-node cluster, the 4 live survivors remain mutually
  reachable and any partition whose owner died re-forms RF among the survivors within the convergence window.
- **Actual:** during the PASSIVE window the liveness sweep evicts quiet-but-healthy follower↔follower links
  and SWIM marks live survivors DEAD; the dial layer then withholds dials to those (actually-live) peers.
  Reachability among survivors is **not** preserved across the window — only restored after the stuck-dead
  state clears / the higher-id grace elapses.
- **Mechanism:** leader-only liveness pinging pauses during PASSIVE (no leader) → sweep has no fresh
  liveness → treats quiet links as stale.

### Evidence (gate-run-1, 2026-07-19)
- Leader = sof-1; partition `repl-failover-events[0]` HRW owner = sof-3 (a follower, NOT the leader).
- Kill sof-3 → `Entering QuorumLost — leader invalidated` + `ClusterStateNotification[state=PASSIVE]`.
  The killed node was a follower, yet the leader was still invalidated → the PASSIVE window is triggered
  by the membership change itself, not by killing the leader.
- `processViewChange: op=REMOVE, peer=sof-1` + `evictStaleConnection: Node sof-1 evicted stale (inactive)
  link` — sof-1 is the ALIVE leader (`QuicClusterNetwork.java:1980`).
- `QUORUM_LOSS drain SUPPRESSED ... countedMembers=5 swimAliveStuck=[] swimDeadStuck=[sof-1]`
  (`QuorumLossDetector.suppressedByCoConfirmation`) — SWIM holds the alive leader DEAD-stuck.
- Dial gating: `ConnectionDirection.shouldInitiate = self.compareTo(peer) < 0` (ConnectionDirection.java:31)
  + `RECONCILE_BACKOFF_CAP_MS = 60_000` (QuicClusterNetwork.java:184): higher-id side waits up to 60s.
- Stream-layer consequence (the ONLY residual after the #491 batch — transport hard-drop class CLOSED,
  drops=0): the replacement replica's catch-up send is correctly BUFFERED but cannot drain because the dial
  to the SWIM-dead survivor never fires → `promoted owner BEHIND survivor ... target frozen` → phase 9 timeout.

### NOT this issue (fixed in the #491 batch)
Unicast hard-drop of a null-but-member peer (now buffers + drains on attach); backfill blind 5s re-poll
(now probe-first re-verify); empty-node HRW self-election (now committed-owner gated). With these,
`drops=0` and phases 1–8 pass; failure isolated to the SWIM residual.

### Fix directions (deferred C-layer menu — pick during scheduling)
1. Gate the liveness sweep on consensus-active — do not evict follower↔follower links while PASSIVE/QuorumLost.
2. Leader-independent CONTROL-lane keepalive so links stay warm across the PASSIVE window.
3. SWIM false-removal suppression under churn — require corroboration before DEAD-stuck (the
   `QuorumLossDetector` co-confirmation already models this for quorum; extend to the dial-gating consumer).
4. Dial-layer: treat a SWIM-dead-but-topology-member peer as dialable during the re-form window (bounded).

### Acceptance
- `StreamOwnerFailoverTest` phase 9 (unpinned, currently a soft SENSOR pointing here) flips back to HARD
  and converges 3×. The membership-pinned variant (landed with the #491 batch) already proves the
  above-SWIM layers converge; closing this issue removes the pin.

### Cross-refs
#491 (transport/backfill layers — resolved by the batch that surfaced this); SWIM-latency class (since-closed #94 lineage).
