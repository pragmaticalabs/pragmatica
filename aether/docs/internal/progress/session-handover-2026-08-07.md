# Session handover — 2026-08-07

**Branch:** `release-1.0.0-rc3` · **HEAD:** `a6793e6c6` · **pushed** (branch == origin) · working tree clean
**Candidate tag:** `v1.0.0-rc3-candidate` → `9b88911cd`, **~45 commits stale**. Owner ruling: re-point when the arc lands — **it has**; the tag re-point rides the Hetzner run below.

---

## §1 START HERE — the RFC-0017 arc is CODE-COMPLETE; next is the arc-final live Hetzner run

All seven stages of `docs/rfc/RFC-0017-cluster-owned-provisioning.md` are implemented, unit-verified, and pushed. The RFC's migration section carries per-stage implementation notes (read them — several correct the original design claims). What remains is ONE live Hetzner validation run covering the five deferred checks, then the candidate-tag re-point.

### The five deferred live checks (§1b history below; run in this order)

1. **Cores-only bootstrap end-to-end** (stage 7 — the headline): `aether cluster bootstrap` with a `[source.X.core]` + `[source.X.worker]` config → cores self-assemble via discovery (stage 4), formation observed via `aether-formed` labels (C4), workers appear WITHOUT bootstrap creating them (stage 5), booted with live core peers.
2. **Typed scale live** (stage 2): `aether cluster scale --role worker --count N` up and down; scale-to-zero.
3. **Fence race** (stage 3): concurrent scale + auto-heal — both edits survive or a loud 409/typed failure; never a silent lost update.
4. **Worker scale-down newest-first** (stage 5): cluster-provisioned `-r` workers reaped before bootstrap-era ones (post-arc there are no bootstrap workers — verify id ordering among minted ones).
5. **Destroy with unrecorded VMs** (stage 6): destroy reaps cluster-provisioned workers via the label sweep; account CLEAN after; `test-pg` untouched (it is now PROTECTED in the CLI too).

### Before ANY cloud run (standing discipline, #572)

- `tools/provision-test-pg.sh --print-only` — supplies PG_* AND confirms the VM (still unprovisioned since 2026-08-03 unless someone restored it).
- Grep every harness teardown you invoke for destructive calls: `grep -n "reaper\|--destroy\|--force\|terminate\|delete" <script>`.
- Scoped-reap cleanup ONLY; hard 2h cap; watch the run, never fire-and-forget.

---

## §1a What stage 2 delivered (commits `03b1e0c90`, `6d25a390f`, `a1a72228a`)

- `ClusterConfigValue` stores **per-(source, role) desired topology** (`AetherValue.TopologyEntry`). `coreCount()` is **derived, not stored** — the drift where a scale rewrote `coreCount` while `tomlContent` kept the old number is structurally impossible now, not merely fixed.
- `ClusterTopologyManager.setDesiredCount(sourceName, role, count)` **replaces** `setDesiredSize(int)`.
- `ScaleRequest(source, role, count, expectedVersion)` **replaces** `ScaleRequest(coreCount, expectedVersion)`; `aether cluster scale --source/--role/--count` replaces the positionals and `--core`.
- Source **inference** when exactly one source declares the role; **refusal naming the candidates** when several do; **refusal of an undeclared `(source, role)`** so a typo cannot become a provisioning target (`withDesiredCount` appends, which is right for composing a topology and wrong for a scale).
- Quorum arithmetic is core-only and evaluated against the resulting **cluster-wide** total. `withCoreTotal` is gone, replaced by `sourcesWithRole` + `declares`.
- `GET /api/cluster/config` serves `desiredTopology`; dashboard **DESIRED TOPOLOGY** panel renders it and flags when a scale will need an explicit `--source`.

**Evidence:** `./build.sh` green (49 lint findings, all baseline, 0 new); 832 node + 642 cli + 730 slice + 323 aether-config tests, 0 failures. `ScaleRequestContractTest` 3/3, `ClusterConfigRoutesScaleTest` 11/11, `ClusterScaleCommandTest` 3/3, `ClusterConfigKVTest$DesiredTopology` 8/8. **No scale has been executed against a live cluster** — the command was non-functional before this, so there is no prior live behaviour either.

