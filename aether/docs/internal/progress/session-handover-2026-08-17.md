# Session handover — 2026-08-16/17: one defect class, seven fixes, and 02y turned green

**Branch:** `release-1.0.0-rc3` · **HEAD:** see git log (handover amended after the design ruling) · **ALL PUSHED** · tree clean ·
**candidate tag re-pointed once, to `cf3f26b5e`** (deliberately not re-pointed again for `cd36c2c27` —
a second re-point in quick succession is what races the Release asset uploads).

Started from the previous handover's §5 open question. It resolved in the first twenty minutes, and
everything after came out of chasing why.

**rc3 open issues: 44 → 39.** Six closed (#597, #568, #539, #352 by evidence; #601, #592 by fixing).

---

## §1 The approved question, answered: the fence was innocent

02y was re-run against the fixed build. Both failures reproduced **identically**, and `CORE_ABSENCE`
appears zero times in the run log and in all four surviving node logs. The core-absence fence was not
drawing blood. Cluster B declares all five hosts core, so the suppressor makes the detector structurally
unable to fire there — which also means **#590's community-tier fence still has no live verification**.

The "known pre-existing failure" alibi turned out not to exist: #593 and #594 are both CLOSED, and the
comment citing them explains why 02y was *split out* of 02-chaos, not that 02y has a known-failing test.

## §2 What was actually wrong — three independent roots

02y's two failures had **three** causes, none of them the fence.

**A. Forwarded publishes bypassed min-sync entirely** (`c04241af7`). Both writers route on LOCAL RING
PRESENCE, not ownership, and each awaits replication only on its local-append arm. A publish forwarded to
the owner was acked by `StreamForwardHandler.onPublishForward` → `publishForwarded` → `publishLocal` with
**no `awaitReplication` anywhere** — so it silently ran at min-sync 1 however the stream was configured.
The barrier now sits on the OWNER, where the ack is produced, covering both writer paths at once.

**B. A stalled backfill held its reshuffle slot forever** (`08a30528e`). A slot released only when the
partition stopped being a not-caught-up REPLICA, and `PartitionBackfill` retries forever once its bounded
wait elapses with a committed owner present. The release condition was exactly the condition that would
never become true. Measured: `entity:orders[4]`/`[6]` held BOTH slots for 4m55s with **zero** releases,
while the two `multipart-events` partitions queued behind them never became in-sync and were lost when
their owner was killed. Slot tenure is now bounded, preemption gated on a non-empty queue.
`reshuffle_concurrency` also became a real config key — it was a hard-coded constant that the error
message named as though it were tunable.

**C. The leader destroyed a healthy, serving slice** (`d9b37e180`). See §3.

**The three compose.** Any one alone leaves 02y red: the barrier makes the ack honest, preemption makes
the guarantee *achievable*, the remediator fix stops a converging slice being destroyed.

## §3 The correction worth reading — the timeout guard was never broken

First reading was "a timeout guard silently failed to fire". **That was wrong**, and the truth is worse.

`Promise.timeout()` arms a one-shot `fail()` against the same instance and returns `this`; `resolve()` is
CAS-guarded. It is a deadline on that promise, and a no-op once resolved. It did not misfire — node-2's
activation chain **succeeded**, so there was nothing to fire at.

The slice was ACTIVE and serving: node-2's own log shows
`test-stream-multipart-stream-slice/publish depth=0 duration=25.591363ms` at 23:15:15. The **leader's**
projection still read ACTIVATING and force-UNLOADed it 35s later. `StuckTransitionalRemediator` judged a
slice by `Active.sliceStates()` — an in-memory map — and never re-read the committed `NodeArtifactValue`.

## §4 ⚠️ THE FINDING THAT GENERALISES: one defect class, seven instances

A value a component reports about ITSELF, or a local projection of a committed authority, read as
observed truth by code that then acts on it. **The tell: a value that cannot expire.** Under partition or
a missed transition it does not go stale — it FREEZES at its last good value, which reads as healthy
forever.

Known before: #593 (`SYNCING` seed), #508 (status field), #590 (`memberCount`), the remediator (§3).

A read-only audit calibrated on those four found **three more, all live**:

5. **`CommunityPlacementPlanner`** read `announcement.members()`/`memberCount()` raw. `CommunityLivenessView`
   — built as #590's own fix — had exactly ONE consumer in the codebase, and this was not it. So the core
   kept weighting a partitioned community at full size and naming unreachable nodes in directives:
   #590's stated consequence at a grain its ACTIVE/DEGRADED gate cannot catch, because a community can sit
   above the viability floor while having lost members. **A fix applied at one reader is not applied.**
6. **`StaleEntryCleaner.cleanupOrphanedSliceEntries`** classified slices orphaned from `active.blueprints()`,
   a projection rebuilt only on `Active` entry, then force-UNLOADed them cluster-wide every reconcile tick.
   One missed blueprint put would do that for a whole leader term.
7. **ACTIVATING had no node-local remediation at all** (#601) — a bare `case ACTIVATING -> {}`.

Fixed in `36712ba5a` and `cf3f26b5e`. **The fix shape is identical and cheap every time:** re-read the
committed authority before acting destructively, and skip destruction ONLY on a positive reading of a
settled state — absent, unreadable, or agreeing-with-the-view all fall through to prior behaviour, so the
guard can only ever spare, never strand. ~10 lines plus two tests each.

**Repeat this audit.** One agent-hour, three real defects, plus a clean checked-and-cleared list.

### Still open from the sweep — the one I did NOT fix
`ReplicaDescriptor.state == CAUGHT_UP` never downgrades. `ForwardingReadRouter` can therefore serve reads
from a replica that stopped acking (stale data, no error), and `caughtUpOthers` over-counts so an owner
may release its ring believing enough replicas are caught up.

**DESIGN DECIDED (owner, 2026-08-17) — lag-based, bound expressed in OFFSETS. See §11.**

## §5 A safety threshold derived from what the failure controls (#557, second defect)

`RabiaEngine.syncQuorumSize()` computed `min(connectedNodeCount(), clusterSize) / 2 + 1` — **1** at
connectivity 0 or 1. A node reaching one peer would `restoreState` from a SINGLE response, adopting
consensus state precisely when least likely to be on the majority side of a partition. The docstring
justified it as *"adapts to actual connectivity"*: the defect stated as its own rationale.

Now a majority of the CLUSTER. **One-way — strictly stricter**, so it cannot admit anything previously
refused; the cost is liveness, deliberately.

**The test fallout was the evidence.** Exactly two tests broke, both in a fixture building a **5-node**
cluster and feeding **2** sync responses — a minority that only ever activated because the gate had
collapsed. The 3-node fixture, where 2 IS a majority, stayed green. **Fix the fixture, never the
assertion.**

## §6 The harness was not testing what anyone thought

`run-tests.sh`'s Step-1 build guarded on `${REPO_ROOT}/build.sh` where `REPO_ROOT` is the **aether**
directory — `build.sh` lives at the repo root. Always false. **The harness never built**, `--skip-build`
was a no-op, and every run silently used whatever jars were on disk. That is how the 2026-08-15 run
tested a PRE-fix build. Now a hard error; `--skip-build` warns that provenance is unverified.

Two more harness gaps closed (`f4d8f5b01`): 02y continued after its own deploy gate failed (producing a
durability verdict on a cluster that never converged), and owner discovery queried partition 0 only —
which produced a wrong premise in this very investigation, since node-4 owned partitions 0 **and** 2. The
full ownership map is now logged before the kill.

## §7 Verification — 02y is green

`02y-stream-crash` + `02w-entity-crash`, remote cluster B: **2 suites, 2 passed, 0 failed.**

| | before | after |
|---|---|---|
| deploy to all-instances ACTIVE | timed out at 240s | **3s** |
| ACKED events surviving the crash | 39 of 80 (41 lost) | **80 of 80** |
| non-empty partitions post-crash | 2 of 4 | 4 of 4 |
| 02y suite | FAIL, 327s | **PASS, 85s** |

Not vacuous — the non-vacuity gate confirms 80 acked events were actually checked. **One run**, which
meets the catalog's Integration-verified bar (multi-node, failure injection) but does not establish the
absence of a race.

Everything else this session is `[verified: unit + mutation]` and tagged as such. Every fix was
mutation-checked: reverting it turns exactly the pinning test red and leaves the control green.

## §8 rc3 triage — the milestone was overstated

**44 open, not 32** (an earlier count truncated). Seven were labelled `blocking`, and four of those labels
were stale. Closed with evidence: **#597** (auto-heal static-init, live-verified 41s replacement),
**#568** (committed-owner liveness), **#539** (premise wrong — routes exist and 501 honestly), **#352**
(fenced entity provisioned and refuses to degrade). **#264** got a retriage note rather than a close: its
headline claim is false at HEAD (`CursorStore.commit` persists via `storage.put`+`replaceRef`), but it
carries `blocking` and may mean something narrower — owner's call.

**Genuine remaining blockers, ranked:** #596 (entities unusable behind the shipped ingress), #590
(mechanism built, needs a real partition run), #509 (its mask #597 is lifted, needs one confirming run).

**Not filed, worth filing:** `aether cluster autoheal off` is cosmetic — `autoHealEnabled` is read only by
the status route, `LeaderReconciler` never consults it. **FILED as #603.** And #444's premise is wrong:
`SourceProfile` is already provider-agnostic; the real residual is that auto-heal replacements provision
**unfirewalled** — **retriaged on #444, and now §11, the first item of the next session.**

## §9 Doc corrections
- Catalog row 139 (sync replication ack) Complete → Partial, with the forward-path gap.
- Catalog row 97 claimed "Complete / Zone-aware group computation" while zone was string-split out of the
  NodeId — corrected, then re-corrected after #592 landed.
- `known-limitations.md` cited `CoreAbsenceDetectorTest 13/13`; the file has 16.
- The `min-sync-replicas ≥ 2` remedy named in known-limitations was itself the guarantee that did not hold.

## §10 Next

**Owner-decided ordering (2026-08-17): §11 first, then §12.** Both are DESIGN-SETTLED — implement as
specified, do not re-open the choice. Then #596; #590 and #509 need cluster runs, not code.

## §11 FIRST — unfirewalled auto-heal replacements (residual of #444, see its retriage comment)

**The defect.** `buildCreateRequest` takes firewall IDs (`HetznerComputeProvider.java:367` ←
`config.firewallIds()`), and at runtime that resolves to empty
(`HetznerEnvironmentIntegrationFactory.java:75`, `getOrDefault("firewall_ids", "")`). `firewall_ids` is
populated ONLY on the CLI bootstrap path (`ProviderResolver.java:236`). `SourceProfile` persists firewall
**rules**, never the created firewall's **id**. So every CTM-provisioned auto-heal replacement is created
with **no firewall association**, and the code states the consequence itself at
`HetznerComputeProvider.java:455`: such servers **accept ALL inbound**. The window `ProviderResolver.java:205-207`
was explicitly written to close is closed for bootstrap nodes and wide open for every replacement.

**DECIDED — mechanism: resolve by label at create.** The capability already exists and is used by the
ingress path: `client.listFirewalls(firewallSelector(cluster, sourceId))`
(`HetznerComputeProvider.java:475`), one firewall per `(cluster, source)`. When `config.firewallIds()` is
empty, resolve through that selector and pass the result to server-create.

Rejected, and why — do not revisit without new information:
- *Persist the ids at bootstrap* — adds state that goes stale the moment the firewall is recreated out of
  band, and staleness in a security control is the worst failure mode available here.
- *Re-create from `SourceProfile.firewallRules`* — viable (create/patch is already idempotent, `:449-451`)
  but heavier per provision and turns rule drift into a silent reconciliation.

**DECIDED — failure policy: FAIL THE PROVISION when no firewall resolves.** This is a deliberate
behaviour change: today the node is created anyway. A missing replacement is a visible, recoverable
degradation; a publicly-reachable one is neither. Same fail-safe direction as every other fix in this
session — refuse on a negative reading rather than proceed on an unknown.

**Verification note.** End-to-end verification means provisioning real PAID servers. Land with unit
coverage and tag the claim honestly as **not cloud-verified** unless a run is explicitly authorised.
Do not quietly imply cloud verification.

## §12 SECOND — CAUGHT_UP staleness on the read path

**DECIDED — lag-based freshness, bound expressed in OFFSETS, not time.**

A replica counts as caught-up FOR READ SERVING only when its `confirmedOffset` is within a bounded
distance of the partition's current high-water.

**A time-based TTL was considered and REJECTED — this is the important part to not re-derive.**
`updateWatermark` is driven purely by acks and backfill milestones (`DefaultReplicationManager.java:94`,
`PartitionBackfill.java:669/875/906/1055/1158`); NOTHING refreshes it on a quiet partition. A TTL would
therefore age out every replica of a write-idle stream and stop serving reads from the healthiest streams
in the cluster. This is the same trap `#333` documented in its own seam — *"on a write-idle partition (no
live batch re-arms the gap loop) it would serve stale/empty data forever"* — and note that #333 also did
NOT reach for a timer: it added a caller-supplied owner-aware predicate and kept the registry free of
topology knowledge.

Lag is naturally correct when quiet: if the owner has not advanced, there is no lag, so no false
staleness. It also catches the asymmetric-partition case that motivated the finding — a replica readers
can still reach but which stopped acking to the owner falls behind while writes continue.

**Still to choose during implementation:** the bound itself. Zero (exact high-water) is too strict —
replication is asynchronous by design, so a healthy replica is transiently behind on every write. Pick a
small offset bound (or "behind for more than N reconcile ticks"), and keep it expressed in offsets so it
stays immune to the idle-stream problem above.

**Apply at both consumers**, not one: `ForwardingReadRouter.isCaughtUp` (which selects read targets) and
the `caughtUpOthers` count feeding the ring-release guard (`AetherNode.streamCatchupView`). Fixing one
reader and not the other is exactly the half-applied-fix mistake that left #590 live at the placement
grain (§4, instance 5).

## §13 After those
1. **#596** — top remaining blocker (durable entities unusable behind the shipped ingress).
2. **#590 / #509 verification runs** — both need a cluster, neither needs code.
3. **#599** zone test — now unblocked: the zone axis genuinely enters formation (#592), so a two-zone test
   can no longer pass for the wrong reason.
4. **#603** — `aether cluster autoheal off` is cosmetic (filed 2026-08-17).
