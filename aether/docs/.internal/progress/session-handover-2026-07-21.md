<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->

# Session Handover — 2026-07-21 (aether-main)

**Branch:** `release-1.0.0-rc3`. **HEAD:** `bfd00615d` (#491 batch) + this handover commit; origin may be ahead (design-stream #448/#443 active — **PULL FIRST**). **Working tree:** clean on the pragmatica side after the #491 land.

## TL;DR
1. **#491 batch LANDED + CLOSED** (`bfd00615d`, Release + CI both green). Transport-loss class closed (drops=0, lossless read failover proven). RF-restoration does NOT converge — split into residuals **#498** (SWIM false-removal, rc4) + **#499** (HRW-divergence, rc3).
2. **Release pipeline reorganized** into a truthful shape: rc3 (feature-complete, ~51), **rc4** (validation + hardening + dashboard, new), **v1.0.0** (docs/GA gate, new). GA **quality north star** ruled (4 axes). Dashboard **functional-completeness + ontology-shaped** doctrine ruled.
3. **Aether-book teammate** set up (audit-then-write; prose pause LIFTED). **Re-start instructions in §3** — the owner explicitly asked for these.

---

## 1. #491 — DONE (this session's main execution)
Landed `bfd00615d`: `fix: buffer unicast to absent members + probe-first backfill reverify + committed-owner gate (#491)`. 9 files. Reviewer-491 (opus): 0 land-blocking. Build: consensus 699/0, stream 629/0. Release+CI green.

- **What shipped (proven, durable):** unicast-to-absent-member offline-buffering, eager INIT PeerState, probe-first backfill reverify, committed-owner self-election gate (F4), failover-not-cold-start gate (m2). Result: **drops=0** and StreamOwnerFailoverTest **phases 1–8 pass** = lossless read failover.
- **What did NOT converge (tracked honestly):** RF-restoration after owner-kill. The membership-pinned gate empirically DISPROVED that pinning suffices — with `swimDeadStuck=[]` held (build-runner reproduced 0/3), an EMPTY non-replica node becomes HRW owner and can't catch up from the data-bearing survivor (watermark stuck -1). Deadlock. SWIM-independent.
- **Residuals filed:** **#498** SWIM false-removal-under-churn (membership; auto-heal-off → self-fence) → rc4. **#499** RF-restoration/HRW-divergence + non-owner registry-reseat gap (the concrete mechanism-B surface) → rc3.
- **Regression harness ready:** `StreamOwnerFailoverPinnedTest` + `EmberCluster.withRaisedSwimTimeouts()` seam ship **`@Disabled` on #499**. When #498+#499 close, remove `@Disabled` → the pinned failover must converge 3×.
- Full detail: memory `project_491_batch_stop_point`; issue comments on #491/#498/#499.

## 2. Strategic / pipeline work (this session)
- **Milestones (executed via gh):** rc3 cut 140→51 (active tracks only); **v1.0.0-rc4** (milestone 11) = GA-gate perf/scale (#365–370/#376), dashboard defect-7 + #494/#495, resource-SPI/interceptor audit (#268–281), ops/CLI, API-freeze (#226/#300); **v1.0.0** (milestone 12) = docs #314–324 + trust-model #313; ~42 swept to no-milestone backlog; empty v0.25.0 deleted. Memory: `project_rc4_pipeline_org`.
- **GA quality north star (owner ruling):** no time pressure, quality primary; first release of a new product category → assessed on **reliability / docs completeness / DX+UX / claims↔reality**. Anchored as gate criteria on #376. Filed **#496** (claims-vs-reality guarantee audit, v1.0.0) + **#497** (DX first-touch journey audit, rc4). Cuts on VALUE only, never schedule. Memory: `project_ga_quality_north_star`.
- **Dashboard:** reconciliation found 14 management-API groups with ZERO dashboard surface (spec is pre-pipeline Draft 2026-02). Owner ruled **production-grade, functionally-complete, ONTOLOGY-shaped IA** with **dormant-dimension pattern** (roadmap concepts like zones get stable slots showing true degenerate values, never fabricated). **#494** = gap umbrella (rc4), **#495** = UX/IA spec-first (rc4). Sequencing: API freeze #300/#226 → #495 spec → #305 tooling → build; #417 GameDay = acceptance. **Triad→QUAD invariant** added to project CLAUDE.md (a management endpoint is incomplete without its dashboard surface or an explicit dormant-slot decision).

## 3. AETHER-BOOK TEAMMATE — HOW TO (RE)START  ← owner-requested
**What it is:** a session-scoped teammate (`aether-book`) that works in `/Users/sergiyyevtushenko/IdeaProjects/coding-technology/` (prose `book-aether/`, meta `book-aether-meta/`), coordinated by aether-main. It is NOT persistent — it lives inside the spawning session. For a standing agent, the owner launches a separate coding-technology session, coordinated via the same bridge + `.claude/scratch/cross-session.md`.

**Why it's safe to write now (owner ruling 2026-07-20/21):** the 2026-07-04 prose pause existed because the product moved faster than prose could track (constant divergence). Owner LIFTED it because aether-main now coordinates BOTH the GA/product work AND the book — divergence is preventable IF the lead runs the bridge:
- **Product → book (divergence prevention):** when GA/product work lands a change touching a book-relevant surface (slice/resource APIs, routes, CLI, streaming/pub-sub semantics, DX), the lead hands the delta to `aether-book`.
- **Book → product (gap-finder):** the book's source-verification audit gaps flow into `aether-gap` / #496 / #497 on the pragmatica tracker.

**FIRST, check the in-flight run's leftovers** (the 2026-07-20/21 aether-book agent was mid-run at session end): read `coding-technology/book-aether-meta/HANDOVER-aether-main-coordinated.md` (its checkpoint, if it wrote one), `coding-technology/.claude/scratch/cross-session.md` (its lane claim), and `git -C ../coding-technology status` for what it touched. Resume from there rather than restart cold.

**Re-spawn (Agent tool):** `name: "aether-book"`, `model: opus`, `subagent_type: general-purpose`. Mandate:
- **STEP 0 — LANE SAFETY FIRST (before any write):** book dirs are UNTRACKED working-tree state in a SHARED multi-lane repo (book-editor, pfd-editor, website). Read `.claude/scratch/cross-session.md` + check `book-aether/*.md` mtimes; if a live competing Aether-book-editor session is editing, STOP + report — do not write. Else claim the lane in cross-session.md.
- **STEP 1 — ONBOARD:** read `coding-technology/CLAUDE.md`, any `book-aether*/CLAUDE.md`, `book-aether-meta/{HANDOVER-2026-07-12, PROBE-2026-07-17, PROBE-2026-07-09, BOOK-PLAN, aether-book-voice}.md`; skim `book-aether/part0..part6`. Follow the book lane's own versioning conventions.
- **STEP 2 — GATING AUDIT (phase 1, highest value):** real-slice-contract + drift audit — verify every book idiom/API/code-sample/claim against CURRENT Aether in `../pragmatica` (read-only). Output a STRUCTURED gap list: {book location, claim, product reality, severity, BOOK-fix vs PRODUCT-DX-gap}. Product-side gaps → lead files as aether-gap/#496/#497.
- **STEP 3 — RESUME PROSE (phase 2, cleared sections only):** per PROBE-2026-07-09 P1 (items 1–5), source-verified, in voice. Sections still blocked by an unresolved product gap → flag, don't fabricate.
- **CONSTRAINTS:** no git push / PR without asking; stay in lane (book-aether*, read-only pragmatica); source-verify — no invented API; write/update a book-side handover; report to lead via SendMessage (lane outcome, onboarding summary, gap list, prose progress, next step).

**First product→book sync OWED to it:** the #491 batch changed streaming failover semantics. Hand it the honest current story — **drops=0 + lossless read failover PROVEN; RF-restoration a STATED known-limitation (#499)** — so streaming/operate chapters teach reality. This is itself a #496 exemplar (naming exactly what holds). Memory: `reference_aether_book`.

## 4. Rest of queue / next priorities (owner's call — nothing auto-started)
- **#499** (HRW-divergence / non-owner registry-reseat) — the natural next reliability item; the @Disabled pinned harness is the ready acceptance gate.
- Streaming-debt tail: **#431** (crash-durability), **#411** (multi-survivor/serializer), **#485** (forward-retry parity).
- rc4 tracks: dashboard (#494/#495 spec-first, needs #300/#226 API freeze first), GA-gate validation (#365 epic), #496/#497 audits.
- Book coordination is ongoing (see §3); relay product deltas as they land.

## 5. Gotchas (carry-over)
- **PULL FIRST** — design-stream pushes to release-1.0.0-rc3; rebase (batches are disjoint from jbct/ so conflict-free, but verify).
- **build-runner owns maven**; NEVER `mvn verify` with `HCLOUD_TOKEN` set; `env -u HCLOUD_TOKEN` for forge/e2e; `jbct.skip` handled by POM hierarchy (never `-Djbct.skip=true` for aether).
- **forge-tests vacuous-skip trap:** `mvn test` runs ZERO forge ITs (surefire skip=true) → false green. Use `mvn verify -Pwith-e2e -Dit.test=<Class>` (NOT `-Dtest=`, which failsafe ignores and runs the whole suite).
- **Agent report drops are CHRONIC** — on an idle-without-report, check the tree/logs/scratchpad before re-instructing; work is usually done, only delivery failed.
- **candidate tag** `v1.0.0-rc3-candidate` re-points after each substantial batch (force-push, watch Release CI) — the authoritative full-build-with-lint gate.
