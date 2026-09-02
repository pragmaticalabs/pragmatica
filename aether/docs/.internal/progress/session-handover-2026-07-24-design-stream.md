# Session Handover — 2026-07-24 (design stream / aether-clone)

> Continuation of `session-handover-2026-07-21-design-stream.md` (the #448 close + API-instability
> arc). This doc covers the **07-22 → 07-24** arc: the whole **#493 corpus burn-down** plus three
> more lint reconciliations shipped **as PRs** (new workflow), two stale tracker issues cleared
> (**#244**, **#377**), and a full **rc3 tracker↔code reconciliation**. Branch `release-1.0.0-rc3`,
> synced to origin.

## Process change — design stream now ships via PRs (not direct-to-branch)

All lint/design code this session went through **feature branch → PR → `release-1.0.0-rc3`** so
aether-main reviews + merges. aether-main merged fast (all within the session). Handover docs and
GitHub issue closes are still done directly. Rebase-as-you-go: as rc3 advanced under merges, PR
branches re-conflicted **only** on `jbct/CHANGELOG.md [Unreleased] Changed` (trivial — resolve by
removing the `<<<`/`===`/`>>>` markers, keep all bullets).

## Shipped this session — 4 lint PRs + doc PR, all MERGED

| PR | Issue | What | Owner ruling applied |
|----|-------|------|----------------------|
| **#500** | #493 | 5-rule corpus burn-down → 0 | see below |
| **#502** | ORD-01 | member-order reconciliation | records→execute; exempt private constants |
| **#504** | NAM-05 | test-naming relaxed to ≥2 segments | relax (not strict 3-seg) |
| **#505** | #443 | derive gate requires Q4 scoping | require-scoped |
| **#516** | #244 | doc reconcile (formatter-disabled) | — |

### #493 corpus burn-down (PR #500)
- **SEAL-02** (50 sites): 20 real named fixed-message causes → per-cause `enum Foo { INSTANCE }`
  (type names unchanged → permits/type-patterns stay valid); 30 `record unused()` placeholders
  **rule-exempted** (owner chose rule-side over churning the 136-site idiom). New FP-guard fixtures.
- **RET-08** (110 sites → 0): rule exemption extended `.or(null)` → also `orElse`/`compareAndSet`/
  `getAndSet` (distinctive JDK boundary adapters). `TypeMapper` type table Option-ified (40);
  `OutputFormatter.printQuery` given a private `Option<TableSpec>` core; ~29 JDK/framework-boundary
  + Jackson-view-DTO + provider-DTO nulls justified-`@SuppressWarnings`. ~85% of the corpus was
  JDK-boundary — same pattern that got the null-*compare* arm dropped in #451.
- **MUT-01** (2): param→local. **STY-09** (4): de-nested ternaries. **BND-01**: confirmed already
  dispositioned (both sites clear, severity ERROR).
- Verified: 78-module reactor compile SUCCESS; per-rule sweeps = 0.

### ORD-01 reconciliation (PR #502)
- **Use-case order was backwards.** #453 encoded `records→steps→factory→execute`; the book
  (`project-structure.md` numbered list + every worked example) and the corpus both use
  **`records→execute→steps→factory`**. Corrected.
- **Value-object relaxed to fit idioms:** static factory + accessors share one rank (serialization
  pairs like `toJson`/`fromJson`, private factory-helpers no longer flagged); **private** static-final
  constants exempt from constants-first (public/package-API first). New `FileTypeClassifier.isPrivate`.
- Residual cleared: `ApplyState` 2 file-local consts → `private`; `ArtifactDependency` 3 package-API
  sentinels hoisted. Corpus → 0.

### NAM-05 relax (PR #504)
- Strict 3-segment → **≥2 underscore segments** (`method_[scenario_]expectation`). Corpus **578→88**
  across 4 modules (88 residual = genuine single-word names; advisory WARNING).
- **Plugin test-source support already existed** (`jbct.includeTests` flag collects `src/test/java`) —
  the "add it" follow-up in #244/#451 was stale.

### #443 Q4 (PR #505)
- Derive `EntryGate.requiresNarrowScope` now includes **Q4** (consistency contract) — a `system`-scoped
  Q4 is `UNSCOPED`, matching Q3 sibling + all 4 golden sheets (which scope by data-class/path). SPEC
  annotated, +1 test, 89 tests green. **NB: GitHub #443 = jbct-derive (was CLOSED); the provisioning
  issue is #444 — don't confuse.**

## Stale tracker issues cleared

- **#244 CLOSED** — was a "landed-but-open": `build.sh` Step 2 already runs the combined `process`
  goal (format+lint, fail-on-ERROR) since **2026-06-13**; the 33 lint errors were cleared in the
  #489/#493 sweeps; verified **0 ERROR-severity findings** across 66 modules today. Deferred-debt
  doc reconciled in PR #516. (The 5091 *warnings* are a separate, out-of-#244-scope surface;
  `failOnWarning=false`.)
