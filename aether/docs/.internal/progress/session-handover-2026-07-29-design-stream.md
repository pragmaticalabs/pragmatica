<!-- SPDX-License-Identifier: BUSL-1.1 -->
<!-- Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko -->

# Session Handover — 2026-07-29 (design stream / aether-clone)

> Continuation of `session-handover-2026-07-24-design-stream.md`. Covers the **07-24 → 07-29** arc:
> the four remaining design-queue rulings resolved, **six issues filed and five fixed**, shipped as
> **9 PRs** (7 merged, 2 open). Branch `release-1.0.0-rc3`.

## TL;DR

1. **All four pending owner rulings resolved.** Three of the four items were mis-framed in the queue;
   the reframing was the deliverable in each case.
2. **Five issues filed from those investigations, then all five fixed.** Every one was mis-scoped when
   filed — always *understating* the problem.
3. **The recurring meta-defect: the same logic living in two places, one of them wrong.** Three of the
   five fixes were really deduplication. Worth grepping for proactively.
4. **`jbct score`'s 0-100 number was mathematically degenerate** and has been replaced by violation
   density. This is the largest change of the arc (#553, open).

## Part 1 — the four design-queue rulings

### `[lint.layers]` aether adoption → the premise was wrong
ARCH-01..04 were **already enabled and running** (`LintConfig.java:121-130`), not off. They were quiet
because with no `[lint.layers]` block, classification falls back to book conventions that match almost
nothing in aether — and **ARCH-03/04 are structural no-ops there** (0 `*UseCase` classes, 0 `usecase`
packages). Baseline was 1 finding across 1175 files.

The real defect: `globToRegex` compiled `com.example.core.**` to `com\.example\.core\..*` and matched
it fully anchored, so it **never matched the bare package** — a class in its own declared layer package
silently fell out of that layer. The canonical example in `LayerConfig`'s own javadoc had the bug. Two
duplicated copies existed (`LintContext`, `LayerClassifier`, the latter's comment already conceding it
"mirrors LintContext").

**Ruled:** fix the classifier, defer aether config. `**` now means zero-or-more segments, via one shared
`PackageGlob`. Measured: 37 bogus findings (32 ARCH-01 + 5 ARCH-02) → 0 under a realistic config;
aether baseline unchanged. **PR #531, merged.**

### Style score category → 42–48% of diagnostics were invisible
14 registered rules produced **no signal anywhere** — unlike unknown rule IDs, which at least warn.
Measured: 6317 of 13184 aether diagnostics (47.9%) and 1642 of 4498 jbct-self (36.5%) excluded from the
reported score.

**Ruled:** `STYLE` as a **weight-0 advisory** category — reported like any other, excluded from `overall`,
so the six principle categories still sum to 100 and every existing score stayed comparable. Neutrality
proven by building the pre-change jar in an isolated worktree and comparing over a byte-identical tree.
**PR #536, merged.**

### Transcription upstreaming → done, with a canonicity contract
The four published runs' schema-form sheets now live in `siy/derivation-artifacts/schema/`, fulfilling
that repo's own README promise. **jbct-derive stays canonical**; each mirrored file carries a
do-not-edit banner naming source path and commit. `living-system.toml` excluded (synthetic, no
corresponding prose run). **derivation-artifacts PR #1** (open, owner's repo) + **PR #537, merged**
recording canonicity on our side.

### #401 homonym rule → closed after measuring every operationalization
Not blocked on #455 as hypothesised — it is single-file expressible. The blocker is precision. Three
heuristics measured; the tightest (decl-vs-import, distance ≤2) gives **~1 true positive per 50–60
candidates**, and the only TP found across two corpora was the posterchild itself, in the repo chosen
*because* it contains it. Issue bullet (a) is also a provable no-op — unreachable in valid Java without
wildcard imports, 0 hits / 1461 files. **Closed with the full measurement trail.**

## Part 2 — issues filed and fixed

| Issue | Filed as | What it actually was | PR |
|---|---|---|---|
| **#540** | "find the offending pattern" | Per-character regex recursion in `MapperSafety.STRING_LITERAL`, plus a duplicate copy of the same bad pattern in `CstDiscardedResultRule` | #544 merged |
| **#541** | "add missing tests" | Also completed a half-done badge extraction that had left the SVG duplicated | #546 **open** |
| **#533** | "thread real counts out of the linter" | Denominator self-referential; score mathematically degenerate; "checkpoint" undefined everywhere | #553 **open** |
| **#532** | "add merge up the chain" | The merge was never per-key, and **silently reverts settings today** at two levels | #549 merged |
| **#534** | "fix ARCH-01's silence" | ARCH-02 shares it, ARCH-04 has the inverse, and the obvious fix would spam | #552 merged |

### #540 — `jbct score` died at default `-Xss`
`STRING_LITERAL`'s alternatives were `(?:A|B|C)*`; the JDK compiles a quantified group with alternation
to a `Loop` node that **recurses once per iteration**, each consuming one character. The 7481-char
scaffold text block in `SliceProjectInitializer` produced a 33 667-frame trace. Fixed with possessive
quantifiers (safe: every closing delimiter starts with `"`, excluded from the class). Regression tests
run on a **1 MB-stack thread** — the macOS main thread's 8 MB stack hides the fault entirely.

### #532 — a live bug, no chain required
`~/.jbct/config.toml` setting `failOnWarning = true` was **silently reverted** whenever a project
`jbct.toml` named a single rule severity: `fromToml` parses the absent key as `false` and rebuilds
`ruleSeverities` from DEFAULT, so the section differs from DEFAULT and gets taken *wholesale*.
Fixed structurally — `PartialConfig` with `Option` per key, fold nearest-wins, materialise defaults
once — then chain-merge on top, stopping at the first ancestor with a `.git` entry (**which is a *file*
in a git worktree, not a directory**).

### #534 — the cautious config was the worst config
ARCH-01 and ARCH-02 both go silent on an unclassified own-layer, so a *narrow* `[lint.layers]` config
enforces nothing while reporting clean (measured: adapter-only → 0 findings; + domain catch-all → 37).
The skip is correct; the silence is the defect. Fixed with a one-line per-run coverage summary, **gated
on an explicit layers config** — ungated it would fire for every project that never opted in, since
conventions-only leaves most packages unclassified.

### #533 — the score could not distinguish 10 violations from 2000
`checkpoints = (int)(violations * 1.1 + 10)` — derived from the numerator. Reproduced directly with N
`return null;` methods: old score **77 / 17 / 0 / 0 / 0 / 0** for N = 1/5/10/50/200/2000. In the wild it
ranked backwards: aether/node STYLE 528 findings scored **11**, jbct-lint 102 findings scored **16**.

Replaced with **violation density per KLOC** (physical non-blank lines), raw counts always alongside.
Deleted: `countCheckpoints`, severity multipliers, `ScoreCategory.weight`, the weighted overall, the
progress bar, the badge format. `advisory` became an explicit boolean (the weight deletion broke
deriving it from `weight == 0.0`).

**The gate was renamed, not silently inverted.** `jbct.score.baseline` meant "fail below"; density means
"fail above". Old names now hard-error with migration text. Free only because nothing configures it —
that window closes the moment something does.

## Also filed, not fixed

**#548** — `CstArchSliceInternalRule` (ARCH-04) maps an absent own-slice to `false`, so an unclassified
file stays *active* and flags references into any slice's internals. The inverse of #534: over-flagging,
not silence. Deliberately out of scope of #552.

## Merge state

**Merged:** #516, #531, #536, #537, #544, #549, #552. rc3 tip `700aa514d`.
**Open:** #546 (retargeted to rc3 when #536 merged; MERGEABLE), #553 (stacked on #546's branch — its
mergeable state resolves once #546 lands). **derivation-artifacts#1** awaits the owner.

## The pattern worth carrying forward

**Every one of the five issues was mis-scoped when filed, always understating.** Investigating before
implementing changed the fix in four of five cases, and in two (#532, #533) the filed fix would have made
things worse.

**Three of the five fixes were deduplication.** `globToRegex` (×2), the score renderer (×2),
`STRING_LITERAL` (×2), the badge SVG (×2), the per-run file loop (×3+). When a defect appears, grep for a
second copy before fixing the one you found.

**Silent-wrong-state is the dominant defect class in this codebase** — six instances this arc alone.
The tell is always the same: a value that looks authoritative while being disconnected from what it
claims to measure.

## Operational notes

- **Agent instability recurred** — three failures (two timeouts, one dropped connection). Recovery that
  worked every time: check the branch/worktree on disk rather than trusting the absence of a report. In
  two cases the surviving work was good and was resumed rather than restarted.
- **Task assignments cross with agent completions.** A recon agent received the same task four times as
  stale re-sends. State explicitly when a task is NEW versus a re-send, and name the delta.
- **`forge-tests` 30-minute timeout is an intermittent hang, not a failure** — confirmed twice (#531,
  #544): identical commit, red once, green on re-run in ~8 minutes. Signature is a teardown hang after
  SWIM/QUIC churn, then silence to the step timeout.
- **`build-and-test` can fail on testcontainers image pulls** (`ContainerFetch … testcontainers/ryuk`) —
  environmental, re-run (#549).
- **Never `mvn install` here** — `~/.m2` is shared with the main stream and a RELEASE-versioned install
  swaps their toolchain. In-reactor `mvn -f jbct/pom.xml test -pl … -am -Djbct.skip=true` throughout.
- **In-reactor verification scoped with `-pl` cannot see breaks outside that scope.** CI's full build is
  the real gate; treat a scoped green as partial.
- **Verification must be adversarial to mean anything.** Every fix's tests were run against the *pre-fix*
  code to confirm they go red (#540: 7/11 fail; #532: 13/19 fail). #533 additionally mutated six
  behaviours to confirm each had a sensor.
- Correction to the previous handover: it listed **#516** in a table headed "all MERGED" while it was
  still open. It has since merged. Verify state rather than trusting the record.
