# Aether Documentation Cleanup / Overhaul Plan — final step before public RC1

**Date:** 2026-06-11
**Author:** investigation synthesis (3 parallel doc-audit agents over `feature/stream-namespaces-impl` HEAD)
**Positioning:** the **last step** before the public RC1 release — executed *after* code freeze and after all functional RC1 tickets are closed, so documentation is reconciled to the **final shipped state** rather than a moving target.
**Companion docs:** `operator-surface-assessment-2026-06-11.md`, `design-completeness-assessment-2026-06-10.md`.

---

## 0. Preconditions & positioning

This plan assumes:
1. **Code is frozen** — the API surface, CLI commands, config keys, and management routes are final. Drift reconciliation (Phase 2) is a *one-time sync against frozen code*; doing it earlier wastes effort re-syncing.
2. **All functional RC1 gaps are closed** — the open feature/bug tickets (storage durability #248–254, streaming #260–267, resource-surface #268–283, operator-surface #285–313, etc.) are resolved, so docs describe what actually ships, including the *resolutions* (e.g. #290 security default, #313 trust model).
3. **Docs are the release gate, not a parallel track** — nothing else blocks; this is the final polish that makes the runtime publicly consumable.

**Calibration — this is NOT a rewrite.** The estate is 308 markdown files. Much of the user-facing core is already accurate and high-quality: the top-level README quickstart, `reference/management-api.md` (3952 lines, route-verified), `reference/cli.md` (2048 lines, verified), `slice-developers/getting-started.md` (1044 lines), `architecture/01-consensus.md`, the dual Apache-2.0/BSL-1.1 license mapping, and an exceptionally detailed code-grounded CHANGELOG. The work is **four bounded workstreams**: cut what shouldn't ship, consolidate structural duplication, reconcile a small set of drifted docs to frozen code, and write a handful of missing public-facing documents — then lock it with an anti-drift CI guard so it can't re-rot after launch.

---

## 1. Current-state findings (verified)

### 1.1 Estate shape
- **308–309 `.md` files.** No site generator anywhere (no mkdocs/docusaurus/antora/jekyll config). The only navigation hub is `aether/docs/README.md`, and it is **already stale**: it omits the entire numbered `architecture/` set, links the superseded `contributors/architecture.md`, and leaves ~40 files orphaned (unreachable from the hub).

### 1.2 What must NOT ship publicly (internal scaffolding)
- `aether/docs/internal/**` — **74 files**, including `internal/progress/` (**60 session-handover dev logs**), `internal/audits/`, `internal/design/`. Development scaffolding, not product docs.
- `aether/docs/archive/**` (15) — explicitly deprecated/superseded; keep out-of-tree or behind a "history" link.
- Per-module internal docs: `jbct/**`, `aether/pg-tools`, `tests/cloud`, `postgres-async/docs`, `slice-processor/docs`.
- Draft/unbuilt specs (see 1.4).

### 1.3 Structural duplication / collisions
- **`operator/` (3) vs `operators/` (8) vs root `runbooks/` (1)** — three operator locations; `operators/` is canonical (README-linked, has its own `runbooks/` subdir). The singular `operator/` is an accidental near-name-collision split. → **merge all into `operators/`.**
- **`contributors/architecture.md` vs `architecture/00-overview.md`** — two competing "Aether Architecture Overview" docs with identical H1. The numbered `architecture/00–13` set is newer, coherent, and cross-linked from specs; the contributors one points to archived vision docs. → **retire `contributors/architecture.md`; `architecture/00-overview.md` is the single overview.**

### 1.4 Spec / RFC / architecture estate (73 governing docs)
Refined status split (corrects the prior ~24/7/13 estimate — most "rotten headers" turned out to be *shipped specs that merely lack a status header*):
- **33 landed** (spec-of-record, maps to shipped code) — keep as reference; **~20 lack a status header**.
- **5 designed-only / unbuilt** — `hierarchical-storage-spec` (AHSE — **88 KB, "Implementation-Ready" header, ZERO implementation classes: the single most misleading doc in the repo**), `cloud-provider-digitalocean`, `declarative-http-client-spec`, `control-plane-delegation-spec`, `fluid-migration-spec` (partial). Plus RFC drafts 0002/0006/0007/0008/0010/0011/0012/0013.
- **5 superseded** — `membership-architecture-spec`→topology-rc1+fsm; `passive-worker-pools-phase2`→topology-rc1; `integration-test-overhaul` v1→v2; RFC-0009→0010.
- **2 confirmed rotten** — `architecture/12-management.md` (see 1.5); `dashboard-ui-spec` (drifted off API contract, ref #291–294).

**Header conventions are inconsistent (three incompatible styles).** RFCs have a clean uniform `Status:` line — adopt it everywhere. `aether/docs/architecture/` has **zero status headers** → no freshness signal on the most authoritative-looking layer.

### 1.5 Drift against code (the accuracy debt)
| Doc | Real drift (frozen-code truth) | Ticket | Sev |
|---|---|---|---|
| `architecture/12-management.md` | **RE-SCOPED:** REPL **is** real (`AetherCli.java:99,255`), WS endpoints `/ws/dashboard,/ws/status,/ws/events` **are** real (`ManagementServer.java:361-363`) — *keep these*. Actual fiction: `/api/v1/*` base-path (real surface is flat `/api/*`), `/api/aspects` (absent), Prometheus path is `/api/metrics/prometheus` not `/metrics/prometheus`, CLI `blueprint`→`blueprints` + no `aspects` command | **#310 (premise wrong — re-scope first)** | High |
| `architecture/10-security.md:143-145,219` | States external access **requires** API key + RBAC unconditionally; never mentions `SecurityMode.NONE` default (`SecurityMode.java:10-12`, `AppHttpServer.java:694`) | **#290 / #313** | High |
| `slice-developers/resource-reference.md` | Core `@ResourceQualifier`/`@Notify` syntax verified **accurate**; residual errors are the narrower retry / `factory.close` / interceptor-wiring specifics | **#283, #271, #277** | Med |
| `reference/configuration.md:283` | `Option.empty()` in example — compiles but violates project `none()`-only style | — | Low |

### 1.6 Missing for a public release (net-new docs)
| Missing | Audience | Sev |
|---|---|---|
| **`SECURITY.md` + documented trust model** — single-trust-domain / trusted-private-network assumption stated nowhere; `SecurityMode.NONE` default unwarned | operators, security reviewers | **Critical** (#313+#290) |
| **`CONTRIBUTING.md`** — absent at every level; no PR/fork/sign-off/CoC process | external contributors | High |
| **Versioning / compatibility / upgrade-policy** — no SemVer/compat-window/version-skew contract; only a procedural `rolling-upgrade.md` | operators, integrators | High |
| Security-default warning woven into getting-started + operator install | new users | High |
| Troubleshooting depth | operators | Med |

Already-open doc-related RC1 tickets to fold in: **#310, #283, #290, #313, #271, #277**.

---

## 2. Target information architecture (public set)

Adopt a single generated site (recommended **mkdocs-material** — cheapest for a pure-markdown estate; decision point in §5). Everything not listed is excluded from the public build.

```
docs/ (public root)
├── index            ← aether-overview + what/why (articles/aether-introduction)
├── get-started/     ← getting-started, first-slice quickstart, demos, install
├── develop/         ← slice-developers/* (REFRESH — public front door, currently a month stale)
│                       programming model · resources · lifecycle · testing ·
│                       persistence · pg-notifications · resource-reference · forge · faq
├── reference/       ← reference/* (slice-api, cli, configuration, management-api,
│                       feature-catalog) + guides/ merged in   [already strong]
├── operate/         ← operators/* + operator/* (MERGED) + runbooks (MERGED):
│                       install · bootstrap · scale · upgrade · backup/restore ·
│                       monitor · tls · networking · multi-cluster · troubleshoot · runbooks/*
├── security/        ← NEW: trust model · SecurityMode · hardening · cluster_secret hygiene
├── architecture/    ← architecture/00–13 (CANONICAL; add status headers)
├── contribute/      ← CONTRIBUTING + contributors/* internals (consensus, routing, lifecycle)
└── design/          ← OPTIONAL public: rfc/ + curated RC1/Approved specs only (decision §5)
```

**Excluded from public build:** `internal/**` (74), `archive/**`, draft/unbuilt `specs/**`, all per-module internal docs (`jbct/**`, `pg-tools`, `tests/cloud`, `postgres-async`, `slice-processor`).

---

## 3. Execution phases

### Phase 0 — Decide & freeze conventions *(small; do first — everything else lands against it)*
- Ratify the target IA (§2) and the explicit public-set boundary.
- Adopt the RFC `Status:` front-matter convention for **all** specs + architecture docs.
- Decide the site generator (§5 decision 1) and whether `design/` ships publicly (decision 2).
- Output: a short `docs/CONTRIBUTING-docs.md` (conventions) + the nav skeleton.

### Phase 1 — Cut & consolidate *(mechanical, low-risk, high signal-to-noise)*
- Move `internal/**` out of the public tree (to `.internal/` or out-of-repo).
- Merge `operator/` → `operators/`; root `runbooks/` → `operators/runbooks/`.
- Delete `contributors/architecture.md`; redirect references to `architecture/00-overview.md`.
- Move 5 superseded specs → `specs/archive/`; move 5 designed-only specs → `specs/future/` **each with a "NOT IN RC1 — design only" banner** (AHSE first — it is the highest-risk misleader).
- Regenerate the hub index from the file tree (eliminate the ~40 orphans).

### Phase 2 — Reconcile to frozen code *(the accuracy pass)*
- **Re-scope #310 first**, then fix `12-management.md` (only the `/api/v1` prefix, `/api/aspects`, Prometheus path, 2 CLI typos — preserve REPL/WS content).
- Rewrite `10-security.md` to state the real model: `SecurityMode.NONE` default, when/how to enable API_KEY+RBAC, link the new trust-model doc (#290/#313).
- Fix `resource-reference.md` retry/interceptor/`@Notify` specifics (#283/#271/#277); fix the `configuration.md` style nit.
- Add `Status:` headers to the ~20 header-less specs and all 14 `architecture/` docs; verify each "maps to code."
- Run the API/CLI/config cross-check (see Phase 4 guard) as a one-time audit and fix every documented identifier that doesn't exist in frozen code.

### Phase 3 — Fill release gaps *(net-new public docs)*
- **`SECURITY.md` + `security/` trust-model doc** (Critical): single-trust-domain assumption, private-network premise, `SecurityMode` behavior, hardening steps, `cluster_secret` at-rest hygiene (#287), how to detect an untrusted-network deployment (#313).
- **`CONTRIBUTING.md`**: fork/branch/PR/sign-off/CoC + the dual Apache-2.0/BSL-1.1 contribution implications.
- **Versioning & compatibility policy**: SemVer commitment, RC→GA stability contract, version-skew/rolling-deploy stance (app-owned vs runtime-owned, per existing scope decisions).
- Weave the security-default warning into `get-started` + `operate/install`.
- Expand troubleshooting.

### Phase 4 — Structure & anti-drift *(so it cannot re-rot after launch)*
- Stand up the site generator with the §2 nav; wire a docs build into CI.
- **Doc-lint CI guard** — the audit agents already prototyped the extraction: pull route literals, picocli command names, and config keys from code; **fail the build when a doc references an identifier that doesn't exist.** Add link-checking (orphans/dead links) and `Status:`-header enforcement on specs.
- **Review-on-API-change policy**: extend the existing "keep skills updated on API change" rule to docs — any PR that changes a route/CLI/config key must touch the corresponding reference doc, enforced by the guard.

---

## 4. Sequencing, effort, risk

**Dependency order:** Phase 0 → (1 ∥ 3 can run in parallel) → 2 → 4. Phase 1 (cut/consolidate) and Phase 3 (net-new docs) are independent and parallelizable. Phase 2 (reconcile) should follow Phase 0's header convention and ideally land with the Phase 4 guard so the reconciled state is immediately protected.

**Rough effort:** Phase 0 ≈ small (decisions). Phase 1 ≈ 1–2 days (mostly `git mv` + index regen). Phase 2 ≈ the bulk of the accuracy work but bounded (a handful of files + header sweep). Phase 3 ≈ 4 genuinely new documents. Phase 4 ≈ the highest-leverage investment (the guard is what prevents this whole exercise recurring).

**Risks:**
- **R1 — fixing #310 to its wrong premise** deletes accurate REPL/WS content. *Mitigation:* re-scope the ticket before touching the doc (Phase 2 opens with this).
- **R2 — designed-only specs leak into the public set** and misrepresent RC1 capability (AHSE especially). *Mitigation:* Phase 1 banners + `design/` gating decision.
- **R3 — drift returns the day after launch** absent a guard. *Mitigation:* Phase 4 is non-optional; treat the CI guard as a release deliverable, not a nicety.
- **R4 — `slice-developers/` (the public front door) is a month stale.** *Mitigation:* explicit refresh pass in Phase 1/Develop, not just a move.

---

## 5. Decisions — RESOLVED (2026-06-11)

1. **Site generator → mkdocs-material.** Cheapest for a pure-markdown estate; provides nav/search and a CI build to host the anti-drift guard.
2. **Publish `design/` publicly → yes, gated by status.** Public RFCs + curated specs, RC1/Approved only.
3. **Designed-only specs → move to `specs/future/` with "NOT IN RC1 — design only" banners.** Keeps roadmap intent visible without misleading public readers (AHSE prioritized; ties stream-durability epic #248–254).
4. **Ticketing → epic + per-deliverable children.** Filed (see §6).

---

## 6. Ticket map — FILED

- **EPIC #314** — RC1 documentation cleanup/overhaul (tracks this plan).
- **Children:**
  - **#315** Phase 1 — cut & consolidate (exclude internal/archive, merge operator dirs, retire dup overview, relocate non-RC1 specs, regen hub index)
  - **#316** Phase 2 — rewrite `10-security.md` for `SecurityMode.NONE` default (ties #290/#313)
  - **#317** Phase 2 — adopt RFC `Status:` header convention; sweep ~20 specs + 14 architecture docs
  - **#318** Phase 2 — one-time API/CLI/config cross-check vs frozen code
  - **#319** Phase 3 — `SECURITY.md` + `security/` trust-model section (Critical; ties #313/#290/#287)
  - **#320** Phase 3 — `CONTRIBUTING.md` (PR/fork/sign-off/CoC + Apache-2.0/BSL-1.1)
  - **#321** Phase 3 — versioning/compatibility/upgrade-policy doc
  - **#322** Phase 3 — refresh `slice-developers/` + weave security-default warnings
  - **#323** Phase 4 — mkdocs-material site + target IA + CI build
  - **#324** Phase 4 — doc-lint CI guard + review-on-API-change policy
- **Re-scoped:** **#310** (12-management — KEEP real REPL/WS docs; fix only `/api/v1`, `/api/aspects`, prometheus path, CLI typos).
- **Folded in (existing):** #283/#271/#277 (resource-reference accuracy), #290/#313 (security default + trust model → feed `security/`).

All labelled `rc1`.
