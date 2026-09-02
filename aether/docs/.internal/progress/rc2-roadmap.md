# v1.0.0-rc2 Roadmap

**Status:** active · **Branch:** `release-1.0.0-rc2` · **Created:** 2026-06-14
**Milestones:** `v1.0.0-rc2` (#9), `v1.0.0-rc3` (#10) · `v1.0.0-rc1` (#6) closed.

> Goal (unchanged from RC1): **production readiness.** Prioritize where we gain the most
> readiness weight for the least effort, **foundational parts first**. RC2 is the hardening
> RC: close the genuine gates (security, data integrity, convergence-under-load). Breadth,
> DX, and docs ladder to RC3. Truly unscheduled work is `deferred`.

## Label ladder

| Label | Meaning |
|-------|---------|
| `rc2` | Foundational must-fix to call the runtime production-capable. Gated for this RC. |
| `rc3` | Planned, but not RC2 — breadth, DX, docs, feature-completion. |
| `deferred` | No committed timeline; demand-driven or speculative. |
| `post-ga` | Planned for after 1.0.0 GA. |
| `on-demand` | Pulled in when a concrete consumer needs it. |

## RC1 close-out (done 2026-06-14)
- Shipped, merged to main (`786cb7432`), published to Maven Central, tagged `v1.0.0-rc1`.
- Public security claim corrected on main + rc2 (`62b49778b` / `7595d6917`): default is
  `SecurityMode.NONE` (OFF), not on.
- First RC2 bug #274 (pub/sub namespace-blind routing) fixed (`9051757c0`).
- **9 issues re-verified against code; 7 closed with evidence** (#146 #156 #173 #174 #177
  #247 #284). **2 refuted and kept open:** #166 (handleNodeRemoved is a log stub — no KV
  prune) → RC2; #73 (4 of 7 cloud milestones untested) → RC3.

---

## RC2 scope — foundational must-fix

Organized as parallelizable **lanes** (see worktree map below). ~28 issues.

### Lane S — Security hard-gate
The #1 gate. Single-trust-domain today; RC2 makes security real.
- **#290** management plane open by default (`SecurityMode.NONE`) — make security default-on.
- **#282** artifact-repo Maven push endpoint has no auth (arbitrary code into the cluster).
- **#289** re-bootstrap config push has no version fence (`expectedVersion:0` skips CAS).
- **#299** authorization is coarse path-prefix matching, not per-operation.
- **#287** harden `cluster_secret` at rest (chmod 600; keep off `docker -e`/argv).
- **#295** single-node quorum allows split-brain at formation (`quorumOf(1)==1`).
- **#293** INITIAL_STATE never delivered when security enabled (secured clusters show empty).
- **#209** replace cloud-bootstrap TLS skip-verify with `cluster_secret`-derived CA trust.

### Lane T — Stream integrity (correctness / data-loss)
- **#260** silent replica divergence — receiver ignores `fromOffset`, no verify/repair.
- **#261** backfill never fires for a fresh replica; any ack promotes SYNCING→CAUGHT_UP.
- **#262** `minSyncReplicas` accounting is hollow (identityless acks, self-counted set, race).
- **#266** batch publish misroutes keyed events (whole batch → first event's partition).
- **#267** `/api/events` leader-bound → 503 during churn (availability regression).
- **#274** pub/sub: namespace routing fixed; verify remaining `TopicSubscriptionKey`
  nodeId / lossy-delivery facet before close.

### Lane R — Reconciler & consensus under load
The "reconciler-under-load defect class" — fine when settled, pathological under churn.
- **#325** S20 post-wipe redeploy: slice wedges in ROUTING, never reaches ACTIVE.
- **#329** gate leadership / task-group ownership on consensus readiness (lagging leader).
- **#331** reconciler over-provisioning does not converge to coreCount under heavy churn.
- **#166** CTM phantom nodes persist HEALTHY in KV after removal (config-atom prune missing).
- **#148** cap CTM retry count to prevent runaway container creation.
- **#258** Rabia stall detector re-broadcasts votes but not proposals → phase deadlock.
- **#246** `LeaderReconcilerTest` broken at HEAD (19 failures + 2 errors) — must go green.
- **#236** bounded dissolution gate counts transport-reachable peers, not synced voters.
- **#256** gossip-key day-rollover locks out next-day joiners (UTC-midnight boundary).

### Lane Q — Quick wins (S effort, H/M weight)
- **#251** ContentStore slice resource cannot provision (StorageInstance ext reg — #99 regr).
- **#259** cluster status shows descriptor role for worker-demoted nodes (assigned role hidden).
- **#301** destructive CLI commands (drain/scale/migrate/restore) execute with no confirmation.
- **#302** dashboard client calls wrong API paths/verbs → silent 404s.
- **#308** structured-error gaps — server 500 fallback for unmapped causes; CLI ignores
  `--format json` on errors.

---

## RC3 scope — breadth / DX / docs (planned-later)
High-level groupings (all `rc3`, milestone `v1.0.0-rc3`):
- **Stream durability/throughput:** #248 segment sealing, #249 S3 remote tier, #263
  ReplicationBatcher, #264 durable cursors, #265 ring-hydration memory.
- **Dashboard:** #291 #292 #294 #303 #304 #305 #312 (the non-RC2 panel/contract fixes).
- **Interceptors / resource SPI:** #268 #269 #270 #271 #275 #276 #278 #279 #280 #172.
- **@Scheduled:** #272 #273.
- **Persistence / storage:** #250 #252 #253 #254 #255.
- **CLI:** #309 #310 #311.
- **Cloud breadth:** #206 #222 #224 #296 #297 #298 #306 #307 #120 #147.
- **Mgmt-API consolidation:** #188 #189 #190 #198 #220 #226 #300 #212 #233.
- **Membership/topology:** #134 #154 #155 #164 #176 #178 #230 #234 #235 #241 #277.
- **Docs epic #314:** #313 #315 #316 #317 #318 #319 #320 #321 #322 #323 #324 #283 #310.
- **DX/scaffolding/tech-debt:** #142 #143 #151 #152 #162 #169 #170 #171 #179 #144 #165
  #184 #207 #210 #211 #232 #244 #281 #73.

## Deferred (no committed timeline)
- Core API ergonomics bikeshed: #2 #3 #4 #5 #6 #9 #227.
- #231 φ-accrual spike (the detector was removed from prod; spike is moot).
- #205 cross-namespace stream RBAC (on-demand).
- #119 HashiCorp Vault secrets, #136 #137 streaming read guarantees.

---

## Parallelization map (worktree lanes)
Lanes chosen for minimal file overlap → safe concurrent worktrees + delegation/teams.

| Lane | Primary surface | Issues |
|------|-----------------|--------|
| **S** Security | `ManagementServer`, `*SecurityValidator`, `AppHttpConfig`, artifact-repo, bootstrap TLS | #290 #282 #289 #299 #287 #295 #293 #209 |
| **T** Stream | `StreamPartitionManager`, replication (`ReplicationManager`/`ReplicaPlacement`), `/api/events` | #260 #261 #262 #266 #267 #274 |
| **R** Reconciler | `LeaderReconciler`, `NodeDeploymentManager`, `ClusterTopologyManager`, `MembershipFsm` | #325 #329 #331 #166 #148 #246 #236 |
| **C** Consensus | `RabiaEngine`/stall detector, gossip-key | #258 #256 |
| **Q** Quick-wins | storage ext-reg, CLI confirm, dashboard paths, error mapping | #251 #259 #301 #302 #308 |

Notes:
- Lanes S, T, R, C, Q touch disjoint packages — run as independent `isolation:"worktree"` agents.
- **#290 (security default-on)** and **#329 (consensus-readiness gating)** are behavior
  changes with config/operational impact — design-confirm before implementing.
- Each lane: fix → module test → JBCT lint → reconcile (spec→code) before commit; full
  `./build.sh` gate before merge; integration sweep for R/C/T lanes.

## Verification discipline (carried from RC1)
- Verify every "done" claim against code before closing an issue (2 of 9 were refuted).
- Verify public claims (README/CHANGELOG/release notes) against code before publishing.
- Reconcile spec→code (DONE/MISSING/STUB/SHORTCUT/OMISSION) — commit only at all-zero.
