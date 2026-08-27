# Session Handover — 2026-06-16

## ⚡ TL;DR

Turned the Hetzner **container** cloud integration sweep into a fix-loop and validated rc2 on
real cloud iron. **Cluster A went to 9/10 green** (was failing 08-resources + 11-observability).
Along the way, surfaced and fixed **two genuine RC2 product bugs** (not test/harness) plus
several cloud-harness completeness gaps.

- **11 commits** on `release-1.0.0-rc2`, **all pushed**; `v1.0.0-rc2-candidate` tag + ghcr image
  rebuilt at HEAD **`13e9eb9f6`**.
- **08-resources: fully GREEN on cloud.** **11-observability: partially fixed** — one layer remains.
- Headline product bugs fixed:
  1. **Slice config override precedence was backwards** — deployments could not override a slice's
     LOCAL-targeting config. Generic config-resolution bug; docker masked it.
  2. **Stream cold-start backfill deadlock** — `system:cluster-events` never reached CAUGHT_UP on a
     multi-node cluster, so `/api/events` returned `[]`. Docker masked it (single-JVM timing).
- **Open:** 11-obs forward-read layer, cluster B (5 suites, harness-cloud gaps), JVM runtime, #59.

---

## 1. Commits this session (oldest → newest)

| Commit | What |
|---|---|
| `e32f0fcfc` | feat: node endpoint-resolve + live-nodes management APIs (Tier A1/A2) |
| `866b4c710` | docs: endpoint-resolve + live-nodes |
| `cd6d77d27` | test: management API schema-stability contract (A3) |
| `e0e1519ea` | feat(test): C7 connectivity preflight |
| `74d8ada42` | fix: clear JBCT lint blockers (tcpConnect→Result, @Contract emitIdentityEnv twins, formatter drift) |
| `118393962` | fix(test): export AETHER_INSECURE_DEV_MODE for cloud nodes (artifact-push gate) |
| `48c5053ed` | fix(test): flat `[database]` node_config for @PgSql migration + scale 11-obs convergence by TIMEOUT_SCALE |
| `f6707221e` | **fix(slice): deployment override must win over slice intrinsic resources.toml (LayeredConfigProvider order backwards)** |
| `121929996` | test(slice): pin deployment-override-wins precedence (regression) |
| `e0b91dc01` | fix(test): override slice `[database]` creds with PG-VM user/pass; provision-test-pg emits PG_USER/PASSWORD/HOST/DB |
| `13e9eb9f6` | **fix(stream): break cold-start backfill deadlock — owner self-promotes + periodic re-drive** |

First 4 (+ lint fix) integrate the Tier A + C7 lanes from the prior session (gated full `build.sh`
green, then pushed). The rest are the cloud-sweep fix-loop below.

---

## 2. The cloud-sweep fix-loop (container runtime)

Ran `./run-tests.sh --env cloud --runtime container`. Each failure → stop, root-cause, fix, retry.
**`--runtime` value is `container`, not `docker`.** Cloud needs `HCLOUD_TOKEN`, `AETHER_SSH_KEY`
(`~/.ssh/aether_test`), `PG_URL` (+ now `PG_USER/PASSWORD/HOST/DB`). PG VM via
`tools/provision-test-pg.sh --fresh`; firewall init once via `tools/pg-firewall.sh init`.

### Gate: artifact-push 403 (`118393962`)
00-smoke `Push_artifacts` → `Artifact publication requires OPERATOR or ADMIN`. `#282` gates
artifact push behind OPERATOR/ADMIN; only bypass is `AETHER_INSECURE_DEV_MODE=true` on the node.
docker-compose sets it for every node; cloud path didn't. Fix: `run-tests.sh` cloud block exports
it; `NodeUserDataRenderer.emitIdentityEnv` propagates it into each node. **Not a regression — a
pre-existing cloud-env gap.**

### 08-resources @PgSql on cloud — THREE layers
1. **datasource name/shape** (`48c5053ed`): the `@PgSql` slice resolves a **flat `[database]`**
   section (`@ResourceQualifier config="database"`). `databases.X` produces **nested
   `[database.X]`** which the connector ignores. Fix: inject flat `[database]` via
   `[source.hetzner-eu.node_config.database]`.
2. **THE override bug** (`f6707221e`, + regression test `121929996`): even with the value present,
   the runtime `@Query` used the slice's LOCAL default (`postgresql://forge-postgres:5432/forge`).
   `SliceStore.assembleSliceComposite` built `LayeredConfigProvider.layered([intrinsic, composite])`
   but `LayeredConfigProvider` is **first-wins** ("index 0 = top priority"), so the slice's
   intrinsic **won over** the deployment override — the exact opposite of the documented design
   (`logShadowedKeys`: "intrinsic shadowed by operator override"). **Swapped to `[composite,
   intrinsic]`.** Docker never caught it because its node `aether.toml [database]` is *identical* to
   the slice's; cloud is the first env where they differ. Verified: migration ran (table created),
   error moved from "name resolution" → next layer.