- **#377 CLOSED** — one-time tracker↔code reconciliation. Swept all 48 open rc3-milestone issues
  against `release-1.0.0-rc3` code (5 parallel by-area investigators). **Closed 3 landed-but-open:
  #350** (per-key serial queue), **#248** (segment-sealing writer), **#459** (image plumbing). Full
  note on #377.
  - **Finding:** the 2026-06-25 "~3× understated" has **already been reconciled** — only 3
    phantom-open found. The current milestone is a fairly *accurate* reflection of real remaining
    work. **15 are "partial"** (headline landed, named sub-item remains) so within-issue progress
    is further than the count shows.

## rc3 scope snapshot (post-reconciliation)

- **~43 open** in `v1.0.0-rc3` milestone (was 48; closed #244/#350/#248/#459/#377 this session).
- **No `blocking` issue is in rc3** — the perf/scale GA gate (#365 epic, #367 multi-community, #376
  enforced gate) is explicitly **rc4/GA**, not rc3.
- Long poles: **durable-entity** epic (#345; timers/workflow/saga/observability #351/#353/#354/#355
  still spec-only), **storage** (#349; GC/RemoteTier-S3/encryption-key/ReplicationBatcher #263 unwired),
  **cloud** (#463 epic; live multi-cloud e2e, orphan-cleanup #297, quota #298), and a fresh cluster of
  **installer/init bugs #509–#515** (filed 07-24, all reproduce).
- The `rc3` *label* is on 114 issues but that's broader than the milestone; a `v1.0.0-rc4` milestone
  (49) + `v1.0.0` GA (14) absorb deferred work.

## Cross-stream / book / config

- **Book reconciliations = 3 diffs handed to the user** for the `coding-technology` repo (has WIP +
  PR workflow, so its tree was NOT touched; diffs also copied to clipboard):
  1. `project-structure.md` value-object order (ORD-01 — reverses "patterns first": public consts
     lead, private impl consts at bottom).
  2. `chapter-summaries.md:192` test naming (NAM-05 — 3-seg → `method_[scenario_]expectation` ≥2).
  3. `book-arch-meta/NEXT-STEP-SPEC.md:77` Q4 scope annotation (#443 — keeps the canonical source in
     sync with the derive `SPEC.md` copy; "book wins on disagreement").
- **Project `CLAUDE.md` test-naming note** relaxed to ≥2 locally, but `CLAUDE.md` is **gitignored**
  (per-clone) → the main stream must update its own copy if it wants alignment.
- **`~/.m2`** now holds jbct built from current rc3 HEAD (all merged rule changes); aligned with the
  branch — the prior "ahead-of-branch" drift is resolved.

## Remaining design-stream queue

Owner rulings pending (each ready to prep with concrete options like #443's):
- **`[lint.layers]` aether adoption** — turn on the #452 layering rules (ARCH-01..04, corpus-clean) for `aether/**`.
- **Style score category** — a distinct score bucket for uncategorized style/naming/zone rules.
- **Transcription upstreaming** of the derive golden answer-sheets to `siy/derivation-artifacts`.

In-lane lint item surfaced by #377: **#401** naming-collision / homonym lint rule — genuinely open,
no such rule exists in jbct-lint. **#455** hard tier stays POST-GA (needs javac-tier / workspace-index).

## Operational notes

- **Lint gate = `mvn jbct:check -pl <module>` (or `:process`), NOT `mvn compile`** — the plugin is only
  in root `<pluginManagement>`; core aether modules don't add an active `<plugin>`, so `compile` never
  lints them (gives a false "clean"). Format drift: `mvn jbct:format -pl <module>`.
- Building jbct itself for a `~/.m2` refresh: `mvn -f jbct/pom.xml install -Djbct.skip=true -DskipTests`
  (the one sanctioned `-Djbct.skip=true`).
- API instability from the prior session did not recur meaningfully this session; build-runner was
  reliable for verification, jbct-coder for the batched conversions (checkpointed).
