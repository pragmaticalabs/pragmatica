<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->

# Session Handover — 2026-08-01 (aether-main, continues the 2026-07-27 arc)

**Branch:** `release-1.0.0-rc3`. **HEAD:** `a69b9156e` + this handover. **CI green on HEAD.** Working
tree clean apart from this doc. **PULL FIRST** — the design stream merges into this branch (it landed
`b431bbd3b` mid-session).

> ⚠️ **`v1.0.0-rc3-candidate` is STALE at `27cf20ed1` — 12 commits behind HEAD.** It was deliberately
> not moved: see §5 decision 1. Moving it publishes `ghcr.io/pragmaticalabs/aether-node:1.0.0-rc3-candidate`,
> which the cloud harness now depends on.

## TL;DR

1. **#542, #550, #551 closed** with mutation-verified evidence — schema-migration ownership, deploy-time
   rejection of a duplicate migrator, and baseline no longer wiping the record it baselines.
2. **8 of 9 open PRs merged** (#516, #531, #536, #537, #544, #546, #549, #552). Only #553 remains, and
   it needs a scoring-model decision, not a merge.
3. **#557 is only PARTIALLY fixed, and the follow-up was rejected at a design gate.** The landed fix
   corrects a path that does not govern on real clusters. Read §3 before touching it.
4. **The cloud harness could never have run.** It referenced an image that was never published, built
   from a module that no longer exists. Fixed in `a69b9156e`; the LB question is #560.
5. **Hetzner was NOT run.** Remote-host validation came first (free, closer to cloud) and found enough
   to change the plan twice.

## 1. What landed (commit order)

| Commit | Content |
|---|---|
| `a1e55068f` | #542 gate scoped to owning blueprint + blocks on FAILED; #550 required `owningBlueprint` + duplicate-migrator rejection (409); #551 baseline preserves coords/owner; schema routes now resolve 404/409/400 instead of a blanket 500 |
| `27cf20ed1` | Quad for the above — CLI `OWNING BLUEPRINT` column, dashboard owner + `HOLDING` badge, management-api/cli/feature-catalog/CHANGELOG |
| `7daf62647`…`9b1ee20a2` | PR merges: #516, #531, #544, #536, #552, #549, #546 |
| `dc24377a7` | #557 boot-time quorum counts observed-connected peers (see §3 — incomplete) |
| `964132b08` | #557 changelog entry |
| `a69b9156e` | Cloud harness: management routed to a core node, retired `aether-lb` dropped, rc1 pins corrected |

**Closed:** #542, #550, #551 — each closing comment carries the mutation-check evidence and, for #542,
an explicit correction of two claims in the issue's own text that would have produced a broken fix.

## 2. THE lesson from this arc

**Four consecutive tickets were wrong in their causal framing.** Every correction came from reading the
code, never from the issue text. This is now recorded in memory
(`feedback_validate_ticket_before_implementing`) and mirrored to `../oss/context/`.

- **#542** was filed as a one-line predicate bug. It was four defects: the gate was also *inverted*
  (held slices through recoverable retries, released them on permanent failure), unscoped, and `FAILED`
  was a dead-end log line. Both fix directions it proposed relied on `artifactCoords`, which is unusable
  as a scoping key. And it was structurally **downstream of #550, which nobody had filed** — scoping by
  owner only works once duplicate ownership is rejected.
- **The publish 409** was declared, mapped, and covered by a passing mapping-level test — and was
  unreachable. `Result.allOf` folded the typed cause into a composite, which is not `HttpStatusAware`,
  so the funnel answered 500. A mapping test proves the mapping; only a route-level test proves the route.
- **#557** — I argued the fix belonged at the cause rather than downstream. The cause exists on *two*
  paths and the one fixed does not run on real clusters (§3).
- **The `SyncRequest` re-arm**, which I recommended and the owner approved, was a **no-op**:
  `doSynchronize` already re-broadcasts and re-arms unbounded. The cited evidence for a missing retry
  was absent `log.trace` output — absence from a disabled log level.

**Corollary:** a `[verified]` claim is only worth its tag if the cited test exercises the **live path**.
`./build.sh` has been green with a feature entirely disabled; 2915 unit tests were green while forge hung
for 30 minutes. This is now `CLAUDE.md` §6 (*Claim discipline — evidence tags*).

## 3. #557 — READ THIS BEFORE TOUCHING IT

**What landed:** `dc24377a7` makes the **BOOTING fallback** (`legacyHealthyActivePeerCount`) intersect
the dial set with the transport's last-reported CONNECTED set. Correct, deterministic-tested, mutation-verified.

**Why it does not fix the bug on real clusters:** measured on a 5-node remote-host cluster, every node
transitions `BOOTING -> NORMAL` from a restored snapshot *one millisecond before* quorum is evaluated,
so `haveQuorum()` takes the `MembershipView` branch — which the fix deliberately left alone. Observed on
`aether-a-node-1`:

```
06:31:44.035  BOOTING -> NORMAL (snapshot coreMemberIds=5 >= quorum=3)
06:31:44.035  Quorum established (local view)
06:31:44.036  RabiaEngine: quorum connected. Starting synchronization attempts
06:31:44.056  SWIM member joined … node-2 — no HEALTHY hint until …
06:31:44.195  QUIC Hello handshake complete with first peer
```

`healthyOnDutyCount()` was satisfied by the snapshot's ON_DUTY set while **zero** peers had completed a
handshake and SWIM had not yet admitted any. Same "health assumed, not observed" flaw, one layer up.
`SyncRequest` still goes into an empty network for 159–286ms (was ~1s pre-fix).

**Why the follow-up was rejected — do not retry it as specified.** The proposed rule (only responders
that served authoritative state satisfy the sync quorum) **provably deadlocks fresh-cluster formation**.
Proven empirically: baseline 5/5 nodes activate; with the rule applied, **0/5 activate** across 10 sync
rounds. There is no path to Active without first receiving a `SyncResponse` — `activate()`/`activateAsObserver()`
are reachable only from `restoreState()`, which is reachable only from the two sync-response handlers.
Fresh formation is *powered by* the very empty-state responses the rule would disqualify.

**Correction now on the issue:** the earlier claim that a seed node "self-satisfied via `applyRestoredState`"
was a **test artifact**. `LocalNetwork` loops `broadcast` back to the sender; production
`QuicClusterNetwork.broadcastPayload` iterates `peers.values()` only — no loopback. Do not reason from it.

**Second, independent defect found:** `syncQuorumSize()` returns `min(connected, clusterSize)/2 + 1`,
i.e. **1** whenever connectivity is 0 or 1, regardless of cluster size. A single response decides. Any
real fix must address both this and authoritativeness.

**Shape that might work** (unimplemented, needs its own review): authoritativeness cannot be *necessary*
for quorum, only necessary *when an authoritative peer is reachable*. A cold node needs a locally
evaluable formation-vs-catch-up discriminator — e.g. accept an all-non-authoritative response set only
when the responder count reaches the full expected cluster size.

**Implementation trap:** `SyncResponse` is `@Codec` and its generated decoder calls the canonical
constructor. Adding a component *plus* a convenience 2-arg constructor (to avoid touching ~15 test call
sites) lets wire paths silently default the flag while in-process tests stay green.

## 4. Gotchas ledger (new this arc)

- **A stale rc2 `aether` CLI shadows on PATH** (`~/.aether/bin/aether`). The integration harness has a
  version-parity preflight that aborts *before* bootstrap (#440) — it works, and it saved a wasted cloud
  provision. Fix: `AETHER_BIN=<wrapper over aether/cli/target/aether.jar>`; the harness prepends its dir
  to PATH. A wrapper is at `scratchpad/aether-bin/aether` pattern — recreate as needed.
- **`-Dfailsafe.excludedGroups=` runs a SUPERSET.** CI's forge job executes **34** tests; that flag made a
  local run execute **71** and blow the 1800s fork timeout. It is from the *single-suite* recipe
  (`-Dit.test=X`); do not generalise it to a full run. CI's actual command is
  `mvn verify -B -Pwith-e2e -pl aether/forge/forge-tests`.
- **The full forge suite hangs on this machine** — cross-test cluster leakage in a shared JVM
  (`forkCount=1`, `reuseForks=true`); a `sofp-*` zombie from an earlier class blocks a later one. Single
  classes run fine (`ClusterFormationTest` 20s, `SliceVersionLifecycleTest` 32s).
- **`git worktree` + `--delete-branch` closes stacked PRs.** Merging #536 with `--delete-branch` deleted
  #546's base branch, and GitHub **closed** #546 rather than reparenting it. Recovery: recreate the base
  branch at its old SHA, reopen, retarget, delete again. Merge stack bases **without** `--delete-branch`,
  retarget children first.
- **Squash-merging a stacked PR creates `add/add` conflicts** for its children. #546's resolution was
  provable (zero release-side code lines missing) but its changelog needed a **de-duplicated union**, not
  marker-stripping — the branch carried the parent's entries too.
- **Absent log lines prove nothing** until the level is checked (`log.trace` at `RabiaEngine:1010`).

## 5. Next session — decisions first, then work

**Decisions parked (owner):**

1. **Move the candidate tag?** It is 12 commits behind at `27cf20ed1`; CI is green on HEAD. Moving it
   publishes the `1.0.0-rc3-candidate` images the cloud harness now pins. Nothing else blocks it.
2. **Hetzner — run now, or after #557 is genuinely fixed?** The harness is repaired and the remote-host
   gate is green, but a run today samples a knowingly-incomplete #557. Sequence is: move tag → Release CI
   publishes images + dist assets → container sweep → JVM sweep (needs `jar_url` wired; `aether-cloud.toml`
   only has container config today).
3. **#557's real fix** needs a design pass (§3). It is on the rc3 milestone.
4. **#553** — the last open PR. Replaces the 0-100 score with violation density per KLOC, and conflicts
   substantively with the score work just merged: 72 release-side code lines absent from its version of
   `ScoreReport`, 11 and 9 `add/add` conflict blocks in two test files. Needs a scoring-model decision,
   not a merge. I deliberately did not resolve it.
5. **rc3 milestone triage** — 30 open. Most are rc4-shaped riders, as in the previous arc.

**Ready to implement, no decision needed:** #517, #519, #524, #543, #545, #547, #558.

**Verification debt:** the #557 quorum-drop behaviour (`connectedPeers()` excludes `EVICTED`, so a
transient eviction can now emit a PASSIVE edge that was previously impossible) is **unmeasured**. Node
logs are not archived by the harness; measuring it needs `--skip-teardown` plus a log pull. Zero
occurrences observed, but from a log that could not have contained them.

## 6. Issues filed this arc

**rc3:** #557 (cluster-start quorum from discovery, not reachability — partially fixed, see §3).

**rc4:** #554 (schema gate scopes by migration *ownership*, not usage — the stated limit of what shipped),
#555 (`schemaRequired` does not survive FSM restore), #556 (no local gate runs forge, so cluster-level
regressions surface 30 minutes later in CI — includes the per-class-timeout suggestion), #558
(`NodeState.suspected` never called; the health filter is a discovery count and connection backoff is
inert), #559 (backfill cannot distinguish "owner empty" from "I am at the owner tail" — **reproduced on
real hardware**, costs a red `02-chaos` suite today), #560 (decide the load balancer: retired deployable,
orphaned Dockerfile — mode-on-node vs not at all).