## §2 Build traps — read before trusting a green

1. **`mvn install` runs failsafe and can provision a REAL PAID Hetzner server.** `install` comes *after* `verify` in the lifecycle, `aether/pom.xml:603` binds `maven-failsafe-plugin` in its **active** plugins block (`integration-test` + `verify`, `**/*IT.java`), and `HetznerCloudIT:38-39` gates only on `HCLOUD_TOKEN` being non-blank — no profile check. `aether/node` and `aether/cli` both depend on `environment-hetzner`, so `-am` pulls it in. The CLAUDE.md warning naming only `mvn verify` is **incomplete**. `./build.sh` is safe because it passes `-DskipTests`, which suppresses failsafe too. Safe hand-rolled spelling: **`-DskipITs`**. A build-runner agent caught this by refusing my instruction — I had asserted install was the safe one.
2. **Do not hand-roll `mvn ... -am`; run `./build.sh`.** `-am` force-rebuilds `integrations/cluster`, `integrations/dht`, `integrations/swim` from source while skipping the jbct bootstrap that `build.sh` does first. Result is a **false red**: `@Codec` processor errors plus `ClassCastException: String cannot be cast to TypeMirror` in `swim`, in modules not in the diff (they last changed 2026-07-17). Cost most of a cycle. For module tests after `build.sh`: `mvn test -pl <modules>` with **no** `-am` (`-am` also drags in Docker-dependent `sql-splitter`).
3. **`build.sh` reformats Java in place** as part of its lint gate. After a run, **re-read a file before editing it** — an `Edit` prepared beforehand will fail to match.
4. **`build.sh` does not run tests.** Compiling a module is not testing it.
5. **A `@Nested` test class shows `Tests run: 0` in the OUTER surefire report.** That is normal, not a vacuous pass — check `Outer$Nested.txt`. Do not raise a false alarm on it, and do not accept a genuine 0 either.

Carried from earlier: `FirewallPresetsTest` used to assert `rulesFor_standard_allRulesUseAnyCidr` — that **every** rule of the default preset, management API included, uses `0.0.0.0/0`. The exposure was encoded as the requirement, so no failing test could ever have surfaced it. When a security fix meets a test that "passes", check which behaviour the test pins.


---

## §3 What landed this session

`./build.sh` green (0 errors, lint 0 new) at every step; ~2986 tests green across slice/deployment/node/cli.

