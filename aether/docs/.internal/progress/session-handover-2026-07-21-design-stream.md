# Session Handover — 2026-07-21 (design stream / aether-clone)

> Continuation of the 2026-07-18→19 lint-track session. Read
> `session-handover-2026-07-19-design-stream.md` first for the full queue history
> (10 tickets closed, the corpus-validation-first protocol, the #489 saga). This doc
> covers the 07-20→21 arc: **#448 method-shape classifier phases 2 & 3** (the last open
> queue item, now fully closed), the **persistent API-instability adaptation**, and a
> **repo-wide test census**. Nothing here is unpushed; branch `release-1.0.0-rc3`.

## #448 — closed, all three phases (was the last open queue item)

Registry **41 → 65 rules** across the whole session. Phase 1 was in the prior handover.

- **Phase 2 (46cd6f14e) — reach + a latent-bug find.** (1) *Preamble reach*: `MethodShapeClassifier`
  no longer bails on any multi-statement body; a body whose leading statements are all skippable
  preamble (pure local decls — mutation-signal-guarded — narrow `if(…)return/throw` guards, or a
  single logger call) classifies by its composition-root tail. (2) *Latent `extractSpine` bug*: the
  v6 grammar folds a dotted call target into the PRIMARY leaf (`valid.map(f)` → `PRIMARY[valid.map]`
  + `POST_OP[(f)]`), so **every variable-receiver 2-step chain mis-read LEAF instead of SEQUENCER —
  silently corrupting even the phase-1 census.** Recovered the absorbed segment as the chain's first
  link. Re-census: UNCLASSIFIED **5336 → 3832**. **Verdict: the ticket's <5% promotion gate is NOT
  reachable on the aether corpus** — the residue is genuinely imperative code (loops, multi-branch
  side effects), a corpus fact. SHAPE-02 stays census-only / default-disabled.
- **Phase 3a — absorption (e6007ed06).** Built the lambda-argument descent primitive
  (`chainLambdaLinks` + `classifyLambdaBody`) the classifier deliberately lacked. Folded the three
  string-heuristic rules into facets: **PAT-02** → true structural facet; **ZONE-03 / NEST-01** →
  *masking-only* (their exact detection kept but run over `blankNonCode`-masked text) — a deliberate
  call, because full structural re-classification would REGRESS (drops single-combinator re-chains
  `inner(x).map(f)` which classify LEAF). Corpus **byte-identical** (ZONE-03 296, NEST-01 202,
  PAT-02 0; zero added/dropped) — pure consolidation + a latent FP safety-net, no cross-stream impact.
  Bonus: PAT-02 gained the `shouldLint`/`excludePackages` gate its siblings had; `monadicOpCount`
  precompiled.
- **Phase 3b — the one new cross-check (97ebf39bb).** `JBCT-SHAPE-03` shape↔zone-verb (flags
  impl-verb-on-SEQUENCER/FORK_JOIN and orchestration-verb-on-LEAF mis-leveling). Corpus gate: **622
  hits** (~460 the expected orchestration-verb-on-LEAF one-liner noise) → **default-disabled,
  census-on-demand like SHAPE-02.** The other two enumerated cross-checks were NOT built, documented:
  shape↔2-5 redundant (1-link chains already classify LEAF), shape↔return-type too noisy (ITERATION
  terminals mix collection/scalar).

## Operational note — persistent API instability (07-20)

Agent stream timeouts were severe and worsening this session: **coder-448-2 died twice** (but got far),
then **three consecutive coders died producing NOTHING** (coder-448-3, -3b, and one retry). Consulted
the user → "retry delegation." What worked:
- **Aggressive checkpointing**: instruct the coder to finish + report Step 1 (the primitive) before
  Step 2 (the facets), and report after EACH rule. coder-448-3c survived this way, banking each rule.
- **Direct takeover for small/verification work**: I ran verification via `build-runner` (reliable),
  diffed corpus hit-sets myself, and hand-made the small fixes (precompile, shouldLint gate, SHAPE-03
  disable, doc nits) rather than risk another coder death.
- The dead coder-448-3 left a useful `CorpusMeasure.java` harness (used for the delta gate, then
  removed before commit — a throwaway, not shipped).

If instability persists next session: default to build-runner for verification, small edits direct,
and only delegate large builds with mandatory per-step checkpoint reports.

## Repo test census (07-21, static declared-method counts)

JUnit `@Test`/`@ParameterizedTest` methods across `src/test`:

| Component | Test methods | Test files |
|---|---:|---:|
| Pragmatica Core (`core/`) | 828 | 31 |
| Integrations (`integrations/`) | 2,590 | 236 |
| JBCT (`jbct/`) | 1,096 | 123 |
| Aether (`aether/`) | 6,204 | 614 |
| **Repo total** | **10,718** | **1,004** |

Aether **integration** tests (shell, docker-compose, main-stream/docker only): **17 suites · 59
`test-*.sh` scripts · ~362 test cases (`run_test`)** + ~360 `assert_*`. (Note: an `it`-style
convention appears 89× in some suites — not summed with `run_test` without checking overlap.)

Caveats: declared-method counts (runtime "tests run" differs via parameterized expansion / `@Disabled`).
Runtime reference points seen this session: jbct-lint module 634; the 12-module aether RET-06 reactor
subset 3,545.

## State + remaining work

- Everything committed + pushed; `~/.m2` current (SHAPE-02/03 default-disabled, all final severities).
  MAILBOX.md up to date; no open cross-stream dependencies from this side.
- **Open, optional/future**: **#493** corpus debt (all WARNING — SEAL-02 ~50, RET-08 null-arg ~90-120,
  ORD-01 25, STY-09 4, MUT-01 2, ARCH-02 1, NAM-05 plugin test-source support; the burn-down touches
  aether/** → needs an owner override like RET-06 did). **#455** post-GA hard tier (+ the RET-08
  flow-aware null-compare successor). SHAPE-02/03 census tools are enable-in-config.
- **Owner rulings still pending** (from prior handover, non-blocking): Q4 per-data-class scoping (#443),
  transcription upstreaming to derivation-artifacts, a possible Style score category, `[lint.layers]`
  aether adoption.
- **Book-repo reconciliation items**: ORD-01 use-case member order (ticket vs book table), NAM-05
  naming-schema normalization.
