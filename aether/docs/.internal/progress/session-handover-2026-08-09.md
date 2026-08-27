# Session handover — 2026-08-09

**Branch:** `release-1.0.0-rc3` · **HEAD:** see `git log` — this file rides the post-run docs batch on top of `fd50dbb69` · **Candidate tag:** re-pointed to the docs-batch HEAD at session end (third re-point of the day; Release CI green each time).

---

## §1 START HERE — the RFC-0017 arc is LIVE-VALIDATED and #581 is CLOSED

The arc-final Hetzner run happened: **five bootstraps, five destroys, account reaper-CLEAN after
each, `test-pg` untouched** (still unprovisioned since 08-03). All five deferred checks PASSED
against published candidate assets. Canonical evidence: **RFC-0017 §Live validation** (in the RFC
itself) and the CHANGELOG's three new Fixed entries. `scratchpad` artifacts (bootstrap1-5 logs,
timeline, findings.md) live only in the session scratchpad — the durable record is the RFC + CHANGELOG + #581 close comment.

The run was the point: **it found three integration bugs that no in-JVM suite could see, all
fixed same-day, each with tests that go red on the unfixed code (mutation-probed):**

1. `986574f12` — **config-apply chain**: BootstrapModule self-seed (blank `tomlContent`,
   configVersion 1) was never replaced; `handleApplyConfig`'s `.orElse` swallowed apply failures
   into `storeInitialConfig`; its bare unfenced Put was silently rejected by the RFC-0018 fence
   while the route answered success. Worker topology died with a 200. Now: route on presence
   (Option), seed replaced as confirmed fenced successor at version+1, one confirmed write path.
2. `fd50dbb69` — **CTM worker reconcile**: every minted VM was stamped `aether-source=default`
   (from `ProvisionContext.forReplacement`'s hardcode) while the reconcile selector filters
   on the entry's real source → `actual=0` forever → runaway up-scale AND structurally-impossible
   down-scale; plus mid-pass triggers were dropped (doc-promised re-poke was never implemented).
   Now: source name threaded end-to-end (core auto-heal resolves via `cloudSourceFor`),
   pending-trigger replay. NOTE the test lesson: the suite's fake provider filtered on ROLE ONLY,
   which is exactly why the defect survived its own tests — it is now label-faithful.
3. (docs batch) — **firewall presets emitted `tcp` for the cluster port; the transport is
   QUIC = UDP.** Two full bootstraps formed 0/5 behind `in tcp 6000`. `FirewallPresetsTest`
   had PINNED the bug ("tcp") — same encoded-exposure failure shape as the `0.0.0.0/0` finding.
   Presets now emit `udp`; `bootstrap-config.md` gained the trap block.

## §2 Traps discovered/confirmed this session

- **Sequencing correction:** the 08-07 handover said "live run, then tag re-point" — impossible.
  VMs fetch node bits from candidate-tag release assets and stages 3/4/5 are node-side; there is
  NO local-jar channel (the snapshot tool also pulls the release URL). Re-point FIRST, watch
  Release CI, verify the ASSET BY CONTENT (`unzip -p` + `javap` for a symbol landed in the batch)
  — asset timestamps alone were nearly trusted when CI uploaded within ~110s of trigger.
- **Hetzner instance types rotated**: `cx22`/`cx32`(/`cx31`) no longer exist (`cx23`/`cx33`/...);
  `cpx22`/`cpx32` are valid. Docs swept (live specs + RFC-0016); the historical
  website-corrections doc deliberately left.
- **`aether cluster scale --json` never existed** (`-o json` is real); docs fixed at both sites.
- **`clusters.toml`**: bootstrap writes the endpoint WITHOUT the management port and does not
  switch the active context (#584). Until fixed: `sed` the entry to `:8080` and pass `--cluster`.
- **First-boot timing**: VM create → java running ≈ 4-5 min (apt + JDK + 54MB jar). The default
  300s `health_check` gate is tight; this run used `[operations.timeouts] health_check = "600s"`.
- **Multi-module fixes**: `mvn test -pl aether/aether-deployment` alone resolves changed sibling
  modules from ~/.m2 (stale → NoSuchMethodError at runtime or "stale-looking pass"). Scope the
  reactor: `-pl aether/environment-integration,aether/aether-deployment` — still no `-am`.
- **Monitor-filter discipline**: destroy's drain lines ("Draining node...") weren't in my monitor
  grep, so a 6-minute silent phase looked like a hang. Include the phase-progress vocabulary.
- The `mvn install` failsafe hazard, build-runner 600s watchdog stalls (twice more today — both
  times the build finished green on disk; watch the detached PID yourself), and `build.sh`
  reformat-in-place all held exactly as documented in the 08-07 handover §2.

## §3 What landed (commit map)

| commit | what |
|---|---|
| `986574f12` | fix: cluster config apply replaces the bootstrap seed and confirms fenced puts (+`ClusterConfigRoutesApplyTest`, 10 tests w/ fence-modeling KV) |
| `fd50dbb69` | fix: CTM worker reconcile stamps the real source label and replays mid-pass triggers (+6 tests, label-faithful fake) |
| docs batch (this HEAD) | FirewallPresets tcp→udp + test un-pinning; bootstrap-config.md QUIC trap + cli.md `-o json` + instance-type sweep; RFC-0017 §Live validation; CHANGELOG 3 Fixed entries + 2 evidence-tag upgrades; feature catalog rows 574→Complete, 581 added; this handover |

## §4 Issues

- **CLOSED: #581** (arc epic — evidence in the close comment), **#570** (earlier).
- **FILED: #583–#589**: status role misattribution w/ role-blind disruption budget (#583 — has
  TEETH: it refused WORKER drains against the CORE minimum during destroy, twice), registry
  endpoint/context (#584), CL-02 pre-release semver (#585), unredirected node stdout (#586),
  destroy UX triad (#587), membership ghosts after scale-down (#588), CLI wire-contract
  structural gap (#589 — was the 08-07 handover's "NOT YET FILED" item).
- **OPEN unchanged**: #578 (applier drops 8/10 DiffActions — firewall edits on a live cluster
  still discarded), #241 (community seeding), #582 (codec tags), #501, #498.

## §5 Standing hazards (unchanged)

- `test-pg` still unprovisioned. Before ANY cloud run: `tools/provision-test-pg.sh --print-only`
  + grep harness teardowns. Scoped reap only; 2h cap (this session ran over deliberately — the
  overage bought three live-only bug finds; clusters were destroyed between attempts, spend <€2).
- #250 storage GC — DO NOT WIRE. 11 stale worktrees under `.claude/worktrees/`.

## §6 Where next

1. #583 first among the new batch (role-blind counting reaches the disruption budget — a rolling
   worker restart on a minimal core quorum will wedge exactly like the destroy drains did).
2. #578 remains the biggest RFC-0017 leftover: live firewall/config edits are still bootstrap-only.
3. rc4 pipeline per the standing plan; the arc no longer blocks it.