| commit | what |
|---|---|
| `69f8acaa0` … `e4a8d95f9` | **#574 ingress firewalls**, end-to-end (9 commits) |
| `51aa98231` | destroy treats an already-terminated VM as destroyed |
| `20e50f352` | readiness gate names the management port; warns when ingress omits it |
| `ff985e589` | docs — management-port trap |
| `2d0bbc65b` | **RFC-0017** |
| `0a070211f` | **#579** refuse to provision a VM whose cluster cannot be identified |
| `6d4f2b6c1` | **#580** cluster init no longer opens the management API to the internet |
| `f450014a7` | **#580** PF-24 |
| `f777ef0fb` / `1543bf2fa` | handover + its correction |
| `86aa40885` | **typed per-source/per-role topology in cluster state** |
| `761e1316e` | **CTM scales one (source, role)** |
| `5ff9026cc` | handover |
| `03b1e0c90` / `6d25a390f` / `a1a72228a` | **stage 2 quad** — scale one (source, role): retyped `ScaleRequest`, inference + refusals, cluster-wide quorum, dashboard DESIRED TOPOLOGY, docs |
| `257c683ca` / `2b32f046c` / `5687f807e` | **stage 3 (#570 CLOSED)** — RFC-0018 `VersionFenced` successor fence in the KV applier (mutation-checked); writers confirm the fenced put landed (CTM bounded retry, REST 409) |
| `5b78113c5` / `0feeb74cb` / `47abedf0a` | **stage 4** — discovery self-assembly arm in `Main`, `[cluster] nodes` in overlay, worker seeds baked at create, C4 `aether-formed` label readiness, RFC gap table corrected |
| `2537446bc` / `906adced5` / `f1ae06f86` | **stage 5** — CTM worker-topology reconcile (deficit/surplus vs label inventory), role-aware `provisionReplacement` spec, applier routes all roles, scale-to-zero end-to-end |
| `2f5e9fe76` / `2137a0c11` | **stage 6** — destroy sweeps cluster-labelled VMs (scoped by construction), `PROTECTED_CLUSTERS` in the CLI, polite scale-to-zero phase dropped as redundant (recorded in RFC) |
| `c657b4841` / `a6793e6c6` | **stage 7** — cloud bootstrap seeds cores only (`CLOUD_BOOTSTRAP_ROLES=[CORE]`), stage-4 worker-seed baking removed as dead, `--wait` = core quorum formed |

**#574 is live-verified on Hetzner** (3 runs, 9 VMs, account CLEAN after each; `test-pg` never touched). Against the real API: one labelled firewall per source; `tcp+udp` → two rules; union-not-replace; no 8090/8080; attached AT server-create (three independent proofs); idempotent re-run issued zero writes; **enforcement proven** — port 22 timed out at 6.0s while allowed 8070 refused in 0.06s; destroy deleted it (404). The CHANGELOG evidence tag was upgraded to match — it still said "not yet exercised on a live Hetzner cluster".

### Docs drift found while aligning the scale surface

This one command had **five** spellings across the repo, none matching the implementation:

| where | said | status |
|---|---|---|
| `cluster-management-spec.md` | `--core N`, and `{"core_count", "expected_version"}` snake_case in the REST flow | fixed |
| `cluster-bootstrap-spec.md` REQ-10.2.1 | `<source> <role> --count N` (positional) | fixed, + new REQ-10.2.1a/b for inference and cluster-wide quorum |
| `operators/runbooks/scaling.md` | **`--target 7`** — never existed in ANY version | fixed |
| `reference/cli.md` | `--core N` + fabricated example output | fixed |
| `specs/fluid-migration-spec.md` | `--provider gcp --core 5` | **left as-is** — forward-looking design doc, `--provider` does not exist at all |

Worth noting the pattern: the operator-facing runbook was the *most* wrong of the five.


---

## §4 Issues

Filed: **#579** (label precondition — fixed), **#580** (preset exposure — fixed), **#581** (RFC-0017 epic), **#582** (codec tag collisions).

Open and load-bearing for RFC-0017: **#570** (unguarded read-modify-write — see §1), **#578** (`ClusterConfigApplier` no-ops 8/10 `DiffAction`s — still why firewall *edits* are discarded).

### NOT YET FILED — the CLI/server wire-contract gap (structural)

`aether cluster scale` was **broken in every version**: the CLI posted `{"count":…,"role":…,"source":…}` while `ManagementApiResponses.ScaleRequest` read a lone `coreCount`. Executed behaviour (measured, not inferred): the mapper **rejects** the body with `Type mismatch: expected int, got unknown … ["count"]` — it trips on the absent required field, never reaching the quorum check.

**Root cause is structural, and the scale fix did not eliminate it.** Request/response DTOs live in `aether/node`; `aether/cli` does not depend on that module. So every CLI request body is a hand-built JSON string with no compile-time tie to the server record — **18 CLI files do this**. The only `CLUSTER_SCALE` tests assert routing target, which stays green under any field-name drift.

Mitigation landed: `ScaleRequestContractTest` (node) + `ClusterScaleCommandTest` (cli) pin both spellings to the same field names, so a unilateral rename goes red. That is a convention, not a guarantee. The real fix is moving request DTOs into a module both sides depend on (`aether-management-api` already qualifies) and having the CLI serialize records. **Worth an issue; deliberately not folded into the scale change.**


### #582 — codec tag collisions (do NOT fold into RFC-0017)

Hit while adding a serializable nested record: it hashed onto an existing tag and every codec test in `aether/node` died at once.

- `deterministicTag(name) = (hash & 0x7FFFFFFF) % 16256 + 128`. With 469 registered types, expected colliding pairs ≈ **6.8**, P(≥1) ≈ **99.9%**. **Changing the hash algorithm cannot fix this** — it is the birthday bound, not hash quality.
- **Cross-slice collisions are structurally impossible**: `sliceCodec(parent, codecs)` *copies* the parent tag array (`SliceCodec:182`), generated slices add only their own types, and `SliceLoadingContext:132` binds one registry per slice.
- **Real exposure is slice-vs-framework**: ~25% for a 10-type slice, ~44% for 20, growing with every framework type added; the only remedy for the slice author is renaming their own class.
- Proposal recorded on the issue: split the existing derived band — framework `128…4095` explicitly assigned, slice `4096…16383` derived — extending `@CodecFor` rather than adding an annotation.
- **Open question before designing:** name-derived tags are stable under type reordering but change on rename; sequential assignment is the opposite. Matters only if codec-encoded payloads outlive a rebuild — **whether stream WAL carries codec-encoded payloads is UNCONFIRMED** and decides the answer.

---

## §5 Corrections I made (all committed/posted)

Claims of mine that turned out wrong; each was caught by checking rather than by a test:

- "Nothing publishes the topology spec today" — **wrong**, `BootstrapPhaseFormation:247,362` publishes the full `tomlContent`. Came from grepping one file for a few keywords. Corrected in `1543bf2fa`; it made stage 2 smaller.
- "The firewall presets blocked consensus" — **wrong**, `ClusterConfigGenerator` wrote `[operations.ports]` from the same constants, so wizard configs were self-consistent at 7100/7200. The real defect was that the wizard disagreed with the documented defaults. Corrected on #580.
- "Explicit codec tags would shrink the wire" — **wrong**, only tags below 128 are one varint byte and those slots are structural-tag headroom. Corrected on #582.
- "`mvn install` is the safe one; `verify` is what provisions servers" — **wrong**, see §2.1. A subagent refused the instruction and was right.
- "The CLI shape is unresolved and needs an owner call before anything can be wired" — half wrong. The CLI *already had* `<source> <role>` positionals; what was missing was that the server never received them. The open question was narrower than the previous handover implied.

Also corrected in the docs rather than inherited: the `aether cluster scale` example output in `cli.md` (`Core nodes: 5 -> 7`, `Config version: 8`) was **fabricated** — `OutputFormatter.printAction` prints only `Scale successful.` in TABLE format.


---

## §6 Decisions recorded (owner)

- **REQ-5.1.8.3 stands as written.** Aether never opens cluster/management ports on its own initiative; an explicit `allow_ingress` rule is an operator decision applied like any other.
- **Cores hold cloud credentials for every source they provision into.** No alternative survives multi-source clusters. Vault may mitigate — `SecretsProvider` seam exists (Aws/Gcp/Azure/File/Env/Composite/Caching; **no Vault, no Hetzner**). Limit: authenticating *to* a vault needs instance identity, which **Hetzner lacks**. Cheapest real mitigation, no code: **one cloud project per cluster**.
- **Teardown: simple label sweep first**, scoped to `aether-cluster=<name>`, never bare, reusing `PROTECTED_CLUSTERS`. #579 is what makes it sound.
- **Cluster label is a hard precondition** — done.
- **Full RFC-0017 arc on rc3**, replacing rather than additive.
- **Candidate tag stays put** until the arc lands.

---

## §7 Standing hazards

- **`test-pg` is still unprovisioned** since the 2026-08-03 incident. Not needed for firewall work. Before ANY cloud run: `tools/provision-test-pg.sh --print-only`, and grep the harness teardown for destructive calls.
- **#250 storage GC — DO NOT WIRE.** Node-local refcount view deletes from the shared DHT tier.
- 11 stale worktrees under `.claude/worktrees/` pollute every repo-wide grep.
