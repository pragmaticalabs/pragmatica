# Session handover — 2026-08-05

**Branch:** `release-1.0.0-rc3` · **HEAD:** `178daca05` · **Candidate tag:** `v1.0.0-rc3-candidate` → `9b88911cd` (STALE by 2 commits) · working tree clean, everything pushed.

---

## §1 START HERE — firewall end-to-end (owner ruling: must work in GA)

Everything below is verified against code. This is specified enough to begin implementing immediately.

### Why this is first

`aether/docs/specs/cluster-bootstrap-spec.md` §6.2:

> **Hetzner Cloud**: servers created without an explicit firewall association **accept all inbound traffic**.
> **AWS / GCP / Azure** (v1 stubs): default security groups typically deny inbound traffic.

So on the ONE provider actually run and tested, the missing firewall is **fail-OPEN**. On the others it is fail-closed (unreachable, not exposed) and the docs already direct operators to their own security groups. That asymmetry sets the scope.

Meanwhile `[source.X.firewall.allow_ingress]` is fully **inert**: parsed (`ClusterBootstrapConfigParser.java:389-424`), validated (`ClusterBootstrapConfigValidator.java:304`), diffed (`ClusterBootstrapConfigDiff.java:138`), and **scaffolded into user configs by `aether cluster init`** (`ClusterConfigGenerator.java:166`) — with zero consumers on any provisioning path. Every layer the operator touches confirms it works.

Traps T1/T2/T3 all checked clear: no generated call site (`jbct/` has no literal), no provider decorator (only the 5 `*CloudProvider` records), no late binding.

### The key design insight — do NOT build a parallel mechanism

`firewall_ids` is **not a competing mechanism**. It is the *attach* half of this same feature, and it works:

```
firewall_ids  →  HetznerEnvironmentIntegrationFactory.java:75
              →  HetznerEnvironmentConfig.firewallIds()
              →  HetznerComputeProvider.java:334        (passed at server-CREATE)
```

The missing half is **create**: turn `allow_ingress` into a Hetzner firewall, obtain its id, and feed it into the existing `firewallIds` path.

This also removes an ordering hazard for free. Calling `openIngress` *after* server create leaves a window where the node is up and unfirewalled (and per §6.2 that means fully open on Hetzner). Create-firewall-then-attach-at-create has no such window.

Spec REQ-5.1.8.4 names `openIngress`/`closeIngress` as the mechanism; the create-then-attach shape satisfies the requirement's intent while closing that window. If you keep `openIngress` as the SPI entry point, have it create/patch the standalone firewall and return its id for the create call — do not implement it as a post-create mutation.

### Scope

1. **Hetzner — implement.** The client calls already exist and have **zero callers**: `HetznerClient.java:86-88` (`applyFirewall`, `listFirewalls`).
   - `allow_ingress` → standalone firewall associated with the source's servers (§6.2 wording)
   - `"tcp+udp"` expands to two provider-level rules (REQ-5.1.8.1)
   - rules not listed are not touched (REQ-5.1.8.1)
   - record as `CreatedResource.FirewallRule` so destroy removes it — **the cleanup arm already exists and is dead** because nothing ever creates one: `BootstrapCleanup.java:322,472`; the only construction today is JSON revival at `BootstrapStateJson.java:192`
2. **REQ-5.1.8.2** — `load_balancer = "elected"` with no `[source.X.firewall]` block: auto-create tcp+udp on `app_http` (0.0.0.0/0) and emit the warning the spec dictates verbatim.
3. **REQ-5.1.8.3** — cluster (8090) and management (8080) ports stay operator-managed. Do NOT open them.
4. **AWS / GCP / Azure** — reject `allow_ingress` at pre-flight with a clear message. Precedent to mirror: PF-16's spot rejection (`ClusterBootstrapConfigValidator.java:268-282`), which is loud and per-provider. Revisit under #463.
5. **#578 is a hard dependency for CHANGES.** `ClusterConfigApplier` no-ops 8/10 `DiffAction` variants, so without it only initial bootstrap honours firewall config; later edits are logged "Applied config action" and discarded. Bootstrap-only firewall is still worth landing, but say so explicitly rather than implying edits work.
6. **Verify PF-18** — `ClusterBootstrapConfigValidator.java:304` appears to already validate port/protocol/CIDR. Confirm it matches the spec's PF-18 before adding anything.

