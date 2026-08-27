# Session Handover — 2026-07-17 (aether-main)

**Branch:** `release-1.0.0-rc3` @ `a961a003f` (pushed, tracking). **rc2: SHIPPED end-to-end 2026-07-16.** **Cloud: PG-only** (`aether-test-pg-038708`=149856199 + `aether-pg-firewall`). Working tree clean.

## TL;DR

**v1.0.0-rc2 is fully published**: PR #461 → main (`964a05652`), tag `v1.0.0-rc2`, GitHub release (CLI/Forge/node × darwin-arm64/linux-amd64/linux-arm64 + jars + SHA256SUMS), **Maven Central live** (deployment `4e01b31e-4b92-46c0-93f5-3731b15f40d5` validated+auto-published; `org.pragmatica-lite:core:1.0.0-rc2` HTTP 200 on repo1). Before the cut: the release-branch history was **rewritten rc1-style** (353 commits → 11 thematic buckets, hash-scrub to issue refs, tree byte-identical to the fully-gated state; old history preserved in local ref `backup/rc2-pre-rewrite-2026-07-16`). rc3 is open with a two-commit start: version prep (`341610ffb`, 163 files → 1.0.0-rc3) + **repo-wide format sweep** (`a961a003f`, 826 files; #447 closed). Ticket ledger fully reconciled.

## The cloud-gate endgame (runs 4–6 of the arc)

- **Deterministic 03-scaling "6 of 7" root-caused by live forensics: Hetzner account server limit** — every CTM attempt got `403 resource_limit_exceeded` (`GET /api/cluster/provisioning` → `lastProvisionFailure`); concurrent A+B clusters + dead-seed zombies pinned the quota; net-zero auto-heal slipped through freed slots, net-+2 scale-up never could. **Fix: serialized cloud clusters** (`bc8e7839f`-era; now in the `test(integration)` bucket) — A suites run, A is REAPED, then B bootstraps; peak footprint halved. 03-scaling went 0p/3f → **3p/0f (389s)**.
- **VM snapshot built but UNUSABLE** — `408864384` (`aether-1.0.0-rc2-jvm`) exists; the TOML `[source] image` is never plumbed to provision (provider reads only absent `[cloud.compute]` → hardcoded ubuntu default). #442-family gap → **#459**. All VMs boot stock Ubuntu; kill-recovery budgets pay full JDK+jar installs.
- **Two more harness hang classes fixed**: hard `timeout` belt on every `aether` CLI call + `restore_cluster_baseline` leader gate moved to the bounded curl path (a 3.5h silent fork-leaking hang against a dead fleet). A third stall site (post-unrecoverable wait) + suite-continues-after-unrecoverable → **#460**.
- **Last-try 02-chaos (owner-ruled document/ship): 5p/2f — product 100% green.** `kill-under-load` passed (first cloud-JVM pass of the #94-class test); the **stream-failover script passed every assertion for the SECOND cloud run** (all 20 acked served post-failover, tail 0..24 ordered, RF re-converged). The 2 fails: S19's survivor probe runs `docker inspect` on docker-less JVM VMs (harness flavor bug → #460) and S20 missed by 13s (snapshot-less re-bootstrap → #459). Documented in CHANGELOG + feature-catalog (`a43c04c70`).

## History rewrite (owner-directed, rc1-style)

`git reset --soft 786cb7432` → 11 path-thematic buckets (core → integrations → jbct → aether-deployment/node/slice/runtime → test → examples → docs → build) with hash-scrub (11 citations → issue refs in CHANGELOG/feature-catalog; 9 pre-base hashes survive). Identity check: diff vs backup = exactly the 6 scrubbed lines. Candidate tag re-pointed, Release CI republished. PR #456 (formatter #447 fixes) was merged first and its 726-file aether/examples reformat committed pre-rewrite; both dissolved into the buckets.

## rc3 opening state

- `341610ffb` version prep: 153 poms + 4 READMEs + cloud TOMLs (**jar_url/image now reference `v1.0.0-rc3-candidate` — that release does NOT exist yet; push the candidate tag before ANY cloud run**) + notification-hub coords + CHANGELOG (`## [1.0.0-rc3] - Unreleased` added; rc2 date-stamped 2026-07-16). Sweep clean at canonical rc3. Known bootstrap gap: `mvn validate` fails until first `./build.sh` (rc3 jbct plugin) — already resolved locally (build.sh ran green twice).
- `a961a003f` format sweep: 826 files (integrations 532, jbct 200, core 60, standalone examples 27, testing 7) — the POM-exempt trees, formatted via the one legitimate skip-inversion (`mvn <plugin>:format -Djbct.skip=false`). Gated: build.sh (bootstrap rebuilt from formatted jbct) + **full reactor `mvn test` green**. Idempotency proven (0 drift on re-build).

## Ticket ledger (this session)

Closed with evidence: **#445** (4-surface validation incl. 2 cloud stream passes), **#421** (dup), **#441**, **#442**, **#403**, **#426**, **#447**. Filed rc3: **#457** (in-JVM Ember RF=2 owner-kill test; fixture-classpath gap), **#458** (CI forge-tests chronically red on runners since ≥07-12; product exonerated on real infra), **#459** (image spec→provision plumbing), **#460** (suite-abort-after-unrecoverable, stall-site-3, S19 JVM probe, 08-resources publish flake), plus **#446** (RFC 10008 HTTP QUERY, earlier). #431 unblocked-note posted (stays rc3). Untouched for owner: #427, #420, #444.

## Operational gotchas (new since 07-15 handover)

- **macOS ages /tmp files out (~3 days)**: `/tmp/aether-test-pg.env` vanished mid-arc and killed a run silently (wrapper `source` failed pre-log). Rebuilt from the PG VM's container env (password never entered transcript); **durable backup now at `~/.aether/aether-test-pg.env.backup`** — restore with `cp` if /tmp ages out again.
- **Candidate-tag ↔ Release CI ↔ jar_url coupling**: moving `vX-candidate` re-triggers CI republish of the jar the cloud-JVM TOMLs download. Docs-only commits don't need a tag move; jar-affecting ones do.
- Monitor patterns: `401|403` matches source line numbers (`[401,49]`) in compiler output; `FATAL` matches `NON-FATAL`. Watch suite tallies + exit markers, not raw error words.
- `gh pr checks` inheritance: PR checks run on the merge ref → PRs inherit chronic branch CI failures (#458 cost a diagnosis cycle on PR #456).
- Reap loops: use `printf | while read` (zsh doesn't word-split unquoted multiline vars) + parallel deletes + 3-consecutive-clean convergence (auto-heal races single-pass reaps).

## Next-session queue

1. Re-arm MAILBOX monitor (loop contract; silent since 07-10).
2. **Owner decision: rc3 headline = full major-cloud support.** Recommendation delivered and pending: spec-writer capability matrix + per-tier validation bar (Tier-1 Hetzner full-gate / Tier-2 one cloud e2e per release / Tier-3 code-complete+smoke), fold #459+#444 into the provider-agnostic SPI design, AWS first with LocalStack contract tests; owner prerequisites = cloud accounts/credentials/budget.
3. First rc3 code batch → push `v1.0.0-rc3-candidate` tag (unblocks cloud TOMLs).
4. Owner decision queue (unchanged): #427 evidence check, #420 milestone formality, durable-pubsub D1–D5 (D5 owner-held), KVStoreSerializer parse-quarantine (#411 options).
