# Session Handover — 2026-07-17 (design stream / aether-clone)

> Complements `session-handover-2026-07-17.md` (aether-main: rc2 shipped end-to-end, rc3 opened,
> ticket ledger reconciled — read it first for release/branch state). This doc covers the
> design-stream arc 2026-07-15 → 07-17: cloud-testing cost model, JBCT book↔linter audit +
> rc3 ticket set, #447 formatter fixes (executed here, PR #456), Aether positioning work, and
> the Landscape design (#462). Session memory `project-jbct-lint-audit-rc3` + `project-landscape-rc3-state` mirror the durable facts.

## Clone state
- On `release-1.0.0-rc3` (tracking origin), branched clean off the rc3 opening commits.
- **Local branches cleaned 2026-07-16**: 10 deleted (9 topic branches + stale local `release-1.0.0-rc2`),
  each verified content-landed by *file-level comparison against main* — ancestry checks are useless
  because rc2 was squash-merged (and later history-rewritten). **KEPT: `archive/s20-observability-2026-06-27`**
  — `a6baf30fd` (~560-line S20 observability surface) remains unmerged salvage; triage still parked.
- Origin side: 5 merged remote branches deleted same day (design/*, feat/241-worker-governor,
  feat/277-observability-aspects, fix/447-formatter-fixes).
- `feat/277-observability-aspects` note: its two never-merged files (`ObservabilityAspect.java`,
  design-state doc) are relics of the superseded wrapper-codegen approach — gone from origin too now; recoverable only from reflog if ever wanted.

## Arc 1 — Cloud-testing cost model (feeds aether-main's pending "rc3 = major-cloud support" decision)
Baseline correction: `$TARGET_HOST` is a **local notebook** ($0) — the entire Hetzner bill (<$30/mo)
is ephemeral cloud-test compute. Model (July 2026 prices): per-server-hour 4vCPU/8GB ratios vs Hetzner —
OCI ~2-3× (A1 ARM $0.052/h, 10TB free egress, Always-Free host option), DO ~3-5× ($0.071/h, per-second
billing), GCP ~4-7× (fixed-price spot $0.032/h), AWS ~5-10× (Graviton+spot ~$0.04/h), Azure ~7-11×
(deepest spot, highest eviction). **Key findings**: (1) ephemeral SPI-conformance testing is $5-20/mo
per cloud even on-demand — full multi-cloud validation ≈ $30-80/mo total, NOT $500+; (2) spot is the
honest default (tests are interruption-tolerant by design); (3) the real cost risk is **non-VM leaks**
(one ALB $20/mo or NAT gateway $32/mo > a month of legitimate compute) → per-cloud reaper equivalent +
budget alerts are prerequisites, not nice-to-haves (OCI uniquely has hard caps); (4) keep test clusters
single-AZ (cross-AZ consensus chatter is billed on AWS/GCP; Hetzner's 20TB hid this class). Fits
aether-main's Tier-1/2/3 recommendation; also seed data for #202 (expense tracking). Chat-only —
write up as a doc on demand.

## Arc 2 — JBCT book↔linter audit → tickets #447-#455
- **Canonical-source gotcha**: `coding-technology/website/course/jbct/` = lesson stubs only; the
  prescriptive text is the book manuscript `coding-technology/book/*.md` (24 chapters). 74 checkable
  rules vs 41 implemented lint rules: ~18 covered, ~13 partial, ~43 unchecked.
- Filed: **#448** method-shape classifier (census→flag→verb↔shape cross-checks; absorbs PAT-02/ZONE-03/
  NEST-01 string heuristics; feasible BECAUSE JBCT shapes are structurally distinct — unclassifiable
  syntax IS the violation) · **#449** ScoreCalculator keyed to retired rule IDs (all diagnostics fall
  to PATTERN_PURITY default — ScoreMojo currently meaningless) · **#450** SLICE-01 config-only no-op ·
  **#451** easy-tier batch (8 rules; boundary-types ban is the surprising gap) · **#452** layering
  engine (generalizes MIX-01) · **#453** file-type classifier + structural rules · **#454** fixture
  coverage 4/41 + registry invariant · **#455** hard-tier tracking (post-GA; javac-tier unlock via
  slice-processor symbols). Book normalization item: test-name schema stated two ways in manuscript
  (standardized `methodName_scenario_expectation`; basic-patterns chapter needs the fix — book repo).
- Linter infra facts (for #448 work): custom PEG "v6" parser → flat int[] CST, syntax-only,
  single-file, no type resolution; rules self-drive DFS; registration hardcoded in `CstLinter.defaultRules()`.

## Arc 3 — #447 formatter fixes (executed in this stream; CLOSED — sweep landed as rc3 opening commit)
Root causes for posterity: (1) import order defined twice, both contradicting the book → shared
`ImportGroups` classifier in jbct-core, book order `java → javax → org.pragmatica → third-party → project`;
(2) chain glued after wrapped-args head call: `FlowPrinter` ≥2-follow-up special case + `postBrokenArgsAnchor`
— removed so the single-follow-up rule (break before first follow-up, anchor at head dot) applies
uniformly; the `MultilineArguments.chainedWithArgs` golden fixture had CANONIZED the bug; (3) statement-
position chains never broke (`shouldBreakChain` purely structural, width-blind) → width-aware fallback;
(4) dead align config removed. Evidence: 204 occurrences in `../ticketing`. PR #456 merged by
aether-main pre-history-rewrite; 826-file sweep = `a961a003f`.

## Arc 4 — Positioning ("what is Aether")
Framework: **anchor + twist + proof**; define by boundary, not category — *"everything between your
code and the VMs — in one binary. Including managing the VMs."* Audience anchors: OTP (systems),
"the app server, finished" (JVM veterans), self-hosted-serverless-with-state (app devs). **The
credibility move**: pre-empt the location-transparency objection — CORBA/EJB failed by HIDING the
network; every slice boundary returns `Promise<T>`, so failure is in the type signature (this is why
JBCT + runtime ship together). LinkedIn rc2 announce drafted in-session (boundary pitch, failure-
survival framing, $30 line) — **publication pending owner**; suggest updating for "Maven Central live".
"Harvest the anchor": collect 'so it's like…?' replies from real architect conversations before fixing
the category. `aether-overview.md` rewrite offered, not done.

## Arc 5 — Landscape (#462, rc3, spec embedded in ticket)
Versioned application-set template: landscape = GAV artifact of blueprint pins; **the landscape declares
which applications exist; the cluster decides how they run.** Axioms (owner, 2026-07-17): apps
independent, NO ordering/DAG (cross-app interaction = durable streams only; transport absorbs deploy-
order gaps) · NO shared resources/runtime deps (stance: blueprint = atomicity+ownership boundary) ·
environment-free (cluster bootstrap owns env; same landscape → dev/staging/prod) · **counts are state,
bounds are policy** (blueprint `instances` = seed at first deploy only, KV `SliceTargetValue` authoritative
after; maxInstances/thresholds declarative, updated on apply) · per-blueprint atomicity only, sequential
stop-on-first-failure, **re-apply IS the resume** (idempotent) · `--prune`-gated removal · drift reported
never corrected. Design: `LandscapeKey/Value` + leader-side LDM (sibling to CDM); REST/CLI/docs triad.
Naming: `landscape` (EA term of art; `enterprise` rejected — future commercial-tier collision;
Canonical Landscape collision accepted as minor). **Step 0 = land spec as `aether/docs/specs/landscape-spec.md`.**
**Companion check**: verify cross-namespace stream subscription exists (`BlueprintNamespace` is
blueprint-scoped) — if not, axiom 1 is vacuous and the gap needs its own ticket.

## Where the next design-stream session naturally starts
1. `git pull --rebase origin release-1.0.0-rc3` first — aether-main is active; candidate tag
   `v1.0.0-rc3-candidate` must exist before ANY cloud run (their handover, gotchas section).
2. Implementation candidates held here: **#448 classifier census** (we hold the full linter-infra trace),
   **#449** score rebucket, **#451** easy-tier batch, **#462 step 0** (spec file + companion stream check).
3. Support aether-main's major-cloud decision with the Arc-1 cost model (doc it if the decision lands).
4. Parked: archive/s20 salvage triage; LinkedIn announce publication; aether-overview rewrite.