### Risk — treat cleanup as the primary test target, not an afterthought

This creates and deletes REAL cloud firewalls. It must only ever delete firewalls Aether created; `CreatedResource` tracking exists for exactly this.

This is not hypothetical. On 2026-08-03 an unscoped reap deleted the standing `test-pg` VM and its firewall (§5). Over-reaching cleanup is a demonstrated failure mode in this area.

Also: never touch `aether-cluster=test-pg`. `tools/cloud-reaper.sh` now protects it by default (`14d1da8e3`), but the new code must not acquire its own delete path that bypasses that.

### Verification

Needs a live Hetzner run — which is also the natural moment to re-provision `test-pg` (§5). Before any cloud run: `tools/provision-test-pg.sh --print-only` to supply `PG_*` and confirm VM state.

---

## §2 What landed this session

All pushed. `build.sh` green at each step; each fix mutation-checked where a test could be inverted.

| commit | issue | note |
|---|---|---|
| `924a0d0e4` | #568 | committed stream ownership filtered by holder liveness |
| `3c4445075` | **#567** | backfill append floor = local ring tail. **CLOSED**, live-verified twice |
| `c7b06bfaf` | #565 | self-drain uses owner-gate-bypassing emit |
| `ce8924b0e` | #569 | deployment client errors carry their own HTTP status |
| `94181ccef` | #566 | `aether_schema_owner` claim table |
| `22c8323b3` | #509 | probe tests — defect does NOT reproduce |
| `9b88911cd` | #566 | harness aborts on refused publish |
| `14d1da8e3` | #572 | cloud reaper protects shared infra by default |
| `178daca05` | **#573** | management API denies instead of granting ADMIN |

**Integration:** `02-chaos` 41/41 twice (independent builds); `03-scaling` 17/17, scale-down 7→5 under load at 0.00% error, terminal convergence 0s.

**Not reproducing (closed out, do not re-implement):** #509 and the 03-scaling convergence budget. Mechanism recorded on #509 — `MembershipFsm.seed` counts configured cores so a restarted leader sees no deficit, and #557 deliberately left heal-deficit on `coreCountedMembers`. Forge's cumulative-slowdown 3rd-formation environment was NOT reproduced; if it still triggers there, the mechanism is wrong somewhere.

---

## §3 GA readiness sweep — present-but-inert surfaces

Motivation: deciding whether GA can ship with features labelled "experimental". Owner position: label rather than cut, **and labels must PREVENT use**, not just document. That criterion is only satisfiable in code — a doc label cannot reach a user whose call the API accepted.

**Four of the issues below describe surfaces NO ticket mentioned.** The 39/64/14 milestone split was not a map of the gap.

### Root causes — three fixes cover most of Bucket A

| root cause | swallows | disposition |
|---|---|---|
| **#578** `ClusterConfigApplier.applySingle` default arm no-ops 8/10 `DiffAction`s **and logs "Applied config action"** (`:65-71`, `:107-111`) | auto_heal enabled, firewall rule changes, any diffed field | REFUSE loudly first (small), then implement per variant |
| **#576** `StreamConfigParser.parseConsumers` (`:146`) has zero callers; prod uses 1-arg `consumerConfig(groupId)` at `StreamConsumerManager.java:443` | read-preference, dead-letter, batch-size, checkpoint-interval | WIRE |
| **one call site** — `AetherNode.java:3013` uses the 2-arg `storageSegmentSink` | encryption-key-id, compression | WIRE — both fully implemented AND unit-tested (`SegmentCompressionEncryptionTest`); 5-arg overload exists at `StorageSegmentSink.java:46` |

### Individually decided

