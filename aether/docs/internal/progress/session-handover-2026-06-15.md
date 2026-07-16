# Session Handover — 2026-06-15

## ⚡ TL;DR

Pivoted from RC2 cloud validation into a **harness-resilience initiative** (user framing:
*the test harness is a UX proxy for the whole system — bootstrap → CLI → cloud*).

- **Tier B harness fixes IMPLEMENTED + COMMITTED** (3 commits on `release-1.0.0-rc2`, **NOT pushed**):
  `565e6bc9f` (harness resilience), `3330bbf87` (nbg1 env), `d1457ee88` (spec).
- **Harness-resilience spec written**: `aether/docs/specs/harness-resilience-spec.md`
  (root-cause map + Tier A product capabilities + Tier C UX/provisioning + C7 preflight).
- **Biggest finding (NOT a code bug):** the `aether` CLI's `No route to host` against the
  remote docker cluster is **macOS Local Network Privacy (TCC)** blocking the `java` binary
  from the `192.168.x` LAN. Cluster + CLI + product are fully healthy. See §4.
- **Environment CLEAN**: cloud torn down (0 servers), remote cluster A + SSH tunnel torn down.

---

## 1. Session arc

1. Stopped the in-flight container cloud sweep (it had validated the product + the 3 rc2
   behavioral fixes + the harness cloud-addressing fixes — 07-cluster-mgmt went 3/1→4/0).
   The remaining red was harness-cloud-completeness/env/timing, plus a contaminated
   9-members-vs-2-VMs mid-kill snapshot. Torn down (cost).
2. User asked to **zoom out and harden the harness** as a UX exercise.
3. Ran a **4-audit root-cause review** of the harness (lib/common.sh, lib/cluster.sh,
   lib/topology.sh, run-tests.sh, env TOMLs, tools/, plus CLI/API gaps).
4. Implemented **Tier B** (harness-internal), wrote the **Tier A/C spec**, root-caused the
   **CLI reachability** issue, committed, cleaned up.

---

## 2. Root-cause map (7 classes)

Full detail in `aether/docs/specs/harness-resilience-spec.md §3`. Summary:

| # | Class | Disposition |
|---|---|---|
| R1 | No node-addressing identity model (resolution quadruplicated; CTM replacements invisible) | Tier B1 (dedup) + Tier A1 (product) |
| R2 | No live-vs-zombie liveness contract ("9 members / 2 VMs") | Tier A2 (product) |
| R3 | Regex-JSON parsing coupled to field names (`id` vs `nodeId`) | Tier A3 (schema contract) |
| R4 | Silent failure — **mostly fail-SAFE** (conservative→timeout), one genuine silent-PASS | Tier B2 (the one real one) |
| R5 | No baseline isolation between destructive suites (one failed restore poisons downstream) | Tier B4 |
| R6 | Suite authoring friction (01-stability never runs; parse_suite_conf scoping; app-port) | Tier C1/C2 |
| R7 | Provisioning fragility (412 no-retry, snapshot-zone, PG firewall, docker not dogfooding bootstrap) | Tier C3/C4 |

**Key honest reframe:** verifying the audit's claims showed most flagged `2>/dev/null || true`
patterns fail *safe* (visible timeout), not silent-pass. The single genuine silent-PASS was
`observe_quorum_window` (fixed in B2). Don't blanket-rip out the `|| true` guards.

---

## 3. Tier B — DONE + COMMITTED (`565e6bc9f`)

All four shell files `bash -n` clean; `lint-tests.sh --strict` = **0 new findings**. Net LOC
negative (B1 removed ~80 lines of duplication).

- **B1 — resolver dedup.** `aether_failover` (common.sh) + `rotate_mgmt_entry_point`
  (cluster.sh) now **delegate to the single tested resolver** `_resolve_live_endpoint` /
  `_refresh_mgmt_entry_point`. Removed the latent `aether_failover` bug that used `MGMT_PORT`
  (docker host range) instead of `CLOUD_MGMT_PORT` on cloud. *Exonerated + validated on remote*
  — the api_get path (which uses `_resolve_live_endpoint`) passed every 00-smoke check.
- **B2 — `observe_quorum_window` parse-integrity** (topology.sh). Tolerates quoted+unquoted
  `clusterSize`; **fail-closes only on genuine drift** (field present but unparseable), NOT on a
  legitimately clusterSize-free NODE_FAILED window. **Proven by 6/6 synthetic cases**
  (`/tmp/test-observe-quorum.sh` — consider promoting to a lib unit test, spec C5).
- **B3 — silent-failure audit.** Verified the rest fail-safe; **no change needed**. Diagnostic
  value delivered via the B4 gate logging.
- **B4 — cluster-B unrecoverability gate** (run-tests.sh). The loop now calls authoritative
  `restore_cluster_baseline` + a gate: `restore_rc != 0 OR ready < floor OR no leader` →
  `aborted=true` → remaining destructive suites **skip-with-reason** (activates the previously
  **dead** `aborted` branch). **Hardened**: gate reads liveness via new raw-HTTP helpers
  `cluster_leader_http` / `ready_core_count_http` (cluster.sh) — curl/api_get, **immune to CLI
  degradation** (e.g. the macOS LNP issue below). Helpers **empirically validated against the
  live cluster** via SSH tunnel: leader=`aether-a-node-1`, ready=4=floor → no false-quarantine.

---

## 4. ⚠️ Biggest finding — CLI "No route to host" is macOS Local Network Privacy, NOT a code bug

A remote `--env remote` run failed 00-smoke with a cryptic `java.net.ConnectException: No route
to host` cascade. **Root-caused empirically** (not from the inconclusive static investigator):