3. **credentials** (`e0b91dc01`): override now wins per-key, but only `async_url` was set, so the
   slice's `username/password = forge` still won → `28P01 password authentication failed for user
   "forge"`. Fix: `node_config.database` overrides `username`/`password` via `${env:PG_USER}` /
   `${env:PG_PASSWORD}`; `provision-test-pg.sh` now emits those component vars. **→ 08-resources 5p/0f.**

### 11-observability cold-start backfill deadlock (`13e9eb9f6`) — PARTIAL
`/api/events` returned `[]` on all non-writer nodes (`0 0 0 0 3`). Root cause (verified in code):
`system:cluster-events` partition owner starts **SYNCING**; `PartitionBackfill.handleNoSource` is
non-blocking (returns NO_SOURCE while `waited < 20s`); `attemptColdStartPromotion` (the
self-promotion deadlock-breaker) is reachable **only after 20s**, but `backfill` is fired **once**
per `onBecameReplica` reconcile edge with **no periodic retry** → never re-invoked → every replica
stays SYNCING → reads fall back to empty local partition.

**Fix A+B** (jbct-coder, 454 aether-stream tests pass incl. 2 new):
- **B** — HRW owner (`ReplicaPlacement.rank` index 0) **self-promotes to CAUGHT_UP immediately** at
  cold-start (authoritative, no peer source needed). New `membersSupplier` on PartitionBackfill;
  backward-compat factory defaults `List::of` (byte-identical).
- **A** — 5s `SharedScheduler.scheduleAtFixedRate` re-drive (on the existing `streamBackfillExecutor`)
  re-invokes backfill for non-CAUGHT_UP replicas (new `ReplicaRegistry.incompletePartitionsFor`).

**Result: the fix WORKS for replica convergence** — distribution moved `0 0 0 0 3` → `3 0 0 0 3`
(the **full RF=2 replica set now replicates**: owner promoted, replica backfilled). **But 11-obs
still fails** because it needs **all 5** nodes to return the markers, and the **3 non-replica nodes
still return `[]`** — see open item #1.

---

## 3. Current cloud state (container)

| Suite | Result |
|---|---|
| 00,04,06,07,08,09,10,14,15 | ✅ green |
| 11-observability | ❌ 5p/1f — non-replica forward-read layer (open) |
| Cluster B (02,03,05,12,13) | not run/fixed — harness-cloud gaps |

Two transient notes: one bootstrap got **3/5 nodes** (`COLD_START_NOT_FULL`, stayed RECONCILING) —
transient Hetzner provisioning hiccup; retry got 5/5. **`--skip-deploy` breaks cloud addressing**
(falls back to `localhost:5151`) — re-bootstrap instead of reusing a live cloud cluster.

---

## 4. Open items (priority order)

1. **11-obs forward-read layer (next, likely a 3rd product fix).** Non-replica nodes (3/5) return
   `[]` from `/api/events`; they should forward-read to a CAUGHT_UP replica via `StreamForwardClient`
   but **node 1's log shows no forward attempt at all**. Top hypothesis: **cross-node
   `ReplicaRegistry` visibility** — the owner's `updateWatermark(CAUGHT_UP)` may be node-local, so
   non-replicas never learn a source exists and silently fall through to empty local reads.
   Alternatives: forward-read transport on cloud, or `cluster-events` RF < cluster size **by design**
   (then forward-read MUST work). **Open design question for the owner: is `system:cluster-events`
   meant to replicate to all core nodes, or small-RF + forward-read serving the rest?** That answer
   collapses the fix. Code: `AetherNode.java ~2455-2560` (consumer wiring), `PartitionedStreamAccess.
   selectReplicaAndRead`, `StreamForwardClient`, `ReplicaRegistry`.
2. **Cluster B (5 suites).** `kill_node` cannot resolve CTM-replacement **ULID** nodes on cloud —
   `cloud_public_ip` Hetzner fallback looks up `aether-node-id=<target>`; need to confirm the label
   value vs the membership NodeId on a **live** replacement (`HetznerComputeProvider.labelsFor` sets
   `aether-node-id = ctx.resolveNodeId()`). Also `restore_cluster_baseline` uses docker-compose
   semantics ("compose cycle") that don't exist on cloud. Both are harness-cloud-completeness gaps.
3. **JVM cloud runtime (#9 / deferred #53).** Never validated on cloud. JVM TOMLs already carry the
   `node_config.database` fix + are pinned to the `v1.0.0-rc2-candidate` jar.
4. **#59** — B4 abort-path runtime proof (was LNP-blocked from this Mac; LNP now resolved).

---

## 5. Environment / artifacts

- **Cloud: TORN DOWN at session end** (cluster A 5 nodes nbg1 + PG VM fsn1). Re-provision PG with
  `tools/provision-test-pg.sh --fresh` (emits PG_URL + components to `/tmp/aether-test-pg.env`),
  then `tools/pg-firewall.sh init`.
- **Candidate refresh cycle:** edit code → commit → `git tag -f v1.0.0-rc2-candidate HEAD` →
  `git push -f origin refs/tags/v1.0.0-rc2-candidate` → `release.yml` rebuilds ghcr image + release
  jar (~6 min) → re-bootstrap cloud pulls fresh image. **Config-only (TOML/harness) changes do NOT
  need the image rebuild** — re-bootstrap reads the TOMLs locally.
- `~/.claude` agents switched **Fable→Opus** (Fable 5 unavailable); memory updated. Backups in
  `~/.claude/agents/.bak-fable-to-opus/`.

---

## 6. Key lessons / gotchas

- **The override bug is the template:** docker masking a cloud bug because docker's node config ==
  the slice's local defaults. The cloud env is the first to make slice-local vs deployment values
  *differ* — that's why these are surfacing now, late, and they're real.
- **Two real RC2 product bugs found via the cloud environment**, both byte-invisible on docker:
  the override-precedence inversion and the stream cold-start deadlock. The sweep is paying off as a
  product-quality probe, not just a harness exercise.
- **Verify "fixed" empirically per node** — querying each node's `/api/events` (`0 0 0 0 3` →
  `3 0 0 0 3`) is what revealed the A+B fix worked AND that a second layer remained. Suite pass/fail
  alone would have hidden both.
- **`--runtime container`** (not `docker`); **`--skip-deploy` is unusable for cloud re-runs**.
