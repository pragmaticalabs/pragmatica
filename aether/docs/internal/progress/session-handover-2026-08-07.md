# Session handover — 2026-08-07

**Branch:** `release-1.0.0-rc3` · **HEAD:** `f450014a7` · working tree clean, **nothing pushed** · candidate tag STALE (still `9b88911cd`, now ~15 commits behind).

---

## §1 START HERE — RFC-0017 stage 2

`docs/rfc/RFC-0017-cluster-owned-provisioning.md` is written and committed. **Owner approved implementing the whole arc on rc3** (2026-08-07), having been told it reopens feature work on a branch declared feature-complete.

Stage 1 (guardrails) is **done and committed**. Stage 2 is next and is the largest single piece.

### Stage 2 — typed per-source/per-role topology

`ClusterTopologyManager.setDesiredSize(int)` / `desiredSize()` is a bare scalar. It cannot express
"3 cores in `hetzner-eu` + 5 `cpx32` workers in `aws-us`", which is the prerequisite for everything
else in the RFC: cores cannot provision workers from a spec the cluster does not hold.

- Promote to the per-source/per-role model `ClusterBootstrapConfig` already has (count,
  instance_type, image, zone, runtime ref).
- Publish it into cluster state at `CLUSTER_FORMATION`. **Nothing publishes the topology spec
  today** — verified, `BootstrapPhaseFormation` has no config-publication path.
- Touches the REST → CLI → docs → dashboard quad (scale endpoints). Report before wiring that.

Remaining order after stage 2: **#570** (`setDesiredSize` lost-update race — this design leans on it
far harder) → discovery-based core assembly → cores provision workers → teardown label sweep →
delete worker provisioning from bootstrap.

---

## §2 What landed this session

All committed on `release-1.0.0-rc3`, `./build.sh` green (0 errors, lint 0 new) at each step.

| commit | what |
|---|---|
| `69f8acaa0` | #574 Hetzner ingress firewalls, create-or-patch `openIngress` on `ComputeProvider` |
| `3f7427374` | #574 `cluster destroy` actually deletes firewalls |
| `78796a791` | #574 `CREATE_FIREWALL` phase applies rules at server-create |
| `93e268cf2` | #574 wizard `allow_ingress` reaches `SourceProfile`; PF-23 |
| `d35185193` | #574 docs, spec REQ-5.1.8.4 amendment |
| `708f3e31e` | #574 firewall delete retries through Hetzner's async detach window |
| `a65b120c5` | #574 pre-flight warns when `allow_ingress` omits port 22 |
| `e4a8d95f9` | #574 docs — live-run findings |
| `51aa98231` | destroy treats already-terminated VM as destroyed |
| `20e50f352` | readiness gate names the management port; warns when ingress omits it |
| `ff985e589` | docs — management-port trap |
| `2d0bbc65b` | **RFC-0017** |
| `0a070211f` | **#579** refuse to provision a VM whose cluster cannot be identified |
| `6d4f2b6c1` | **#580** cluster init no longer opens the management API to the internet |
| `f450014a7` | **#580** PF-24 |

**#574 is live-verified end-to-end on Hetzner** (3 runs, 9 VMs, account CLEAN after each; `test-pg`
never touched). Verified against the real API: one labelled firewall per source; `tcp+udp` expanded
to two rules; union-not-replace; no 8090/8080; attached AT server-create (three independent proofs);
idempotent re-run issued zero writes; **enforcement proven** — port 22 timed out at 6.0s while
allowed 8070 refused in 0.06s; destroy deleted it (API returned 404).

---

## §3 Issues

Filed: **#579** (label precondition, fixed), **#580** (preset exposure, fixed), **#581** (RFC-0017
tracking epic). Correction posted on #580 — see §5.

Open and load-bearing for RFC-0017: **#570** (`setDesiredSize` unguarded read-modify-write),
**#578** (`ClusterConfigApplier` no-ops 8/10 `DiffAction`s — still why firewall *edits* are discarded).

---

## §4 Two mistakes I made — read before trusting a green module

1. **I shipped a red test.** The 404 → `InstanceNotFound` mapping in `51aa98231` broke
   `terminate_failure_mapsToEnvironmentError`, and afterwards I only ran `install -DskipTests` on
   `environment/hetzner`. `build.sh` does **not** run tests, so nothing caught it until I ran that
   module's suite two stages later. **Compiling a module is not testing it.**
2. **I asserted the firewall presets blocked consensus. They did not.** `ClusterConfigGenerator`
   writes `[operations.ports]` from the same constants, so wizard configs were self-consistent at
   7100/7200. The real defect was narrower: the wizard disagreed with the documented defaults.
   Correction posted to #580.

Also worth carrying: `FirewallPresetsTest` previously asserted
`rulesFor_standard_allRulesUseAnyCidr` — that **every** rule of the default preset, management API
included, uses `0.0.0.0/0`. The exposure was encoded as the requirement, so no failing test could
ever have surfaced it. When a security fix meets a test that "passes", check which behaviour the
test pins.

---

## §5 Live-run findings that only a real cluster produced

Neither was findable by unit test; both are now fixed and documented.

- **Async detach race.** `deleteServer` returns before Hetzner detaches, so the immediate firewall
  delete got `422 resource_in_use` and destroy exited 4. My own unit test *forbade* the discovery by
  asserting no other client call was made. Now retries; re-verified live (`attempt 1/6 … retrying` →
  deleted, exit 0).
- **The readiness gate never inspected cloud-init.** `waitForCloudInit` polls
  `http://<public-ip>:<management>/health/live`. With `allow_ingress` deny-by-default and
  REQ-5.1.8.3 keeping the management port operator-managed, it cannot reach healthy nodes and
  reported `Cloud-init did not finish`. Proven: from inside the host that exact URL returned **HTTP
  200** with the JVM running, while from outside it never connected. Two runs failed identically.

---

## §6 Decisions recorded (owner, 2026-08-06/07)

- **REQ-5.1.8.3 stands as written.** Aether never opens cluster/management ports on its own
  initiative; an explicit `allow_ingress` rule is an operator decision applied like any other.
- **Cores hold cloud credentials for every source they provision into.** No alternative survives
  multi-source clusters. External vault may mitigate — the `SecretsProvider` seam exists
  (Aws/Gcp/Azure/File/Env/Composite/Caching; **no Vault, no Hetzner**). Limit: authenticating *to* a
  vault needs instance identity, which **Hetzner lacks**, so a static token remains there. Cheapest
  real mitigation, no code: **one cloud project per cluster**.
- **Teardown: simple label sweep first**, scoped to `aether-cluster=<name>`, never bare, reusing
  `PROTECTED_CLUSTERS`. #579 is what makes it sound.
- **Cluster label is a hard precondition** — done.
- **Full RFC-0017 arc implemented on rc3**, not deferred.

---

## §7 Standing hazards

- **`test-pg` is still unprovisioned** since the 2026-08-03 incident. Not needed for firewall work.
  Before ANY cloud run: `tools/provision-test-pg.sh --print-only`, and grep the harness teardown for
  destructive calls.
- **#250 storage GC — DO NOT WIRE.** Node-local refcount view deletes from the shared DHT tier.
- Candidate tag ~15 commits stale; nothing pushed this session.
- 11 stale worktrees under `.claude/worktrees/` pollute every repo-wide grep.