## 7. Remote-host validation results (2026-07-31)

Full suite, 5 nodes in containers on `$TARGET_HOST`: **11 passed / 4 failed / 0 unrecoverable** (26 min).
All four failures attributed to causes independent of this arc's changes:

| Suite | Failure | Cause |
|---|---|---|
| `06-deployment` (3) | `POST api/deploy` → 500 | `Blueprint not found: url-shortener:1.0.1` — fixture missing from the artifact store |
| `13-edge-cases` | concurrent publish B=500 | **stream** publish route, not the blueprint path #550 touched |
| `03-scaling` | scale-down 7→5 stalled at 6 after 180s; marker PUT 500 cascades | reconciler-under-load class (#509 territory) |
| `02-chaos` (2) | auto-heal missed JOINING window; `expected 'CAUGHT_UP', got 'SYNCING'` | the second is **#559** |

Attribution is **by inspection of causes, not by baseline comparison** — there is no pre-change run of
this suite. If certainty is wanted, a baseline run on a pre-#557 build would give it.

Positive signal for #557: formation is healthy — `cluster WHOLE (leader deficit=0, all 5 cores present)`
satisfied in **0s** across 7 cycles in `02-chaos`, `phase=NORMAL` in 1s, and all eleven formation-bearing
suites green on real multi-container networking.

## 8. Standing state

- **Hetzner:** standing grant, always scoped-reap cleanup, hard 2h cap, never touch `test-pg`. **No cloud
  resources were created this arc** — nothing to reap.
- **Cloud harness (`a69b9156e`):** management now goes to a core node's own port (`CORE_MGMT`,
  `[deployment.ports].management` = 8080); the `aether-lb` container is gone; node image pinned to
  `1.0.0-rc3-candidate` in both `aether-cloud.toml` and `deploy-cloud.sh`. The `cloud-test-lb` VM remains
  as SSH jump host and Docker host (`TARGET_HOST`). `cloud-testing-spec.md` carries a superseded-in-part
  banner; its body still describes the retired LB design and was not rewritten.
- **Context mirror:** `../oss/context/pragmatica/` now mirrors the gitignored `CLAUDE.md` and changed
  memory entries, with a README stating provenance, the sync rule, and the open question of which rules
  deserve a tracked home. `oss/CLAUDE.md` points at it. **`oss/` is uncommitted.**
- **GA envelope:** `../oss/tmp/ga-envelope-brief.md` was reviewed and updated with seven adopted points —
  most importantly that writing the envelope *is* a hypothesis-driven gap audit and should be budgeted as
  one, and that `AETHER_INSECURE_DEV_MODE=true` in the integration environment means the suite validates
  the **insecure** posture (so "does GA ship security default-on" is two questions, not one).