| item | verdict | action |
|---|---|---|
| #573 mgmt auth | fixed, mutation-proven | **DONE** |
| #574 `allow_ingress` | INERT, fail-open on Hetzner | **IMPLEMENT** — §1 |
| `auto-offset-reset` | INERT — no seek exists; `ConsumerRuntimeState.java:108` seeds every subscription at literal `0L`; parser default is `latest`, so **no value produces `latest` semantics** | REFUSE until a seek exists |
| `HealthSignalSink` (#571) | DEAD — `.set(` count 0 repo-wide; `HealthSignal` has **zero production consumers** | REMOVE bus + producer emits |
| `StreamConsumerAdapter` (#577) | DEAD — real path builds its own lambda at `StreamConsumerManager.java:474` | REMOVE + fix `feature-catalog.md:190` which lists it Complete |
| `[endpoints.<name>]` (#577) | DEAD — `AetherConfig.endpoints()` accessor never read | REMOVE or document as unsupported |
| #250 storage demotion/GC | **DATA-DESTRUCTIVE if wired** | **DO NOT WIRE** — see below |
| #298 `checkQuota` | INERT — all 5 providers return `sufficient=true`, zero call sites, no cost cap anywhere | implement or document plainly |
| AWS spot (#306) | **REAL** — ticket mis-locates it | no action; ticket needs correcting |

### #250 — three revisions, final position

Do not repeat the earlier reasoning; both prior framings were wrong.

- **Durability is NOT the blocker.** `SnapshotManager` wraps the in-memory `MetadataStore` and persists lifecycles+refs to disk, restored at boot (`StorageFactory.java:170,173`; `AetherNode.java:2994`).
- **Per-node metadata is fine for DEMOTION.** `StorageInstance.waterfallReadFromTier` (`:271-284`) probes every tier and never consults metadata; last tier is the cluster-wide DHT.
- **The GC is the hazard.** `DefaultStorageGarbageCollector.collectGarbage` (`:54-72`) filters the **node-local** lifecycle index, then `StorageInstance.deleteFromAllTiers` (`:342-350`) deletes from **every** tier — and the stream tier list includes the shared DHT tier (`StorageFactory.java:158`). Node A's local refcount view deletes blocks node B still references.

So the naive "swap `noOp()` for the real adapter" at `AetherNode.java:2244` is precisely the change that enables the destructive path. If wiring later: demotion only, or make the GC refuse shared tiers.

Adjacent, untracked: `StorageInstance.resolveRef` (`:133-135`) is per-node, so cross-node `ContentStore.get(name)` fails at name→BlockId regardless of tiering. Demotion is leader-pinned (`DemotionManager.java:11-14`) but operates on node-local tiers.

---

## §4 METHOD — read before continuing the audit

**Three verdicts in this sweep were wrong because references were COUNTED instead of behaviour TRACED.** Two of the three were mine, asserted as verified.

| surface | wrong verdict | truth |
|---|---|---|
| `@PartitionKey` | "dangling, zero call sites" | wired — `FactoryClassGenerator.java:890,1658` EMIT `.withKeyExtractor(...)`; read at `StreamPublisherFactory.java:98`, `StreamAccessFactory.java:183`. Memory note corrected |
| `MetadataStore` | "in-memory, therefore not durable" | durability is in a WRAPPER (`SnapshotManager`) |
| `@Sql` | "capability gap — named datasources unreachable" | **docs bug only**; reachable via `@ResourceQualifier(config = "database.orders_db")`, proven at `SliceProcessorTest.java:1075,1750,1824` and live blueprints |

**Mandatory before declaring anything inert — rule out all three:**
- **T1 GENERATED CODE** — grep `jbct/slice-processor` generators for the symbol as a STRING LITERAL; check `target/generated-sources`. The generator's emitted string IS the call site.
- **T2 WRAPPER/DECORATOR** — behaviour may be supplied by a wrapper, not an alternative implementation.
- **T3 LATE BINDING** — an `AtomicReference` seeded with a no-op then `.set(...)` elsewhere. `DeparturePushObserver` (`AetherNode.java:1977`) IS correctly rebound at `:2114`; `healthSinkRef` (`:1746`) is NOT. Both idioms exist — establish which.

**Validate the method with a control.** The strongest pass took sibling fields as a control: `partitions`=34 consumers, `replicas`=12, `consistencyMode`=7, versus `compression`=0, `autoOffsetReset`=0, `encryptionKeyId`=0. That proves the search is not systematically blind. Do this whenever a batch of "zero consumers" verdicts is produced.

**Also: the obvious assertion is often vacuous.** #566's ordering test needed "a refused claim does not even READ the history table" — a row-count assertion passes against the mutation. #569's cause-level assertion passed the whole time the wire answered 500. Mutation-check, and check that the mutation actually turns something red.

---

## §5 test-pg incident — VM still deleted

On 2026-08-03 a cloud run that failed during bootstrap (unresolvable `${env:PG_*}` — `/tmp/aether-test-pg.env` ages out) reached `run-tests.sh`'s teardown, which ran a **bare** `cloud-reaper.sh --destroy --force`, deleting `aether-test-pg-038708` and `aether-pg-firewall`. Filed #572.

**Fixed in `14d1da8e3`** — two independent guards, either alone sufficient:
- `cloud-reaper.sh` protects `PROTECTED_CLUSTERS=("test-pg")` by default, after all selector modes converge; `--exclude-cluster` extends, `--allow-protected` is the escape hatch; the banner always states which is in force
- `run-tests.sh` gates the bare safety-net on `CLOUD_RESOURCES_PROVISIONED` (set only after a bootstrap call returns) instead of on suites-selected

**STILL OPEN: `test-pg` has NOT been re-provisioned.** `tools/provision-test-pg.sh` is idempotent and re-emits the env. It creates a paid VM — needs an explicit decision. Do it as part of §1's verification run.

**Standing rule:** before ANY cloud run, `tools/provision-test-pg.sh --print-only` (supplies `PG_*` AND confirms VM state), and grep the harness's teardown for destructive calls. A dry-run inventory shows what you can LOSE, not that you are safe.

---

## §6 Issues filed this session

| # | title | status |
|---|---|---|
| 572 | cloud teardown deleted test-pg — bare reap gated on suites-selected | fixed |
| 573 | management API grants ADMIN when `[app-http] enabled` false | **fixed** |
| 574 | `firewall.allow_ingress` parsed/validated/diffed/scaffolded, never applied | **→ §1** |
| 575 | `auto_heal enabled` silently ignored | open (subsumed by #578) |
| 576 | `parseConsumers` zero callers → 4+ stream keys discarded | open |
| 577 | dangling surfaces: `@Sql` (downgraded to docs bug), `StreamConsumerAdapter` dead | open |
| 578 | `ClusterConfigApplier` no-ops 8/10 DiffActions and logs "Applied" | open — **root cause** |

Filed independently by other agents: **#570** (`setDesiredSize` unguarded read-modify-write loses concurrent scale requests), **#571** (`HealthSignalSink` never installed).

Corrections posted: #250 (×2), #509, #566, #567, #577.

---

## §7 Remaining work, in order

1. **§1 firewall end-to-end** — owner ruling, GA-required
2. **#578** — loud-refusal change first; makes #574/#575 fixes meaningful
3. Continue Bucket A per §3: WIRE encryption+compression (one call site) and consumer config (`parseConsumers`); REFUSE `auto-offset-reset`; REMOVE the three dead surfaces
4. `06-deployment` fixture collision — `test-persistence` and `url-shortener` both claim the node's single `[database]`, so the suite stays red. Options on #566; cheapest is dropping migrations from `url-shortener` (it is the deployment-STRATEGY fixture; schema is not what blue-green/canary/rolling exercise)
5. Re-provision `test-pg` (§5)
6. rc4 batch: #517 #519 #524 #543 #545 #547
7. Candidate tag is 2 commits stale — re-point once per batch, never twice (races Release asset uploads)

**GA framing to carry forward:** the honest package is one loud-refusal change, two small wirings, three deletions, some doc corrections — plus §1. Bucket A was ~12 symptoms of ~5 causes. That is closer to "we can ship a complete v1" than the raw issue count suggests, PROVIDED the remaining ▲ rows get traced rather than counted.
