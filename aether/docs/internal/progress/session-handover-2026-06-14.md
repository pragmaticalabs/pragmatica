# Session Handover — 2026-06-14

## TL;DR
**v1.0.0-rc1 SHIPPED** (merged to main, published to Maven Central, tagged). **RC2 is open and in progress** on `release-1.0.0-rc2`. First RC2 bug — **#274 pub/sub namespace-blind routing** — is **fixed and module-verified** (commit pending the full-build gate). RC2 roadmap proposed from a 117-issue re-triage; not yet operationalized (no milestone/doc/lane-spinup yet).

---

## 1. RC1 release pipeline — DONE
See [[project-rc1-released]] memory for full detail. Summary:
- Found + fixed 2 release-blocking foundation bugs under cloud load, validated **14/15 Hetzner**:
  - **Zero-leader missed-quorum-presence-edge wedge** — `evaluateQuorumState()` now re-runs in `TopologyObserver.initReconcile` (5s tick, CAS-idempotent). [[project-zero-leader-missed-quorum-edge]]
  - **Concurrent first-publish 503** — `StreamPartitionManager.ensureStreamMaterialized` decouples stream auto-create from the synchronous consensus commit.
  - Plus loud `AETHER_INSECURE_DEV_MODE` startup WARN.
- History re-folded to **25 cohesive subsystem commits** (byte-identical, base main `03fe57bb4`).
- **Merged to main** via PR #332 (merge commit `786cb7432`, 25 commits preserved, NOT squashed).
- **Published to Maven Central** — full reactor (Apache core/integrations/jbct + BSL aether), GPG-signed, autoPublish bundle `dff34db2…`, exit 0.
- **Tag** `v1.0.0-rc1` (annotated). Candidate tag deleted.
- Cleanup: Hetzner box `aether-gate` DELETED; 2 stale clones removed (work confirmed already in main; 3 stashes salvaged to `IdeaProjects/clone-stash-salvage/`); ~24 worktree-agent + 3 at-tag old-release branches deleted.
- LinkedIn draft: `/tmp/linkedin-rc1-ascii.txt` (corrected — see §4).

## 2. RC2 branch
- `release-1.0.0-rc2` created from main, version bumped to **1.0.0-rc2** (`8cd2def45`), pushed.
- Doc-correction commit `7595d6917` (security scope-banner fix, see §4).

## 3. RC2 re-triage + roadmap (PROPOSED, not yet locked/operationalized)
Re-triaged 117 open `rc1`/`rc2`/`bug` issues via 6 parallel agents + a lint-suppression audit (#31).
- **Close now — verified already DONE in main (9):** #73, #146, #156, #166, #173, #174, #177, #247, #284.
- **Defer RC3/post-GA:** #119, #120, #147, #277, #298, #300, #305, #312, #323, #324.
- **RC2 tiers (foundational-first + quick-wins):**
  - **Tier 0 (gates production):** Security hard-gate #290/#282/#299/#289; Stream integrity #260/#261/#262/#266 + durability #248/#249/#264; Reconciler-under-load #325/#329/#331/#258.
  - **Tier 1 quick-wins (S effort, H/M weight):** #251, #287, #289, #293, #302, #292, #266, #263, #148, #259, #301, #319, #316/#310/#283.
  - **Tier 2 breadth:** resource/interceptor lifecycle #268/#271/#278/#279/#280/#269/#275; scheduled/pubsub #272/#273; persistence #250/#252/#255; dashboard #303/#294/#304/#291; CLI #308/#309/#311.
  - **Tier 3 DX/docs:** #169, #170, docs epic #314 (#315/#317/#318/#320/#321/#322), #164.
- **Parallelization map (worktree lanes / team):** Security (`ManagementServer.java`), Stream (`StreamPartitionManager`/replication), Reconciler (`LeaderReconciler`/`NodeDeploymentManager`), Consensus #258 (`RabiaEngine`), Scheduled/pubsub (`NodeDeploymentState.java`), Interceptors pkg, Dashboard (JS, no Java conflict), Docs (fully parallel), jbct DX. ~9 independent lanes.
- **Lint-suppression cleanup (#31, audit done):** 8 class-level `@Contract` blankets hiding real throws (`ProcessCommandRunner`, `DockerComputeProvider`, …), dht deserializer EX-01 pair, AlertManager `@NullReturn` trio. See the agent report.

## 4. Public-claims correction (security posture)
The RC1 LinkedIn draft + README scope banner + CHANGELOG + `v1.0.0-rc1` tag all overclaimed **"Security ON by default."** Verified against code: **default is `SecurityMode.NONE`** (#290) — security is built-in but OFF by default. Also corrected in the LinkedIn: removed phi-accrual (removed from code, `AetherNode.java:1427`), "mutual TLS"→TLS, dropped "durable/cursorable" stream + "pub/sub isolation."
- **Fixed on rc2:** README + CHANGELOG (`7595d6917`).
- **STILL WRONG (open decisions):**
  - **main** branch README/CHANGELOG still say "ON by default" → recommend cherry-pick `7595d6917` to main.
  - **`v1.0.0-rc1` tag** annotation (immutable) has the same line → recommend leave (README authoritative) vs re-tag.
- Lesson recorded: [[feedback-verify-public-claims-against-code]].

## 5. #274 — pub/sub namespace-blind routing (RC2 TOP, FIXED, commit pending)
Root cause: namespacing done for addressing+storage but NOT routing — `findSubscribers` matched bare `address.name().value()`, so same bare topic name in two blueprints/namespaces cross-delivered (multi-tenant isolation breach). Root-cause posted to #274; `rc2` labeled.
**Fix (8 files on rc2, uncommitted, module-verified):** new shared `TopicAddressResolver` (slice/blueprint) used by BOTH publisher (`PublisherFactory`/`TopicPublisher`) and subscriber (`NodeDeploymentState`), so namespace derivation can't diverge; `TopicSubscriptionRegistry.findSubscribers` now matches the full `ResourceAddress.asString()`. 8 new tests (cross-namespace isolation both directions, intra-namespace delivery preserved, cross-version no-bleed). **aether-invoke 197/0, aether-deployment 620/0, lint clean.** Full `./build.sh` running to gate before commit.

## 6. Immediate next steps
1. **Commit #274** once `./build.sh` is green (it was running at handover — check `/tmp/BUILD-rc2-274.log`).
2. **main/tag security-claim decisions** (§4) — cherry-pick to main? re-tag?
3. **Operationalize RC2 roadmap:** write `rc2-roadmap.md`, create the RC2 GitHub milestone, close the 9 DONE issues (with evidence), re-label deferred → rc3/post-ga, then spin up the parallel worktree lanes / team for Tier 0 + Tier 1.
4. Backups still LOCAL, not dropped (per rule): backup/pre-cleanup-rc1, pre-refold-rc1, pre-refold2-rc1, pre-refold3-rc1. Plus dev branches + old-release-with-local-commits pending user's cleanup call.

## Key references
- Memory: [[project-rc1-released]], [[project-zero-leader-missed-quorum-edge]], [[project-reconciler-under-load-class]], [[feedback-verify-public-claims-against-code]].
- Issue inventory: `/tmp/rc2-issue-inventory.tsv` (156 open). Triage batches in the session transcript.
- RC1: main `786cb7432`, tag `v1.0.0-rc1`, Central deployment `dff34db2…`.
