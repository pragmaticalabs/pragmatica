# Session handover — 2026-08-07

**Branch:** `release-1.0.0-rc3` · **HEAD:** `761e1316e` · **pushed** (branch == origin) · working tree clean
**Candidate tag:** `v1.0.0-rc3-candidate` → `9b88911cd`, **22 commits stale**. Owner ruling: leave it until the RFC-0017 arc lands, which is also when the Hetzner run happens.

---

## §1 START HERE — RFC-0017 stage 2, the quad

`docs/rfc/RFC-0017-cluster-owned-provisioning.md`. **Owner approved implementing the whole arc on rc3** (2026-08-07), having been told it reopens feature work on a branch declared feature-complete. Owner also chose the **replacing** approach over additive: no backward-compatibility requirement, and a clean cut avoids the residuals that keep biting this codebase.

### Done in stage 2

- `ClusterConfigValue` stores **per-(source, role) desired topology** (`AetherValue.TopologyEntry`). `coreCount()` is now **derived, not stored** — the drift where a scale rewrote `coreCount` while `tomlContent` kept the old number is structurally impossible now, not merely fixed.
- REST config paths publish the **real per-source spec** (`ClusterConfigRoutes.topologyOf`), not a lone core count.
- `ClusterTopologyManager.setDesiredCount(sourceName, role, count)` **replaces** `setDesiredSize(int)`. `DiffAction.ScaleUp` always carried `sourceName` and `role`; the applier was discarding both.
- Bare-`coreCount` scale **refuses** when several sources carry cores, instead of silently rewriting one number.

### Remaining in stage 2 — the quad

REST scale route → `aether scale` CLI → `management-api.md` + `cli.md` → dashboard panel.

**Unresolved and needs an owner call:** the CLI must gain `--source`/`--role`, because a bare `--cores N` is exactly the ambiguity the typed model now rejects. Settle that shape before wiring the dashboard. Do the four together so no layer is briefly inconsistent.

### Then, in order

**#570** (`setDesiredSize` lost-update race — the design leans on this path far harder) → discovery-based core assembly → cores provision workers → teardown label sweep → delete worker provisioning from bootstrap.

---

## §2 Two traps that cost me time — read before trusting a green

1. **Maven gives false greens on cross-module API changes.** `mvn -pl <mod> install` returned **0 without recompiling** a downstream module against a changed API. I only caught it because a `.class` file was three hours old. **Use `clean install` for anything that changes a cross-module signature**, and if a build is suspiciously fast after an API change, check a class-file timestamp before believing it.
2. **`build.sh` does not run tests.** I shipped a red test in `51aa98231` because I ran `install -DskipTests` on the module afterwards. Compiling a module is not testing it.

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

**#574 is live-verified on Hetzner** (3 runs, 9 VMs, account CLEAN after each; `test-pg` never touched). Against the real API: one labelled firewall per source; `tcp+udp` → two rules; union-not-replace; no 8090/8080; attached AT server-create (three independent proofs); idempotent re-run issued zero writes; **enforcement proven** — port 22 timed out at 6.0s while allowed 8070 refused in 0.06s; destroy deleted it (404).

---

## §4 Issues

Filed: **#579** (label precondition — fixed), **#580** (preset exposure — fixed), **#581** (RFC-0017 epic), **#582** (codec tag collisions).

Open and load-bearing for RFC-0017: **#570** (`setDesiredSize` unguarded read-modify-write), **#578** (`ClusterConfigApplier` no-ops 8/10 `DiffAction`s — still why firewall *edits* are discarded).

### #582 — codec tag collisions (do NOT fold into RFC-0017)

Hit while adding a serializable nested record: it hashed onto an existing tag and every codec test in `aether/node` died at once.

- `deterministicTag(name) = (hash & 0x7FFFFFFF) % 16256 + 128`. With 469 registered types, expected colliding pairs ≈ **6.8**, P(≥1) ≈ **99.9%**. **Changing the hash algorithm cannot fix this** — it is the birthday bound, not hash quality.
- **Cross-slice collisions are structurally impossible**: `sliceCodec(parent, codecs)` *copies* the parent tag array (`SliceCodec:182`), generated slices add only their own types, and `SliceLoadingContext:132` binds one registry per slice.
- **Real exposure is slice-vs-framework**: ~25% for a 10-type slice, ~44% for 20, growing with every framework type added; the only remedy for the slice author is renaming their own class.
- Proposal recorded on the issue: split the existing derived band — framework `128…4095` explicitly assigned, slice `4096…16383` derived — extending `@CodecFor` rather than adding an annotation.
- **Open question before designing:** name-derived tags are stable under type reordering but change on rename; sequential assignment is the opposite. Matters only if codec-encoded payloads outlive a rebuild — **whether stream WAL carries codec-encoded payloads is UNCONFIRMED** and decides the answer.

---

## §5 Corrections I made (all committed/posted)

Three claims of mine turned out wrong; each was caught by checking rather than by a test:

- "Nothing publishes the topology spec today" — **wrong**, `BootstrapPhaseFormation:247,362` publishes the full `tomlContent`. Came from grepping one file for a few keywords. Corrected in `1543bf2fa`; it made stage 2 smaller.
- "The firewall presets blocked consensus" — **wrong**, `ClusterConfigGenerator` wrote `[operations.ports]` from the same constants, so wizard configs were self-consistent at 7100/7200. The real defect was that the wizard disagreed with the documented defaults. Corrected on #580.
- "Explicit codec tags would shrink the wire" — **wrong**, only tags below 128 are one varint byte and those slots are structural-tag headroom. Corrected on #582.

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
