# Pragmatica / Aether — Session Handover

**Date:** 2026-04-08
**Branch (local checked out):** `release-1.0.0-rc1` (up to date with `github/release-1.0.0-rc1` @ `ebacb8fda`)
**Repo root:** `/Users/sergiyyevtushenko/IdeaProjects/pragmatica-store/pragmatica-clone`

## Purpose of next session

User wants to **assess feasibility of one feature** (feature not yet specified — will be provided at session start).

## Project identity (canonical names)

- **Pragmatica Core** — the functional library (was "Pragmatica Lite Core").
- **Aether** — Unified Application Runtime (was "distributed runtime").
- **Envelope format version:** `1000` (clean break from previous schemes).
- **Consensus:** Rabia (CFT, not Byzantine).
- **Thread pool:** `SharedScheduler` (project standard — do not introduce new pools).

## Recent merges (last ~2 weeks) on release-1.0.0-rc1

```
ebacb8fda feat: add aether-management-api module with route registry foundation
1697b0f20 docs: update changelog for LB binary fix and management port separation
d2117f749 feat: configurable management content limit on LB, default 2MB
56e273511 fix: LB sendResponse uses binary write to preserve byte content for artifacts
78960b485 fix: LB management API on dedicated port, disabled by default for security
00a5c0f91 feat: forward management API requests from LB to core nodes via QUIC
7cfe889c6 fix: preserve Syncing state in advancePhase to prevent sync cancellation under load
4959e55ff fix: CI build time — parallel build, disable slow tests, exclude examples (#130)
39f62d4da fix: cleanup leader wait 90s for post-restart convergence
8c3b5c705 fix: stop-remove-start cleanup prevents CTM re-provisioning race
5ebccebe3 fix: increase quorum hysteresis to 5s for restart stability
8326fcb2d fix: coordinate SWIM RemoveNode with hysteresis, use SharedScheduler
```

Note: **management API forwarding** landed — LB now proxies management requests to core nodes via QUIC, with a dedicated management port and a new `aether-management-api` module containing route registry primitives (ManagementRoute, RouteMatcher, RouteAssembler). This is relevant if the "one feature" touches control plane or routing.

## Open work

### Open PRs
- **#129** — `feat/naming-consistency`: docs naming consolidation (Pragmatica Core + Unified Application Runtime). Not yet merged.

### Open issues — rc1 milestone (feature-gating GA)
- **#73** — Cloud integration testing milestones
- **#77** — Per-route HTTP rate limiting
- **#108** — Deployment bundle assembly from external Maven dependencies
- **#118** — Schema migration failure recovery (auto-retry, manual retry, activation resumption)

### rc2
- **#119** — HashiCorp Vault secrets integration
- **#120** — Pre-baked cloud images for instant node boot

### post-ga (triaged, not blocking)
- **#82** — Slice development IDE plugins
- **#76** — Forge modular rework: remote clusters + Forge Script DSL
- **#107** — Cross-artifact deployment strategies
- **#117** — Config subscription: rollback, versioning, schema evolution
- **#123** — DigitalOcean & Vultr providers (Tier 2)
- **#124** — Cluster expense tracking via cloud billing APIs
- **#125** — Cross-slice distributed transactions (2PC) — design only

### core deferred (naming/API nits)
- #2 Thunk, #3 isOk/isErr aliases, #4 Result.filter order, #5 getOrThrow, #6 sequence naming, #9 Lazy extends Supplier, #10 Why Promise?

## Recent architecture investigations (context that may matter)

- **Quorum hysteresis (5s)** interacts with SWIM RemoveNode — coordinated via #8326fcb2d so they no longer race. Watch for re-emergence of reconnection bug if hysteresis is tuned.
- **Chaos tests** now tolerate auto-healed extra containers in initial node count; stop-remove-start cleanup prevents CTM re-provisioning race.
- **#7cfe889c6** — Rabia `advancePhase` now preserves Syncing state to prevent sync cancellation under load. Recent fragile area.
- **#125 (2PC)** — design discussed, not implemented. Non-trivial; interacts with Rabia and envelope version boundary.

## Correction documents prepared (not yet applied to website)

In `/Users/sergiyyevtushenko/IdeaProjects/pragmatica-store/`:
- `feature-catalog-corrections.md`
- `developer-guide-corrections.md` (note: @Subscription/@Scheduled/@Notify examples were wrong; SqlConnector API wrong)
- `getting-started-corrections.md` (port 8888, not 8080; `Verify.Is::present` wrong)
- `operator-guide-corrections.md` (backup `trigger`, not `create`; Hetzner secrets wrong)
- `reference-corrections.md` (@Codec is internal/auto-generated; 11 CLI groups missing)
- `homepage-corrections.md` (320K code-only lines is accurate; "500+ tests" understated — monorepo has 5,981)
- `naming-corrections.md` — feeds PR #129
- `ci-optimization-proposals.md`, `spring-boot-stats-report.md`

## aether-coder skill (keep in sync!)

- Primary: `~/.claude/skills/aether-coder/`
- Mirror: `/Users/sergiyyevtushenko/IdeaProjects/coding-technology/ai-tools/skills/aether-coder/`
- Structure: `SKILL.md` + `patterns/`, `resources/`, `deployment/` subdirectories.
- **Rule (memory `feedback_skill_maintenance.md`):** update this skill on every API/pattern change.

## User collaboration notes

- **Role:** Investigator. Explores, analyzes, diagnoses, designs. Does not write production code directly — prepares specs and reviews.
- **Style:** Extreme brevity. No preamble. Challenge mode always on.
- **Git:** Single-line commit messages. No Co-Authored-By. No body. Conventional prefixes.
- **Parallelism:** Use multiple tools / agents when independent.

## Known fragile / recently-touched areas

- LB management forwarding (just merged — watch for proxy edge cases)
- Rabia sync state (just fixed)
- Cluster restart / SWIM / hysteresis coordination
- Schema migration failure recovery (#118 — rc1 blocker, not yet designed)

## Next-session kickoff checklist

1. Read this file.
2. Read `MEMORY.md` index.
3. Ask user: "Which feature for feasibility assessment?"
4. Read any relevant corrections doc if feature overlaps with docs area.
5. For processor/runtime questions, check `aether-coder` skill first.