- `java` (the CLI's Homebrew openjdk 25) → LAN (`192.168.0.71` AND the gateway) =
  **instant 0–1ms `NoRouteToHostException`**; `java` → public `1.1.1.1` = connected.
- `nc`/`curl` → same LAN = **OK**; route is direct via `en0`; node server logs **empty** (the
  request never left the Mac); the **cloud** CLI run (public IPs) worked fine.

⇒ macOS **Local Network Privacy (TCC)** is blocking the `java` binary from the `192.168.x` LAN.
The remote docker cluster is LAN-hosted, so **every CLI op from this Mac is killed at the OS
layer** before reaching a node. **Aether, the CLI, and the harness are all healthy** — confirmed
by an **SSH tunnel to loopback** (`-L 15151:localhost:5151`): `aether -c http://127.0.0.1:15151
status` returns full data (loopback is LNP-exempt).

**Why "worked 6 days ago":** the Homebrew `openjdk` upgrade to 25.0.2 (new binary identity) or
the darwin 25.5 update reset the binary's TCC grant.

**Fixes / workarounds (operator action):**
- Grant Local Network access to the **terminal app** (iTerm2 is the TCC-responsible parent) and
  **restart it** (the grant does NOT apply to an already-running app — this is why granting
  mid-session didn't help; it would kill the Claude session).
- Or run the CLI **inside** the cluster network (SSH on the remote host), or use **public-IP**
  endpoints (cloud), or an **SSH tunnel to loopback** (proven bypass).
- Harness fix: **Tier C7 connectivity preflight** (in the spec) — probe via curl AND CLI; if
  curl OK + CLI fails, emit "grant Local Network access / run inside network" instead of the
  cryptic cascade. Would have turned a multi-hour hunt into one line.

---

## 5. Spec — `aether/docs/specs/harness-resilience-spec.md` (DONE, `d1457ee88`)

New, non-duplicative doc (cross-references `integration-test-overhaul-v2-spec.md` +
`cli-gap-audit.md`). Contents: UX-proxy thesis, root-cause map, Tier B (recorded DONE),
**Tier A product "fix-twice" capabilities** (each triad-gated, RC2/RC3):
- **A1** `GET /api/nodes/{id}/endpoint` + `aether nodes resolve` — kills bootstrap-state.json /
  HCLOUD_TOKEN dependency; works for CTM ULID replacements.
- **A2** `GET /api/nodes/live` → `[{nodeId,address,role,swimAlive,reportedState}]` — kills the
  zombie/9-vs-2 class + the `pick_non_leader` 3-source stitch.
- **A3** versioned status schema + CI contract test — kills field-rename silent wrong-answers.

**Tier C** (harness UX/provisioning): C1 suite.conf routing (re-enable 01-stability), C2 shared
preconditions + template + CHARTER, C3 412-retry/snapshot-zone-guard/PG-firewall-TTL, C4 docker
bootstrap dogfooding, C5 lib unit tests, C6 fix 5 tautological asserts, **C7 connectivity preflight**.
Sequencing table + open questions included. Minor cosmetic nits noted (a few estimated line
numbers drift; R3 attributed to `_resolve_live_endpoint` vs actual `status_node_ids`).

---

## 6. What remains / next steps

- **#59 — full runtime proof of B4**: exercise the gate's *abort* path in a live genuinely-
  degraded cluster (low-risk; logic simple + signals validated). Blocked from this Mac by macOS
  LNP → do on the **next cloud sweep** or a **fresh session after granting iTerm2 Local Network
  access + restart**.
- **JVM cloud runtime run** (#53 deferred) — `build-aether-vm-snapshot.sh --runtime jvm` then
  `--env cloud --runtime jvm` (TOMLs already pinned to v1.0.0-rc2-candidate jar).
- **Tier A** (A1/A2/A3) — product work, RC2/RC3, triad (REST + CLI + docs). Highest leverage.
- **Tier C** — C1/C6/C7 are cheap quick-wins; fold into RC2 sprint.
- **Push** the 3 commits when ready (currently local only on `release-1.0.0-rc2`).
- **Do NOT** commit the two foreign untracked specs (`aether-knowledge-bundle-spec.md`,
  `akb-projector-lint-brief.md`) — not from this session.

---

## 7. Gotchas / lessons

- **Verify before believing an agent's diagnosis.** The static investigator oscillated across
  TLS/security/forward hypotheses and never landed; the empirical socket probes nailed it
  (macOS LNP). Cited line numbers ≠ proof.
- **The Bash tool runs zsh.** `${BASH_SOURCE[0]}` doesn't resolve → sourcing the `#!/bin/bash`
  libs from zsh fails (relative `source` paths break). Test harness libs with `bash -c`.
- **macOS LNP signature**: instant 0–1ms EHOSTUNREACH, per-binary (java blocked, curl/nc OK),
  LAN-only (public OK), server logs empty. Loopback + public are exempt.
- **Always check orphans before remote runs** (pgrep run-tests.sh / mvn / surefire on local AND
  remote) — but note `pgrep -f` self-matches the check script over SSH (false positive; trust
  `load 0.00` + no aether containers).
- **HCLOUD_TOKEN is set** in the shell — run the harness under `env -u HCLOUD_TOKEN` to avoid
  any accidental `mvn verify` real-server creation. Remote suite runs don't need it.

---

## 8. State snapshot

- Branch `release-1.0.0-rc2`, HEAD `d1457ee88` (3 new commits, **NOT pushed**).
- Cloud: **0 servers** (clean). Remote cluster A + SSH tunnel: **torn down**.
- Tasks: Tier B (#54–#57) done, spec (#58) done, CLI investigation (#60) done; #59 (B4 runtime
  abort-path) open; #53 JVM cloud run deferred.
- Built `aether-node.jar` present (02:28). aether CLI = `1.0.0-rc2` (built 2026-06-14).
