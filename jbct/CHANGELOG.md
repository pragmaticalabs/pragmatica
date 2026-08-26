# Changelog

## [Unreleased]

### Fixed
- Linter: **framework-shape false positives eliminated across four rules** (#645, #647) — driven by the ticketing suppression census (54 tokens audited on a conforming corpus, zero hiding real defects). JBCT-ORD-01 no longer ranks a method-local record as a type member (the Aether slice-impl record is declared inside its own static factory, so no TYPE_KIND separates it from the interface and it could never sort before the method containing it — 24 of the corpus's 53 tokens, unsatisfiable in user code; fixed via a shared `isLocalDeclaration` primitive extracted from CstValueObjectFactoryRule). JBCT-SEQ-01 no longer counts a local type declaration statement as a chain (its whole body's depth-0 dots summed onto the declaration line — an 80-"step" chain reported on a real slice; nested statements were already independently visited, so this is a filter, and the previously-masked GENUINE method-level chains now fire — the reporter's probe is the fixture, its control chain pinned at 7; switch/ternary arm summation is the deliberately split class-2/3 follow-up, pinned at today's wrong counts so the split cannot be forgotten). JBCT-VO-01 recognizes a pub-sub fact record by its in-file `Topic<Self>` constant (`Topic<OtherType>` still fires). JBCT-UC-02 exempts the fact-consumer shape (sole entry method's parameter carries a user-defined qualifier annotation) and excludes the Scheduled-contract's qualified zero-param `Promise<Unit>` hook from the entry count — qualifier recognition is by exclusion list since qualifier annotations are user-defined and a single-file linter cannot read meta-annotations; the residual failure mode is an FN on a WARNING rule, and the census re-run is its sensor. Audit deliverable from #645 shipped with the fix: every member-scan caller classified for the method-local hole; two residual findings filed as #655 (ORD-01 ranks anonymous-class methods — no TYPE_KIND at all; latent isStepInterface suppression). jbct-lint 756 → 775 green; five mutation checks each turn their guard's tests red

### Changed
- CLI: `jbct --version` now reports the **build timestamp** next to the release version — `1.0.0-rc3 (built 2026-08-21T04:45:23Z)`. The version string alone never identified a build: every jar built from a release branch reports the same `1.0.0-rc3`, so an installed jar months old is indistinguishable from one built from the working tree a minute ago, and `jbct upgrade`'s staleness check (a `Version.get()` comparison) reads same-version drift as up to date. That cost real hours. **#620** reported `JBCT-BND-01`'s origin fix as passing in unit tests but failing through the CLI, and named `LintContext` as the suspect; the findings in fact came from an installed jar dated 2026-07-30 sitting first on `PATH`. Measured, not argued: the stale jar reproduces the issue's output byte-for-byte at both coordinates (`4:68`, `13:18`), while the branch the issue names does NOT reproduce once its own jar is built and run — with a `java.util.Optional` positive control on every binary, so no clean result is vacuous non-reproduction. `LintContext.defaultContext()` and the context `LintCommand` builds are in fact behaviourally identical absent a `jbct.toml` `[lint]`/`[layers]` section (`conventionsOnly()` IS `from(LayerConfig.DEFAULT)`; `defaultConfig()` returns the same instance `JbctConfig.DEFAULT` carries; `fileName` is never read in a decision by any rule; `excludePackages` can only suppress). #620 closed not-a-bug. The stamp rides the already-filtered `jbct-version.properties` via `${maven.build.timestamp}` — no new build plugin, and the format property is a pin against Maven's default rather than a change to it. `Version.get()` is untouched (it names the release, and `UpgradeCommand` compares against it); the new `Version.full()` is what `--version` renders. Deliberately NOT a git SHA: that identifies which commit rather than merely when, which is strictly more useful, but needs a plugin this repo has no precedent for — the timestamp alone separates the two builds this failure mode confuses. `VersionStampTest` is mutation-checked against all three ways the mechanism can fail silently: dropping the `buildTime=` line (RED, resolves to `unknown`), disabling resource filtering (RED, leaves the literal `${maven.build.timestamp}`), and reverting `VersionProvider` to `Version.get()` (RED on that method alone, the other two green — the surface and the value are pinned separately because a jar can ship the right value through the wrong call site)
- Parser: peglib **0.7.2 → 0.7.3**, and the committed `Java25*` sources regenerated with it. The Java grammar deliberately does NOT adopt 0.7.3's `%nest` directive — Java block comments do not nest, so declaring the pair would change the language's meaning; `%nest` is used only by `postgres.peg`, where PostgreSQL requires it (#619). Regeneration is NOT byte-identical this time, unlike the 0.7.2 bump, and the difference is worth knowing: the generator now stamps a provenance header (`// peglib-generator: 0.7.3 (build:767ffbbe347d)`) — which alone accounts for the whole `Java25Visitor` diff — and **token KIND identity changed**, with `KIND_INLINE_STATIC` and `KIND_INLINE_SYNCHRONIZED` replaced by `KIND_STATICKW` / `KIND_SYNCHRONIZEDKW` and the `KIND_INLINE_*` block renumbered, i.e. literals that were anonymous inline literals now resolve to named keyword kinds. `regen_rulekind.py` reports a **zero delta**, since `RULE_TABLE` is unchanged and that is what `RuleKind` derives from — but a zero delta there does not cover which kind a keyword TOKEN arrives under, and the recorded lesson from the 0.7.2 migration is precisely "do not dispatch on keyword kinds". So the claim was checked against the corpus rather than the structure: a differential lint over 2161 aether files with a 0.7.2-built and a 0.7.3-built jar, same corpus and same invocation on both sides, is **identical** — 14186 findings, 0 lost, 0 new, no per-rule change, 0 parse errors either side, with the two jars verified to differ so the run was not an accidental no-op. jbct reactor: 1481 tests, 0 failures
- Parser: peglib pinned to **0.7.2**, and the version property renamed `peglib.maven.plugin.version` → **`peglib.version`** — it always governed both the runtime dependency and the generator plugin, which are released together, while its name said otherwise. Upstream 0.7.2 is `%import` composition, plugin import resolution and a single-flight parser cache, none of which touches the Java grammar; that was verified rather than taken on trust. Regenerating produces a **byte-identical `Java25ParserV6` and `Java25Visitor`**, and a `Java25Lexer` differing only by a **pure permutation of the 54 keyword-map entries** — sorted `r0.put(...)` sets identical, and not one non-`r0.put` line changed. `regen_rulekind.py` delta is zero, regeneration is deterministic across runs (worth re-checking now that generation is fork-join and jbct commits generated sources), the formatter goldens are green, and the differential corpus run over 2135 aether files is **identical: 14236 findings, 0 lost, 0 new, no per-rule change**. One trap for the next bump: the first corpus comparison showed 392 lost / 420 new, which was not the parser at all — the baseline predated a merge from the release branch, so the corpus itself had grown. Rebuild both sides against the same tree
- Score: the 0-100 **compliance score is replaced by violation density** — violations per 1000 physical non-blank lines, unbounded, lower-is-better (#533). The score was degenerate by construction: `ScoreCalculator.countCheckpoints` derived the denominator from the numerator (`checkpoints = (int)(violations × 1.1 + 10)`), so `category_score = 100 × (1 - weighted/checkpoints)` collapsed — a WARNING-only category asymptotically approached `100 × (1 - 1/1.1)` = **9** for any violation count, and an ERROR-only category **saturated at 0 by the tenth violation**. Measured with the pre-change jar against synthetic trees of N `return null;` methods, the old `null_safety` category reported **77 / 17 / 0 / 0 / 0 / 0** for N = 1 / 5 / 10 / 50 / 200 / 2000 — 10 violations and 2000 violations were the same number; density reports **166.7 / 277.8 / 303.0 / 326.8 / 331.7 / 333.2 per KLOC** for the same trees, converging on the true rate instead of the floor. No definition of "checkpoint" existed anywhere in the code, the specs or the book; the concept and `countCheckpoints` are gone. Before/after over four real corpora (old jar vs new jar, byte-identical trees): jbct-lint `21/100` → **24.5/KLOC** (209 violations, 8518 LOC, 78 files), aether-stream `73/100` → **14.0/KLOC** (187, 13354, 87), aether/node `42/100` → **27.9/KLOC** (778, 27867, 129), pragmatica core `30/100` → **54.8/KLOC** (791, 14424, 61). The compression the score hid is visible in the same run: aether/node's STYLE bucket holds 528 findings and scored **11**, jbct-lint's holds 102 and scored **16** — 5.2× the findings, 5 points apart — where the densities are 18.9 and 12.0/KLOC. **Denominator**: LOC is physical non-blank lines (`SourceFile.nonBlankLines()` — comments, annotations and braces count; only empty/whitespace lines are dropped), gathered in the *same* sweep as the diagnostics by a new shared `SourceScan`, so numerator and denominator can never come from different file sets and the CLI and the `jbct:score` goal cannot drift on which files they counted; a file that fails to read or parse is reported and contributes to none of the three numbers, since counting its lines would dilute every density. **No hidden judgement survives**: `ScoreCategory.weight` / `weightFraction` and the `ERROR × 2.5` / `WARNING × 1.0` / `INFO × 0.3` severity multipliers are deleted, the total is a plain sum rather than a weighted average (densities share one denominator, so the total *is* the sum of the counted category densities — verified as an invariant in tests), and ERROR/WARNING/INFO are reported as raw counts beside each density so severity stays a fact rather than a coefficient. `advisory` is now an explicit boolean on `ScoreCategory` instead of being derived from `weight == 0.0`, and advisory categories (`STYLE`) are **excluded from the total and reported separately** below it, so style findings cannot inflate the headline. **A ratio is never printed alone**: every density carries its raw counts, and the header carries the LOC and file count every density divides by — a single violation in a 90-line module really is `11.1/KLOC`, and hiding that denominator would make the report actively misleading. The terminal box is sized to its widest row (a fixed width would eventually overflow its border or truncate a count) and the 20-char progress bar is gone, being meaningless without a [0,100] ceiling. **JSON is a breaking shape change** (pre-GA): `{"linesOfCode", "filesAnalyzed", "breakdown": {"<category>": {"densityPerKloc", "violations", "errors", "warnings", "info", "advisory"}}, "totalDensityPerKloc", "totalViolations"}` — `totalViolations` is included so the headline ratio also obeys the never-a-ratio-alone rule. This supersedes the badge-renderer consolidation recorded below: the badge is deleted outright (see Removed). Coverage: new `ScoreCalculatorTest` (14 tests: arithmetic, one-decimal rounding, small-N, zero-LOC, the two ex-degeneracies, severity-is-counted-not-weighted, advisory exclusion, total-equals-sum), `SourceScanTest` (8: LOC definition, unreadable/unparseable exclusion), `DensityGateTest` (4), rewritten `ScoreReportTest` (18) / `ScoreCategoryTest` (3) / `ScoreCommandTest` (15) / `ScoreMojoTest` (8); `RuleCategoryMappingTest` moved onto the new API. Every claim mutation-checked: inverting the gate comparison (8 failures across all three surfaces), dropping the JSON entry separator (8, seven of them real jackson parse errors), silently ignoring the removed `--baseline`/`jbct.score.baseline` (3), dropping the per-KLOC scale (8), folding advisory into the total (3) and hiding the LOC denominator (6) each turn the suite red
- Score: the **badge** renderer moved into `ScoreReport` as well (`badgeLines` + a `BadgeColor` threshold table), so no score output is rendered inside `ScoreCommand` any more — the CLI now only routes `--format` to the shared renderer. The badge was the last surviving copy of the pattern that produced this whole cleanup: the SVG template and the five-band colour logic sat in the command, where nothing tested them. Output is **byte-identical** for every score the calculator can produce — proven by compiling the pre-change `ScoreCommand.outputBadge` straight out of git alongside the new path and comparing the emitted bytes for all 101 scores (0-100), 814 bytes each, zero differences (the trailing blank line the old single `println` of the text block produced is preserved deliberately, so a redirected `badge.svg` keeps its final newline). The relocated colour logic was also a `JBCT-STY-09` nested-ternary violation that jbct's own `jbct.skip=true` build never saw; it is now an ordered `BadgeColor` band table (`brightgreen` 90+, `green` 75+, `yellow` 60+, `orange` 50+, `red` catch-all), which reads as the threshold table it always was and is testable band by band (#541)
- Config: `ConfigLoader` now merges **every** `jbct.toml` from the repository root down to the working directory instead of only the nearest one, folding them nearest-wins per key. The walk terminates at (and includes) the first ancestor holding a `.git` entry — a directory in a normal clone, a file in a worktree — so a stray `jbct.toml` in `$HOME` or in a parent workspace can never join the chain (it previously climbed to the filesystem root, harmless only because it stopped at the first hit). `--config` stays the final layer and, under a real per-key fold, finally becomes the genuine per-key override `ConfigLoader`'s javadoc has always claimed rather than a wholesale section replacement. Nested project configs now inherit instead of shadow: `examples/*/jbct.toml` previously discarded the root `[files].excludes`, and now receives them. **API**: `JbctConfig.merge(Option<JbctConfig>)` is removed (it *was* the section-level merge) and `ConfigLoader.findProjectConfig` is replaced by `findProjectConfigChain`, returning the whole outermost-first chain — under chain semantics "the" project config is no longer a single file, and keeping a nearest-only lookup alongside a boundary-limited walk would be two contradictory search rules in one class. `JbctConfig.fromToml` is unchanged and still materializes a single document against the built-in defaults (#532)
- Score: new **`STYLE` advisory category (weight 0)** ends the silent exclusion of 14 registered rules. `RuleCategoryMapping` partitioned the 65-rule registry into 51 categorized rules plus an explicit `UNCATEGORIZED` set of 14 (`STY-03/04/06/07/08/09`, `ORD-01`, `STATIC-01`, `LOG-01/02`, `NAM-05`, `ZONE-01/02/03`) that produced **no signal anywhere** — unlike genuinely unknown rule IDs, which at least warn once per run (#449), these were dropped from every category *and* from the report: over the full source trees, **6317 of 13184 aether diagnostics (47.9%, 2065 files) and 1642 of 4498 jbct-self diagnostics (36.5%, 448 files) were invisible in the reported score**, dominated by `STATIC-01` (2272 aether / 715 jbct-self), `STY-06`, `ZONE-02/03` and `NAM-05`. A user saw a number and could not tell that nearly half the findings had not been counted. All 14 now map to `ScoreCategory.STYLE` and the `UNCATEGORIZED` set is **gone** — every registered rule has a real category, so the mapping is a total function on the registry (the partition test now asserts `MAPPING.keySet()` equals the live registry exactly) while the unknown-rule warn path is untouched and still fires for unregistered IDs. STYLE carries weight 0, so the six principle categories still sum to 100 and **every existing score is unchanged**: verified before/after with the built CLI on five corpora — aether/node 42→42, aether/cli 51→51, aether-stream 73→73, aether-deployment 65→65, jbct-lint 21→21; independently re-verified by building the pre-change jar in an isolated worktree and running both jars over a byte-identical tree (full-tree aether 4→4, jbct-self 4→4, with STYLE reporting 9% and 10% respectively alongside the unchanged six). `overall` is now summed over `ScoreCategory.weightedCategories()` explicitly rather than relying on a zero multiplier, and the `jbct.score.baseline` gate (CLI `--baseline` and the Maven goal) keys on `overall` only, so it is unaffected
- Score: a 7th number that silently does not count would be the same defect in a new place, so both output surfaces mark advisory categories explicitly, and the terminal box moved into one shared renderer (`ScoreReport` in jbct-core, byte-identical output, unit-tested) so the CLI and the `jbct:score` goal — previously copy-pasted duplicates — cannot drift apart on it. **Terminal:** advisory categories are separated below a divider, tagged `STYLE (advisory)`, and the box closes with `(advisory) — weight 0: reported for visibility, NOT counted in the score above`. **JSON (breaking, pre-GA):** each `breakdown` entry is now an object instead of a bare number — `"style": {"score": 11, "weight": 0.0, "advisory": true}` — so *every* category self-describes the weight it contributes and no consumer has to guess which numbers make up the total (also fixes a latent locale bug: the category key and the `%.1f` weight are now formatted with `Locale.ROOT`, so a comma-decimal locale can no longer emit invalid JSON). **Badge:** renders `overall` only and never showed a per-category number, so it is unchanged (verified byte-identical)
- Linter: package globs (`excludePackages` and every `[lint.layers]` list) — `**` now means **zero or more** package segments, so `com.example.core.**` matches the bare package `com.example.core` itself as well as everything beneath it. Previously it compiled to `com\.example\.core\..*` and, being fully anchored, never matched the declaring package: a class sitting directly in its own declared layer package silently fell out of that layer and was reclassified by whatever broader glob caught it, manufacturing bogus layering findings. The canonical example in `LayerConfig`'s own javadoc exhibited the bug. Measured against a realistic aether `[lint.layers]` config (domain catch-all + the five cloud-provider adapter globs) over the 46 provider files: **37 bogus findings before (32 `JBCT-ARCH-01` + 5 `JBCT-ARCH-02`), 0 after**; aether's default-config baseline is unchanged (1 `JBCT-ARCH-02`), since aether declares no layers. The two duplicated `globToRegex` copies — `LintContext` and `LayerClassifier`, the latter's comment already conceding it "mirrors LintContext" — are replaced by one canonical `PackageGlob` compiler in `jbct-core`, so the two config surfaces can no longer drift apart. Single `*` is unchanged (exactly one segment); `**` mid-glob spans any depth including none (`com.**.impl` matches `com.impl`), and a sibling package is still correctly rejected (`com.example.core.**` does not match `com.example.corex`). Widening `excludePackages` carries no suppression risk: no project in the repo configures it (the root `jbct.toml` uses the separate `[files].excludes` path mechanism). +31 tests (`PackageGlobTest`, `LintContextTest`) plus additive `LayerClassifier` glob-semantics cases; 653 green
- jbct-derive: entry gate now requires **Q4 (consistency contract) to be scoped** — a `system`-scoped consistency answer is rejected as `UNSCOPED`, matching Q3 (its per-data-class sibling) and all four golden sheets, which scope Q4 by `data-class`/`path`. Consistency is a per-data-class property in the method; a single system-wide contract is the one-size-fits-all smell the gate surfaces. Resolves the pending #443 Q4-scoping ruling; SPEC annotated, +1 gate test
- Linter: `JBCT-RET-08` (null literal as call argument) exemption extended from just `.or(null)` to the distinctive JDK boundary-adapter call names `orElse`, `compareAndSet`, `getAndSet` — an `Optional`/`Stream` nullable bridge and `java.util.concurrent.atomic` empty-sentinels are boundary adapters, not absence leaking into business logic, and cannot be `Option`-wrapped. Names are distinctive (no business-method homonyms) so the FN surface is negligible; common JDK names (`set`/`init`/`load`/`invoke`) are deliberately NOT exempted (dispositioned by explicit suppression instead). Corpus contact: of 110 aether sites, ~85% were JDK/framework-boundary or a legitimate data table (the same "correct JDK-boundary" pattern that got RET-08's null-*comparison* arm dropped) — the exemption clears the distinctive-name subset; the rest were fixed (`TypeMapper` type table Option-ified, `printQuery` Option-cored) or justified-suppressed (atomic `.set(null)`, `SSLContext.init`/`KeyStore.load`, reflective static invoke, JMX, Jackson view-DTO absent fields). Three new fixtures (#493)
- Linter: `JBCT-SEAL-02` now exempts the `record unused()` sealed-interface placeholder-filler idiom — a permitted-subtype stub for a sealed cause hierarchy that has no fixed-message variants of its own is a structural placeholder, not a fixed-message cause. Corpus contact showed the 50 same-file-resolvable hits split into 20 real named fixed-message causes (converted to the per-cause `enum … { INSTANCE }` idiom in aether/**) + 30 `unused()` fillers (part of a 136-site repo-wide idiom); the exemption removes the FP class rather than churning the idiom, keeping named-cause detection intact. Two FP-guard fixtures added (#493)
- Linter: `JBCT-ORD-01` member ordering reconciled to the book and the codebase's de-facto convention. **Use-case order corrected** from the #453 ticket's inverted `records → steps → factory → execute` to the manuscript's canonical `records → execute → step interfaces → static factory` (execute early, factory last — per `project-structure.md`'s numbered list and every worked example; the corpus writes execute-early, so the old rule was fighting it). **Value-object order relaxed** to fit real idioms: the static factory and accessors now share one rank (their relative order is not enforced, so serialization pairs like `toJson`/`fromJson` and private factory-helpers near their use stop flagging), and PRIVATE static-final constants (validation patterns, formatters, private pre-built instances placed at the bottom) are exempt from constants-first — public/package-API constants must still come first. Corpus swept to 0: use-case violations cleared by the order fix; of 9 value-object residuals, 7 were private-constants-at-bottom (cleared by the exemption), 1 file-local package-private constant made `private` (`ApplyState`), 1 package-API sentinel set hoisted to the top (`ArtifactDependency`). New `FileTypeClassifier.isPrivate` helper; +5 rule tests. The book's `project-structure.md` value-object note is reconciled separately in the book repo
- Linter: `JBCT-NAM-05` (test-method naming) relaxed from strict 3-segment `methodName_scenario_expectation` to **≥2 underscore-separated segments** (`method_[scenario_]expectation`), matching the codebase's pervasive, readable 2-segment convention. Corpus-validation via the plugin's `jbct.includeTests` opt-in surfaced 578 hits in just 4 aether modules (thousands corpus-wide), all the readable `method_expectation` / `scenario_expectation` form — the strict rule was over-flagging a good de-facto convention, not catching real defects. Single-word names (`testFoo`, `shouldWork`) are still flagged. Plugin test-source support confirmed already wired (the `jbct.includeTests` flag collects `src/test/java`); the project `CLAUDE.md` test-naming note is relaxed to match (the book's `chapter-summaries.md` statement reconciled separately in the book repo)
- Linter: `JBCT-BND-01` restored to its design ERROR severity — all three corpus sites dispositioned same-day (AwsLoadBalancerProvider fixed by aether-main, SliceStore CompletableFuture eviction hop removed; #493)
- Linter: `JBCT-RET-06`, `JBCT-TOT-01`, `JBCT-TOT-02` all at ERROR — the full aether corpus burn-down (#489) completed same release: 143 RET-06 sites resolved (69 real Option/Verify totalizations, the rest justified parse-don't-validate/framework-boundary suppressions) and all mapper-safety findings cleared (real fixes + rule FP corrections — see Fixed). The interim WARNING window existed only during the burn-down

### Added
- Linter: the **JBCT-CAUSE pack** (`jbct/docs/typed-error-lint-spec.md`) — seven rules enforcing the typed-error construction idiom, all at **WARNING** per the spec's §5.1: severities freeze only after the track-A census and the track-B pilot migration, never at introduction. CAUSE-01 representation shape (absorbs JBCT-SEAL-02 — its fixed-to-enum / data-to-record stance carries forward, plus the prescribed enum shape, message-only-record and anonymous-class checks; the `record unused()` filler exemption survives, and same-file hierarchy detection now runs to a FIXPOINT where SEAL-02 stopped at direct `extends Cause`); CAUSE-02 no hand-written `message()` bodies; CAUSE-03 `message` component last; CAUSE-04 the R1 arity equation, including the value-discarding form; CAUSE-05 wrapped causes use `Cause.Wrapped` instead of hand-declared `source()`; CAUSE-07 no anonymous single-argument templates in domain code (`Causes.cause(String)` deliberately unflagged — the typed/ad-hoc line is a semantic judgement no CST rule can decide); CAUSE-08 no direct construction of cause records (`FACTORY` is the only path on which the arity equation holds at runtime — the cause-flavored VO-02). Mixin recognition keys on the QUALIFIED `Cause.Terminal`/`Cause.Wrapped` spelling on the raw header, deliberately bypassing `DeclSupport.implementedHeadNames`, which strips qualifiers and would reduce the names to collision-prone simple forms. CAUSE-06 (no `message()` assertions in tests) awaits source-set awareness in `LintContext` and does not block the pack. The pack was **census-corrected before shipping**: the track-A census over the monorepo and three corpora found three defects in the pack itself — a prose-"default" FP that flagged the abstract `message()` on `Cause` (modifier checks now run over comment-masked text), a nested-interface double emission (member scans now scope to DIRECT members — the third occurrence of the subtree-attribution bug class this release), and the pack convicting `Causes.java`'s own sanctioned ad-hoc tier (the pack now skips `org.pragmatica.lang*`) — plus one design gap: ungated, CAUSE-08 fired 320 times on the pre-idiom smart-constructor pattern, so it now gates on the record actually declaring a `forXValues` factory (drift requires an idiom to drift from), taking it to a genuine zero. Post-fix census: CAUSE-01 105, CAUSE-02 661, CAUSE-03 9, CAUSE-04 0, CAUSE-05 20, CAUSE-07 151, CAUSE-08 0 — the 105/661 are migration backlog (pre-idiom house style, true by the new idiom's letter), not FP noise, and severity promotion waits on the burn-down per the spec's freeze criteria. New `CAUSE` score category; near-miss tests pin the boundaries that keep the rules from being noise generators (per-constant `isTerminal` bodies, constructor references, `%%`/`%n`, transitive hierarchies, the placeholder filler)
- CLI: **`jbct shape-census`** — reports the distribution of JBCT structural patterns (LEAF / SEQUENCER / FORK_JOIN / CONDITION / ITERATION / ASPECT, plus the MIXED and UNCLASSIFIED residuals) over a source tree, `--format text|json`. No new analysis: it exposes the existing `ShapeCensus` aggregator, whose javadoc had proposed exactly this subcommand. It folds per-file reports itself rather than calling `ShapeCensus.census(Collection)`, because that entry point contributes nothing for a file that fails to parse and the denominator would shrink invisibly — acceptable over a corpus known to parse, wrong for an instrument meant to be pointed at unfamiliar code, so `filesParsed` and `parseErrors` are reported and a test pins that an unparseable file is counted rather than dropped. Measured: jbct-loan 880 methods, ticketing 462, jbct-realworld 217
- CLI: **`jbct obligations`** — reports composition obligations that no test discharges, cross-referencing JaCoCo XML. A **gap list, never generated test code**: a generator was measured to emit tests that cannot be written, tests duplicating existing leaf tests, and an inverted assertion. It does not match test names either, since real names are business-named (`execute_failsWhenAmlFlags`) and structural matching reports false gaps on the best-tested code. Scope is stated in its own output — only compensations and absorbed failures, the two obligations decidable from a method name plus coverage; the success path and per-I/O-failure obligations need the chain decomposed step by step and are not attempted. It refuses to run without coverage data rather than reporting every obligation cold, which is a test. Calibration: ticketing 9/9 discharged, `LoanOrchestrator` exactly four cold
- Linter: **`JBCT-REC-01`** — a failure absorbed by `.recover(...)` with no recorded reason (WARNING). It does not object to absorption, which is legitimate and common; it objects to *silent* absorption, which cannot be told apart from an accident. Justification is the recovery-triple vocabulary already in use (`BER` / `FER` / `design-out` / `best-effort`) in a comment, checked on the absorbing method then against the file — file-scoped because the corpus documents absorption in three places and only one is the method itself (`BuyTicket.voidReceipt`'s reason sits on its sole caller; the projection slices document theirs in a companion `*Log.java` a single-file rule cannot read). The fallback is proportional, not "the file mentions a tag once": a file needs at least as many justifications as absorptions, so adding a `.recover(...)` without a reason is caught even where others are documented. Corpus-calibrated to silence: 0 findings across ticketing's 10 absorption sites, and 0 in jbct-loan and jbct-realworld, which contain none
- Score: first test coverage for the two score *surfaces* (#541). `ScoreCommand` and `ScoreMojo` had **zero tests** — `grep -rln "ScoreCommand\|ScoreMojo" jbct --include="*Test.java"` came back empty — so the `--format` routing, the hand-rolled JSON, the stdout/stderr split and the build-failing `--baseline` / `jbct.score.baseline` gate were all assumed correct. `ScoreCommandTest` (10 tests, jbct-cli) drives the real command through picocli against a temp source tree and asserts the emitted bytes per format: terminal box (borders, score header, advisory marker), badge SVG (first line, score text, band colour, trailing blank line), and JSON — **parsed with a real parser** (jackson-databind, test scope) rather than string-matched, asserting `score`/`filesAnalyzed`/`breakdown` and, per category, the `score` + `weight` + `advisory` triple the previous entry added. One test pins the property that makes `--format json` pipeable at all: `FileCollector`'s `Skipping …` diagnostics go to **stderr**, never into the JSON on stdout. `ScoreMojoTest` (6 tests, jbct-maven-plugin) runs the goal against a temp project and asserts the report reaches the Maven log and that `MojoFailureException` is thrown below the baseline and only below it. Neither suite hard-codes a score: each reads the score back from the surface under test and derives the baseline from it, so lint-rule changes move the fixture's score without invalidating the gate assertions. `ScoreReportTest` gains 5 badge tests, including the badge as a **golden document** — it is a published artifact, so its bytes are part of the contract. Every claim was checked by mutation: inverting either baseline comparison (2 CLI + 2 goal failures), dropping the JSON entry separator (7 CLI errors, a real parser error), corrupting a `weight` value (2 failures), routing the collector's diagnostics to stdout (1 failure) and reordering the badge colour table (4 failures) each turn the suite red
- Linter: per-run **layering-coverage summary** — one line (`layering: evaluated 46 of 3458 files, 3412 unclassified`, with `, N excluded` appended when `excludePackages` skipped files) emitted by `jbct lint` / `check` / `score` and the `lint` / `check` / `score` goals. `JBCT-ARCH-01` and `JBCT-ARCH-02` are both silent for a file whose own package cannot be classified — correct (a direction cannot be ranked when one endpoint has no rank) but traceless, so a narrow `[lint.layers]` config enforces almost nothing while the run reports clean: an adapter-only config over aether's 46 cloud-provider files produced **0** findings, the same config plus a domain catch-all produced **37**. Gated on provenance (new `LayerClassifier.isExplicitlyConfigured()`): without an explicit `[lint.layers]` section nothing is emitted, since conventions-only classification needs a literal layer keyword in the package name and would otherwise nag every project that never opted in. Deliberately not per-file INFO diagnostics — under a narrow config most files are unclassified, which would trade silence for thousands of lines. The count lives in one shared `LayerCoverage` aggregator in jbct-core (a report over the run's file collection, not diagnostics — the `ShapeCensus` shape) that all six call sites use, rather than a counter copied into each command's file loop; CLI output goes to stderr so `--format json` / `badge` / `sarif` stdout stays machine-parseable (#534)
- Linter: `JBCT-SHAPE-03` shape↔zone-verb cross-check (#448 phase 3) — a new INFO facet built directly on the shape classifier that flags mis-leveled methods: a Zone-3 implementation verb (`get`/`fetch`/`parse`/…) heading a multi-step SEQUENCER/FORK_JOIN (an implementation-named method doing orchestration), or a Zone-2 orchestration verb heading a bare LEAF. Naming zone and composition shape are orthogonal axes, so it ships INFO and — after the corpus gate returned 622 hits (~460 the expected orchestration-verb-on-LEAF one-liner noise) — **default-disabled, census-on-demand like SHAPE-02**. The two other cross-checks the ticket enumerated were not built, with reasons: shape↔2-5 is fully redundant (a one-link chain already classifies LEAF in the decision table), and shape↔return-type is too noisy single-file (ITERATION terminals mix collection and scalar forms). #448 phases 1–3 complete
- New module `jbct-derive` (#443) — the `next_step` derivation engine. **Phase A**: schema-v0.1 answer-sheet model + TOML parsing (parse-don't-validate, line-fidelity errors incl. actionable unquoted-date guidance), the entry gate (UNPRICED / UNSCOPED / UNDECOMPOSED / UNTRIAGED / BARE_ILITY / MISSING_SHAPE / MISSING_DOMAIN_SHAPE with the book's vocabulary; UNKNOWN rows pass and propagate), the `jbct check-sheet` CLI. SPEC.md synced from the book's working files; the four published runs' answer sheets transcribed from prose to schema form as goldens (provenance documented). **Phase B**: the derivation pipeline (prune mandate-strikes → press containment-rung/combination checks → resolve cheapest-value/narrowest-scope/conflict-rule/contradiction → verify five budget-arithmetic rules, missing floor ⇒ UNVERIFIED never a default) with markdown + hand-rolled JSON output and the `jbct derive` CLI (exit 0 clean / 1 gate / 2 halts / 3 judgment-points-pending). Named judgment points (recovery ties, contradiction choices, targets, product picks) are EMITTED, never resolved — the engine is the entry gate and bookkeeping, not the oracle. Golden acceptance: all four runs reproduce their recorded moves incl. the SPEC §4 narrowest-scope topology splits (SO exactly tag+search, Discord gateway, Shopify PCI card-path, CH date-of-birth scope-exclusion — each asserted as an exact set); the one true divergence (CH incorporate→BER) is an honest schema-v0.1 gap (no status-transition reshape category), not engine judgment. Apache-2.0, depends on jbct-core only. Schema-v0.2 enrichment findings (mandate strike under-encoding, status-transition reshape value, thin-tier `contained` marker) flagged for follow-up
- Linter: #448 method-shape classifier + census (phase 1) — a `MethodShapeClassifier` (jbct-lint `cst.shape`, sibling to #453's file-type classifier) assigns every concrete method exactly one JBCT pattern (LEAF / SEQUENCER / FORK_JOIN / CONDITION / ITERATION / ASPECT) or one of two residual verdicts, from the returned expression's syntax alone (no type resolution). Two-stage: a spine walker reads the top-level links of the return chain, then a decision table maps join-head/stream-pipeline/sequencing-link features to a shape; a root ternary/switch is CONDITION, a factory returning a lambda is classified by the lambda body, a `with*` decorator or a body that applies+decorates an injected functional parameter is ASPECT. Legal composition does not flag — an extracted fork-join *called* as a sequencer step reads as a plain `flatMap` link (SEQUENCER, never MIXED). Two INFO census rules surface the residuals: `JBCT-SHAPE-01` (MIXED — two pattern features at one altitude) and `JBCT-SHAPE-02` (UNCLASSIFIED — imperative residue, no single composition root); the six pure shapes stay silent (no per-method INFO). A public `ShapeCensus` aggregator computes the shape histogram + residual (MIXED+UNCLASSIFIED) rate in one sweep so the ticket's <5% corpus gate is measurable from tests or a future CLI subcommand. The aether corpus census came back MIXED = 0, UNCLASSIFIED = 5336 (multi-statement / local-var-then-return domination in node/cli/deployment), so `JBCT-SHAPE-02` ships **DEFAULT-DISABLED** (census-on-demand via config enable) to avoid spamming every `jbct:check`; `JBCT-SHAPE-01` stays enabled (corpus-zero, silent, the real-time single-pattern signal). Classifier gets a fixture-per-shape test (all six + MIXED + UNCLASSIFIED + legal-composition + lambda-unwrap + abstract-exclusion + census over a root and a source-file collection); both rules get adversarial positive/negative fixtures + suppression paths (the fixture harness force-enables all rules so the default-disabled SHAPE-02 is still exercised). PAT-02/ZONE-03/NEST-01 are documented phase-2 seams (not yet absorbed). Registry 62 → 64 rules
- Linter: #453 file-type classifier + structural rules — a `FileTypeClassifier` (jbct-lint `cst.filetype`, public for reuse by #448 and score categories) routes a compilation unit from its own syntax to one of USE_CASE / VALUE_OBJECT / ERROR_TYPE / STEP_INTERFACE / UTILITY_INTERFACE / TEST_CLASS / UNCLASSIFIED, anchored on the principal top-level type with a `@Test`-family override. Rules (all provisional WARNING pending a corpus-calibration gate, design severity fixed after the aether run): `JBCT-UC-02` use-case interface structure (single `execute` entry, nested Request/Response, static factory), `JBCT-ORD-01` member ordering per file type (value object: constants→ctor→factory→accessors; use case: records→steps→factory→execute — reports the first out-of-order member; uncategorized in scoring), `JBCT-INJ-01` constructor/factory injection only (non-final instance fields / setter-shaped methods in implementations of an in-file use-case/step interface, or impls nested in a use-case file; TEST_CLASS skipped), `JBCT-VAL-01` boolean `isValid()`/`validate()` on domain types (parse-don't-validate), `JBCT-STAGE-01` deep `request().request().request()` stage chains (masked textual check), and `JBCT-SIDE-01` side effects in map/filter lambdas (**INFO — expected FP surface, promote-after-calibration**). Classifier gets its own fixture-per-file-type test; each rule has adversarial positive/negative fixtures plus a suppression path. Registry 56 → 62 rules. Corpus gate: first contact exposed classifier misroutes (annotation-brace header truncation hiding `sealed` — also fixed in the #451 DeclSupport rules — plus execute-only/record-factory over-capture) driving UC-02 to 100% FP; after the classifier fixes UC-02/INJ-01/VAL-01/STAGE-01 are corpus-clean, ORD-01 has 25 residual real findings (constants-ordering idiom, tracked in #493), SIDE-01 collected 67 INFO calibration samples
- Linter: #452 package-classification layering engine — `[lint.layers]` TOML section (per-layer package globs + slice-root globs, longest-match precedence) with convention-first defaults from the book layout; unclassifiable packages produce no layering diagnostics. Rules: `JBCT-ARCH-01` dependency direction (imports point up only, rank checks scoped to the file's own root group so third-party packages never rank-classify; ERROR — corpus clean), `JBCT-ARCH-02` `lift(...)` confined to the adapter-boundary zone (WARNING), `JBCT-ARCH-03` use case must not reference another use case (WARNING), `JBCT-ARCH-04` no imports of another slice's internals (ERROR — corpus clean; delivers the enforcement deferred from #450). `JBCT-MIX-01` migrated onto the shared classifier with pinned intentional deltas (segment-exact domain classification). Registry 49 → 53 rules
- Linter: #451 easy-tier rule batch — `JBCT-BND-01` forbidden boundary types in business logic (Optional/CompletableFuture/CompletionStage/Mono/Flux/ResponseEntity; WARNING pending 3-site corpus disposition, design severity ERROR), `JBCT-STY-09` nested ternaries, `JBCT-NAM-03` `*State` suffix discipline, `JBCT-NAM-04` local-record lowercase naming, `JBCT-NAM-05` test-method naming `methodName_scenario_expectation` (note: the Maven check goal lints main sources only — this rule currently bites via CLI lint on test dirs; plugin test-source support is a follow-up), `JBCT-MUT-01` parameter reassignment, `JBCT-RET-08` null literal as call argument with the documented `.or(null)` exemption (the defensive null-compare arm was dropped after corpus validation showed ~90% of its findings were correct JDK-boundary checks that cannot Option-wrap — flow-aware policing belongs to the #455 tier), `JBCT-SEAL-02` Cause variant style (fixed-message causes → enum, data-carrying → record). All corpus-validated before landing; registry count 41 → 49 rules
- Linter: `JBCT-TOT-*` mapper-safety family (#486; delivers the lint half of #484) — TOT-01 partial operations (`getFirst()`, `getLast()`, `get(i)`, `iterator().next()`, `orElseThrow()`, `Optional.get()`, `throw`) inside carrier mapper lambdas (ERROR, stream-pipeline spines exempt), TOT-02 partial method references in mapper position via case-insensitive `*OrThrow` heuristic + same-file body scan (ERROR), TOT-03 Jackson wire-record accessors dereferencing possibly-null reference components without a guard (WARNING). Catches the #483 incident class: an in-mapper throw swallowed into a never-resolving promise. R-D (wire-type boundary escape) deferred until the #452/#453 classifiers exist — not determinable single-file
- Linter: fixture coverage for all 41 lint rules — positive (rule ID + line asserted) and negative fixture per rule through a shared parameterized harness (`RuleFixtures` catalog), plus registry invariant tests (every registered rule must have a severity entry, a fixture, and a score category) and suppression-path coverage (`@SuppressWarnings` single/list/"all", `@Contract`, `@TerminalOperation`, `@NullReturn`). New-rule PRs now fail without fixtures (#454)
- Linter: `@Contract` annotation support — methods or classes annotated with `@Contract` are exempt from JBCT-RET-01 (void return type). Use `@Contract` instead of `@SuppressWarnings("JBCT-RET-01")` to declare that void is intentional for a method's contract (framework callbacks, fire-and-forget mutations, event handlers). Annotation lives in `org.pragmatica.lang.Contract`.
- CLI: `jbct add-slice <name>` command — scaffolds a new slice into an existing project with all required files (interface, test, routes.toml, config, dependency manifest) in a dedicated sub-package, enforcing the one-slice-per-package convention

### Removed
- Linter: **JBCT-SEAL-02 retired by absorption** into JBCT-CAUSE-01 (typed-error-lint-spec §1.1). The stance was right and carries forward unchanged; the pack adds what it never checked. No suppression comments referenced it anywhere in the monorepo (verified: zero), so the transition is free
- Score: the **`--format badge` output and the whole badge machinery** — the SVG template, the `BadgeColor` band table, `badgeLines`/`badgeColor` and their tests (#533). An unbounded lower-is-better metric has no meaningful colour band, and the format had **zero consumers**: the only badges in the repo are shields.io license/Java/Maven badges, and nothing renders the JBCT one. `--format badge` is now rejected as a usage error (exit 2) naming the supported formats, not silently rendered as a terminal box — substituting a different format for a real one would hand a CI job the wrong bytes with a zero exit code
- Score: the **`jbct.score.baseline` / `--baseline` gate**, replaced by `jbct.density.maxPerKloc` / `--max-density` (#533). The direction is inverted — the baseline failed the build *below* its threshold, the density gate fails *above* it — so the property was **renamed rather than reinterpreted**: keeping the name would mean a CI snippet copied forward quietly asserts the opposite of what it says. Both old names are still bound, purely as tripwires: the CLI exits 2 and the goal throws `MojoExecutionException`, with a message naming the replacement, the new direction and the fact that the old threshold cannot be carried over. Confirmed inert before removal — no pom in the repo sets a baseline, so there is no migration cost. Direction and wording live once in a new `DensityGate`, so the CLI option and the Maven property cannot drift apart on either
- Score: `ScoreCategory.weight` / `weightFraction`, the `ERROR`/`WARNING`/`INFO` severity multipliers, the `checkpoints` concept with `ScoreCalculator.countCheckpoints`, and `ScoreReport.progressBar` (#533) — see the density entry under Changed for the reasoning behind each
- Linter: `JBCT-SLICE-01` config entry and the dead `slicePackages` / `LintContext` slice-pattern machinery behind it. The rule was declared at ERROR in the default config but had no rule implementation, scorer, or fixture referencing it, and the slice-processor does not check cross-slice import boundaries either — it only generates factories/manifests for `@Slice`-annotated interfaces. Cross-slice import-boundary enforcement is deferred to the layering-rules engine (#452, #450)

### Changed
- Linter: method-shape classifier (#448 phase 3) — a lambda-argument descent primitive (`chainLambdaLinks` + `classifyLambdaBody`, which the phase-1/2 spine extraction deliberately discarded) plus the absorption of three string-heuristic rules into classifier facets. `JBCT-PAT-02` (fork-join nested in a sequencer lambda) became a true structural facet locating `flatMap`/`andThen` argument lambdas via the descent. `JBCT-ZONE-03` (Zone-3 verb in a `map`/`flatMap` lambda) and `JBCT-NEST-01` (nested monadic ops) kept their exact detection logic but now run it over `MapperSafety.blankNonCode`-masked text — a deliberate scoping call: full structural re-classification would drop real single-combinator re-chains (`inner(x).map(f)` classifies LEAF), a regression, so only the masking was adopted (a safety-net that neutralizes a verb/op/join token spelled inside a string or comment). All three rules keep their IDs, severities, score categories, messages, and byte-identical reported lines. Non-regression is guaranteed by construction (NEW ⊆ OLD; every dropped hit would be a masked comment/string token) and confirmed empirically: the full 67-module corpus fires byte-identical (ZONE-03 296, NEST-01 202, PAT-02 0 — zero added, zero dropped), so nothing downstream changes. Also: PAT-02 gained the `shouldLint`/`excludePackages` gate its siblings already had (pre-facet gap), and `monadicOpCount` precompiles its alternation

### Fixed
- Linter: **`JBCT-RET-05` was blind to `Promise` steps and fired on delegating bodies.** It matched only `Result<` returns and asked one question of the body — does it mention success and not failure — which is wrong in both directions. It now matches `Promise<`; excludes bodies composing over a delegate's wrapper (`.flatMap(`, `.async()`, and the aggregate forms enumerated from `Promise`'s real surface: `all`, `allOf`, `allOrCancel`, `allOfOrCancel`, `any`); excludes `@Override` methods, which do not own their signature; and, the correction that mattered most, requires **every `return` to BE a `success(...)` construction** rather than merely that a success appears somewhere in the body. That distinction produced every false positive an audit found: `httpOps.fold(() -> Promise.success(unit()), ops -> sendWithRetry(...))` mentions success but RETURNS a delegate whose other branch fails. Head-anchoring condition 1 took `aether/node` from 48 findings to 28 and removed both confirmed false-positive classes at once. Severity stays WARNING pending a burn-down
- Linter: **`MethodShape.MIXED` was unreachable** — 0 across 49,460 methods in seven codebases, including deliberately constructed violations, so a measured `MIXED = 0` read as conformance when it was the instrument's silence. `jbct lint` and `jbct shape-census` also contradicted each other on the same method: PAT-02 reported "Fork-Join pattern nested inside Sequencer chain" while the census called it a clean SEQUENCER. Phase 1 had scoped MIXED to a fork-join head plus a stream pipeline at one altitude, deliberately leaving richer nestings to PAT-02 / LAM-03 / NEST-01 "until phase 2 revisits MIXED and folds that rule in"; this is that phase 2. A composition whose chain passes an **inline lambda carrying its own structural pattern** now classifies MIXED, with the predicate taken from the classifier's own shape vocabulary so the census and the rules cannot drift apart again. A **method reference is not a lambda** — extraction is the prescribed fix and must never be penalised — and **imperative code stays UNCLASSIFIED**, since MIXED must mean "JBCT code mixing JBCT patterns" rather than "not written in the vocabulary", which would swamp any cross-codebase comparison. Baseline is now a rate rather than a structural zero: jbct-loan 3/880 (0.34%), ticketing 0/462, jbct-realworld 0/217
- Maven plugin: `jbct.includeTests` was **inert for the format-family goals** (#624). `ProcessMojo`, `FormatMojo` and `FormatCheckMojo` each declared their own `includeTests` field with `defaultValue = "true"`, shadowing an `AbstractJbctMojo` field of the same name defaulting to `"false"`; collection ran in the parent and read the parent's field, and a field read is statically bound, so the subclass declarations could not influence it. `jbct:process` and `jbct:format` therefore **never saw `src/test/java`**, and `-Djbct.includeTests=true` did nothing for them — while `lint` / `check` / `score`, which never shadowed, honoured it correctly. `build.sh` Step 2 runs the combined `process` goal, so test sources have never been formatted or linted by the build. Every part of the surface said otherwise: the parameter existed, was documented, and appeared in the generated `plugin.xml` with `default-value=true` — a 2026-07-24 handover recorded the support as working on the strength of that, which corrects to **not working**. Verified end to end through real Maven, not inferred: a deliberately mangled `src/test/java` file survives `jbct:process` byte-identical with AND without the flag, while `jbct:lint -Djbct.includeTests=true` picks it up (1 file → 2). Fix removes the parent's field entirely so there is nothing left to shadow, widens collection to `collectJavaFiles(FilesConfig, boolean includeTests)`, and has each of the six goals declare its own parameter and pass it. `IncludeTestsParameterTest` pins the defect CLASS rather than this instance — no goal may inherit a second field of that name — and reintroducing the parent field turns all six parameterized cases red. **Defaults deliberately stay `false` for every goal, including the three that claimed `true`:** admitting test sources is a policy change, not a mechanism fix, and it was measured before being declined — 1086 test files carry **2042 ERROR-severity findings** (60% of them `JBCT-EX-01`, since JUnit tests idiomatically declare `throws`) plus 12124 warnings, and the formatter would rewrite **1084 of 1086 files (99.8%)**. Turning it on unconditionally fails the build across four modules; it is now opt-in via `-Djbct.includeTests=true`, and enabling it by default needs test-aware rule scoping first
- Linter: every rule went blind to interface and record members after the peglib 0.7.1 grammar bump, silently dropping **1962 of 13274 findings** on the aether corpus (2135 files). Pre-bump `InterfaceDecl <- … ClassBody` and `RecordMember <- CompactConstructor / ClassMember`, so interfaces and records reused the class spelling and every rule covered them for free. 0.7.1 gave each its own productions, and — the part that did the damage — an `InterfaceMember`/`RecordMember` holds its `MethodDecl` **directly**, with no intervening `Member` node: for a class the modifier-bearing wrapper (`ClassMember`) and the declaration (`Member`, whose text starts at the return type) are two nodes, for an interface or record they collapse into one. `CstNodes.findAllMethods`/`isMethodMember` keyed on `MEMBER`, so they returned **empty** for every interface — JBCT's primary subject matter — and 48 call sites across 27 files inherited that blindness. The shapes are now reconciled in `CstNodes` rather than at each site (`isMemberDecl`/`isMemberWrapper`/`enclosingMember`/`enclosingMethodMember`/`typeBodyMembers`/`isFieldDecl`), mirroring how `parameterNodes` contained the `Params` change. Two traps the collapse sets, both live defects before they were fixed: (1) an ancestor-only wrapper lookup walks straight *past* an interface/record member, since there the member IS its own wrapper — `enclosingMember` therefore matches the node itself, without which `CstReturnKindRule.isPrivateMethod` read `false` for every interface/record method and JBCT-RET-01 over-reported by 144; (2) the wrapper's text spans annotations, so heuristics that regex a declaration mis-read it — `@SuppressWarnings("…")` supplies the first `(` and `)`, which made JBCT-NAM-01 report `Factory method 'SuppressWarnings'` and defeated JBCT-LOG-02's `indexOf("Logger ") < indexOf(")")` check. `memberDeclText` anchors those reads on the member's declaration — its first non-`Annotation` child, which covers constructors, fields and nested types as well as methods — and is byte-identical to `text(member)` for a class, so class-side behaviour is provably unmoved. Diagnostics anchor through the same declaration (`anchorOf`), so an annotated interface method reports the same line as the identical method in a class rather than the annotation's line. Verified differentially — the pre-bump binary and the fixed binary linting the *same* 2135 files: **every rule matches its pre-bump finding count exactly, and every finding matches by file, line and column**, with exactly two intended exceptions (JBCT-EX-02 below, and one JBCT-ORD-01 column: a record's `RecordStaticField` node spans its modifiers where the old `FieldDecl` began at the type, so the anchor now covers the whole declaration). Diff as a **multiset** — JBCT emits basenames, not paths, and 21 of 2141 aether basenames collide, so a set-based diff collapses ~880 duplicate triples and can hide a change behind a colliding file. 12 paired class/interface/record regression tests were added for the annotated-member shapes, which no suite had covered — the regression was invisible to 691 passing lint tests and took the corpus differential to see
- Linter: `JBCT-EX-02` matched only 4 of the 57 `.orElseThrow` calls in the aether corpus — **7% recall, and broken before the bump too**, not a regression it introduced. The rule tested `text(node).contains(".orElseThrow")` against `PRIMARY` nodes, but a chained `….findFirst().orElseThrow()` spells the call in a `PostOp` (expression position) or a `ChainOp` (statement position); only a direct `x.orElseThrow()` keeps it inside the `Primary`'s qualified name, and the four it did catch were that incidental shape. 0.7.1's chain rework removed even those, taking it to zero. Matching the narrowest node that actually spells the call — `PostOp`/`ChainOp` whose text starts with `.orElseThrow`, else a `QualifiedName` containing it — gives one diagnostic per call with no double-reporting across enclosing expression levels: **57 findings against 57 raw occurrences**, exact. The rule is severity `error`, so this surfaces 53 previously-invisible violations (49 in tests, 4 in production code) for burn-down
- Formatter: a method chain inside a lambda body stopped breaking vertically when the lambda was a call argument — `return input.map(s -> s.trim().toUpperCase());` stayed on one line where the golden expects the chain broken and aligned. 0.7.1 hoisted `Lambda` out of `Primary` (`Expr <- Lambda / Assignment`), so an argument lambda is now reached through `printNodeContent` rather than `printNode`; the two lambda printers differed only in that the content variant never entered the tail context that makes chains break, and the CST of the body itself was byte-identical to 0.6.2. Both entry points now share one walker that establishes the context. Verified by bisection on a minimal snippet, then the full golden suite green with **no golden edits**
- Linter: `JBCT-STY-03` reported a fully qualified name written inside a **string literal, text block or comment** — data it was reading as code. Found while linting this repo's own new tests: a fixture that feeds Java source to the linter (`var source = """ … import org.pragmatica…; … """`) was flagged for every `import` line inside the text block. The rule scanned raw method text while 10+ sibling rules already mask non-code spans through `MapperSafety.blankNonCode`; it now does the same. The worst case was javadoc: a `/// … [org.pragmatica.lang.Cause] …` cross-reference **must** be fully qualified to resolve, so the rule was penalising correct documentation. Corpus effect on aether: **1130 → 957 findings, and all 173 removed were false positives** — audited by classifying every one rather than sampling: 25 in comments (mostly javadoc links), 148 inside string literals and text blocks, zero genuine qualified-name-in-code findings lost
- Linter: a trailing line comment after a **bare annotation** silently disabled it — `@Contract  // reason` behaved as if unannotated, and the rule it exempts fired anyway. Reported from a real `rc2 → rc3` upgrade, where converting `@SuppressWarnings("JBCT-RET-01")  // reason` to `@Contract  // reason` — a mechanical, comment-preserving edit the `Contract` javadoc explicitly steers people toward — turned a green build red with errors pointing at methods that *were* correctly annotated. The cause is not textual matching, as it appeared: detection is already CST-based, but a node's SPAN can reach past its last real token into trailing trivia, so `text(qualifiedName)` returned `"Contract  // reason"` and matched no known annotation name. `@SuppressWarnings` was immune only because its `(...)` terminates the name span before a comment can be absorbed — so every BARE annotation was affected: `@Contract`, `@TerminalOperation`, `@NullReturn`. The same one-line pattern in `CstTestMethodNamingRule` made the defect bidirectional: `@Test  // comment` stopped registering as a test at all, so **JBCT-NAM-05 silently skipped those methods** — a false negative no corpus diff would ever surface, since it shows up as nothing. `CstNodes.tokenText` now returns a node's non-trivia tokens, and every comparison of node text against a NAME goes through it
- Linter: `JBCT-BND-01` flagged any type whose **simple name** matched a boundary type, so a project's own `Expression.Optional` was reported as `java.util.Optional`. Reported from peglib, where `Expression.Optional` models the PEG `?` operator and is public API: upgrading produced 31 gating errors, of which **18 were this false positive** across 9 files, with no workaround but renaming public API or suppressing the rule in exactly the files where a genuine boundary leak is most likely to hide. `headName` reduced every type to its last dotted segment, which is what destroyed the evidence — `Expression.Optional` and `java.util.Optional` both became `Optional`. Matching is now by ORIGIN, and needs no cross-file type resolution: **none of these types live in `java.lang`**, so a qualified use names its own origin, and an unqualified one can only denote a boundary type if this file imports it (explicitly or via a star import, with a locally declared type shadowing the latter). An import is likewise flagged only when its fully qualified name is a boundary type, so `import com.acme.Optional;` is no longer a violation either. Verified against the aether corpus: **byte-identical, 14183 findings before and after, with BND-01's 4 genuine findings preserved exactly**. Four existing rule tests asserted the old behaviour using fixtures that referenced `CompletableFuture` / `Mono` / `ResponseEntity` with **no import** — code that does not compile, and the only shape under which the simple-name match was load-bearing; the fixtures now carry the imports the scenario requires, and 10 new regression tests pin both the true positives (import, fully-qualified use, star import) and the false positives that prompted the report
- slice-processor: a slice whose method interceptor named a config section containing any character illegal in a Java identifier generated a factory that would not compile. `@ResourceQualifier(config = "cache.availability.seat-status")` emitted `.map((store, methodInterceptor_cache_availability_seat-status) -> {`, and javac reported `')' or ',' expected` / `illegal start of expression` **inside generated code** — no `[SLICE-…]` diagnostic pointed at the slice. The mechanism was that `FactoryClassGenerator.collectUniqueInterceptors` built the lambda parameter name with `configSection().replace('.', '_')` as its *only* sanitization, while the section itself is arbitrary user text; the type half of the same name already went through `ResourceQualifierModel.variableSafeName()`, so only the config half was unguarded. The failure looked arbitrary to users because hyphens are conventional TOML style and work everywhere else — `@Scheduled` never reaches the factory, and publisher/subscription qualifiers pass the hyphenated topic through as a *string literal*. Sanitization now lives in `ResourceQualifierModel.variableSafeConfigSection()`, which replaces every code point failing `Character.isJavaIdentifierPart` with `_` (identifier-*part*, not -*start*: the fragment is only ever appended after a `typeName_` prefix, so a leading digit is legal there). Sanitizing alone was not enough and is not a separate ticket: it is not injective — `a-b` and `a_b` collapse onto one identifier while `deduplicationKey()` still keeps them as two entries — so two interceptors differing only by that separator declared the same lambda parameter twice (`variable methodInterceptor_cache_seat_status is already defined`), uncompilable for a second reason. Issued names are therefore tracked and de-collided with a numeric suffix (`…_2`). No early validation was added: every input now yields a valid unique identifier, and rejecting hyphens would have broken the TOML style the report asks to support. Only the local variable name changes — the `ctx.resources().provide(Type.class, "…")` literal still carries the section verbatim, so resolution behaviour and envelope structure are untouched. 3 regression tests covering the hyphenated section, the separator collision, and the pre-existing dotted form; the interceptor path previously had no fixture at all, which is why this shipped
- Config: a user-level setting was silently reverted by any project config that touched the same section (#532). `JbctConfig.mergeWith` was section-level *replacement* gated on `equals(DEFAULT)`: if a layer's `[format]`/`[lint]`/`[files]`/`[blueprint]` section differed from its default at all, that whole record was taken and every field the layer beneath had set in it was discarded. Live consequence — `~/.jbct/config.toml` setting `failOnWarning = true`, plus a project `jbct.toml` naming a single rule severity, silently yielded `failOnWarning = false`: `fromToml` read the absent flag as `false` and rebuilt `ruleSeverities` from `LintConfig.DEFAULT` rather than from accumulated state, so the resulting `LintConfig` never equalled DEFAULT and was taken wholesale. The root cause was representational, not algorithmic: a parsed layer could not distinguish "key absent" from "key explicitly set to the value that happens to be the default", in either direction — an omitted list and an explicit `excludes = []` both satisfied `isEmpty()`, so a leaf could neither clear an inherited list nor force a field back to its default. Layers now parse into `PartialConfig`, in which every key is an `Option`, fold key by key (nearest wins), and materialize the built-in defaults exactly once at the end; `[lint.rules]` entries fold as one value per rule, so a nearer `"error"` both raises the severity and lifts an inherited `"off"`. The revert disappears as a consequence of the representation rather than as a special case. 29 new tests, all previously absent
- Linter: `jbct score` (and any lint run) died with `StackOverflowError` on a source tree containing a large text block (#540). `MapperSafety.STRING_LITERAL` compiled both of its alternatives as a quantified group *containing alternation* — `(?:[^"\\]|\\.|"(?!""))*` — which the JDK builds as `Loop`/`Branch`/`GroupHead`/`GroupTail` nodes whose `Loop.match` **recurses once per iteration**, each iteration consuming exactly one character. Stack depth therefore grew linearly with literal length: the 7481-character scaffold text block in `SliceProjectInitializer` produced a 33 667-frame trace and killed the process (exit 1, no output) at the default stack size. The bulk alternatives are now **possessive** (`[^"\\]++`), so one iteration consumes a maximal run of ordinary characters and depth tracks the number of `"`/`\` occurrences instead of the literal's length. The language matched is unchanged — every closing delimiter begins with `"` and the sibling alternatives require `"` or `\`, all excluded from the possessive class, so declining to backtrack can never lose a match the greedy form would have found. `CstDiscardedResultRule` carried a **private duplicate of the same pattern**, and therefore the same bug; it now shares the single `MapperSafety.STRING_LITERAL` definition so the two cannot drift. The rest of jbct's main sources were swept for the same construct: `CstValueObjectFactoryRule.ANNOTATION_SIMPLE_NAME_PATTERN` and `CHAIN_TERMINAL_PATTERN` are bounded (iterations track qualified-name segments, and the latter's alternation group is not quantified at all), so both are left alone. Regression tests run the masking on a thread with a fixed 1 MB stack — the macOS main thread's 8 MB process stack is generous enough to hide the fault — and assert masking correctness (offset/newline alignment, escaped quotes, inner quotes, `maskForOps` fill) alongside completion
- Linter: method-shape classifier (#448 phase 2) — two corrections that make the census materially more accurate. (1) **Preamble reach**: `MethodShapeClassifier` no longer bails on any multi-statement body; a body whose leading statements are all skippable preamble (pure local declarations — mutation-signal-guarded so a mutating initializer stays imperative — narrow `if (…) return/throw` guards, or a single logger call) now classifies by its composition-root tail, so `var v = validate(r); return v.map(…).flatMap(…)` reads SEQUENCER instead of UNCLASSIFIED. (2) **Latent `extractSpine` bug**: the v6 grammar folds a dotted call target into the `PRIMARY` leaf (`valid.map(f)` parses as `PRIMARY[valid.map]` + `POST_OP[(f)]`), so any variable-receiver chain lost its leading combinator link and a two-step chain mis-read as a one-step LEAF — the absorbed segment is now recovered as the chain's first link, keeping the full dotted head for join/aggregator/stream/aspect matching. Corpus census: UNCLASSIFIED 5336 → 3832 (the residue is genuinely imperative code — the ticket's <5% promotion gate is not reachable on this corpus, so `JBCT-SHAPE-02` stays census-only/default-disabled). PAT-02/ZONE-03/NEST-01 absorption remains phase-2-follow-up
- Linter: `JBCT-RET-06` hardened before its corpus burn-down — the param null-check scan now ignores string literals/comments (reuses the mapper-safety masking) and no longer attributes qualified field access (`cfg.timeout == null`) to a same-named parameter (#489)
- Linter: three false-positive defects in the `JBCT-TOT-*` family, exposed by first corpus contact (~90% FP rate): (1) string literals were blanked to spaces before op-matching, making `map.get("key")` match the no-arg `.get()` partial form — op scanning now masks literals to a placeholder; (2) bare `.get()` matched total `Supplier`/`AtomicReference` reads — now only Optional-evidenced shapes flag (`Optional…get()`, `findFirst()/findAny().get()`); (3) `JBCT-TOT-02`'s method-ref body scan matched by bare name across all types in the file — now receiver-scoped (`this::m` → enclosing type, `X::m` → type X in file) and restricted to non-overloaded partial singletons. Also fixed: a `.stream()` inside the mapper's own lambda no longer exempts the carrier chain. 8 corpus-derived regression tests (#489)
- Linter: `JBCT-PAT-02` (fork-join inside sequencer lambda) never fired — `isInsideFlatMap` compared the lambda against its own argument-expression node, so no lambda was ever "inside" a flatMap; additionally the lone-transform exclusion swallowed chained fork-joins (`Result.all(...).flatMap(...)`, the rule's own documented example). Both fixed; known text-heuristic limits (string-literal false-trigger, duplicate-lambda blind spot) documented and deferred to the #448 classifier which absorbs this rule (#454)
- Linter: live rule `JBCT-RET-06` (nullable parameter) had no severity entry in the default config; added at ERROR matching its null-safety sibling RET-03 (#454)
- Score: `RuleCategoryMapping` rebucketed to the live 41-rule registry (30 rules across the 6 score categories, 11 intentionally uncategorized style/log/zone rules) — previously keyed to retired rule IDs, so every diagnostic fell into the Pattern Purity default and the weighted score was meaningless. Unknown rule IDs now warn once per run and are excluded from the score; a registry↔mapping bijection test prevents silent rot (#449)
- Slice processor: plain interface factory methods with @ResourceQualifier parameters now generate correct resource provisioning and argument passing instead of zero-arg calls

## [0.6.1] - 2026-02-12

### Changed
- Slice init: updated default JBCT version to 0.6.1
- Build: Bump Pragmatica Lite to 0.11.3
- Build: Bump Aether to 0.8.2
- Routes: Add `.named()` call to generated routes for better tracing

## [0.6.0] - 2026-01-29

### Added
- Init: groupId validation for `jbct init -g` parameter (validates Java package name format)

### Changed
- Slice init: added tinylog dependencies (2.7.0) in test scope
- Slice init: added tinylog.properties configuration file in test resources
- Build: Bump Aether to 0.8.1
- CI: Re-enabled slice-processor-tests module
- Slice init: Java version 21 to 25
- Slice init: updated default versions (Pragmatica Lite 0.11.1, Aether 0.8.1, JBCT 0.6.0)
- Slice init: implementation pattern changed to record-based (nested record in interface)
- Slice init: removed Config dependency from template (factory now parameterless)
- Slice init: added annotation processor configuration to maven-compiler-plugin
- Slice init: added compilerArgs with `-Aslice.groupId` and `-Aslice.artifactId`
- Slice init: removed separate *Impl.java, SampleRequest.java, SampleResponse.java files
- Slice init: Request/Response/Error records now nested in @Slice interface
- Slice init: removed "Sample" prefix from Request/Response records
- Slice init: inner implementation record uses lowercased slice name, not "Impl"
- Init: version resolution uses running binary version as minimum (overrides GitHub API if newer)
- Init: version comparison uses Result-based `Number.parseInt()` from pragmatica-lite

### Fixed
- RFC-0004 compliance: removed non-standard slice-api.properties generation
- RFC-0004 compliance: slice manifests now include `slice.interface` property
- RFC-0004 compliance: renamed `impl.artifactId` to `slice.artifactId` in manifests
- RFC-0007 compliance: infrastructure dependencies now accessed via InfraStore instead of being proxied
- CollectSliceDepsMojo: now scans META-INF/slice/*.manifest instead of slice-api.properties
- VerifySliceMojo: validates manifest files instead of slice-api.properties
- PackageSlicesMojo: reads slice metadata from .manifest files
- SliceProjectValidator: checks for .manifest files instead of slice-api.properties
- SliceManifest: reads `slice.artifactId` property (was incorrectly reading `impl.artifactId`)
- PackageSlicesMojo: fixed JAR naming bug (empty artifact prefix in JAR names)
- PackageSlicesMojo: fixed JAR overwriting bug (multiple slices now create separate JARs)
- FactoryClassGenerator: infrastructure deps (CacheService, etc.) now use InfraStore.instance().get()
- FactoryClassGenerator: only slice dependencies are proxied via SliceInvokerFacade
- FactoryClassGenerator: reduced flatMap chain depth (e.g., 13 to 3 for UrlShortener with mixed deps)
- PackageSlicesMojo: bytecode transformation replaces UNRESOLVED versions with actual versions (strips semver prefix ^/~)
- Slice init: `ValidationError` now extends `Cause` (required for `Result.failure`)
- Slice init: added missing `Cause` import to template
- Slice init: `Promise.success()` instead of `Promise.successful()`
- Slice init: implemented `message()` method in `ValidationError.EmptyValue`
- Slice init: test template now uses monadic composition instead of `.unwrap()`
- FactoryClassGenerator: infra flatMaps now use proper nesting for variable scoping
- GenerateBlueprintMojo: UNRESOLVED dependency edges now properly resolved in graph traversal
- CollectSliceDepsMojo: improved base.artifact validation (rejects spaces, slashes)

## [0.5.0] - 2026-01-20

### Added
- Security: `SecurityError` sealed interface with `PathTraversalDetected`, `UrlRejected`, `DomainRejected` error types
- Slice verify: dependency scope validation for Aether runtime libraries
  - `jbct:verify-slice` now fails if `org.pragmatica-lite` or `org.pragmatica-lite.aether` dependencies are not `provided` scope
  - Prevents accidental bundling of runtime libraries in slice JARs
- Security: `PathValidation` utility rejects path traversal attempts (`..`, absolute paths, escaping base)
- Security: `UrlValidation` utility enforces HTTPS and GitHub domain whitelist
- Shared: `GitHubContentFetcher` extracts common GitHub API patterns (commit SHA, file discovery, downloads)
- Slice processor: `@Aspect` and `@Key` annotation processing for cache-wrapped slice methods
- Slice processor: `AspectModel` and `KeyExtractorInfo` model classes for aspect metadata
- Slice processor: method name validation per RFC-0001 (must match `^[a-z][a-zA-Z0-9]+$`)
- Slice processor test: `should_generate_slice_api_properties_with_correct_artifact_naming`

### Changed
- Build: bump Pragmatica Lite to 0.10.0
- Slice init: template dependencies now use `provided` scope for all runtime libs (`core`, `slice-annotations`, `slice-api`)
- Slice processor: proxy generation uses `TypeToken<R>` instead of `Class<R>` per aether SliceInvokerFacade
- Slice processor: `KeyExtractorInfo` uses Result-returning factories with validation
- Slice processor: `MethodModel` extracts lambdas to named methods, uses stream-based processing
- Linter: JBCT-STY-03 now flags `java.lang.*` FQCNs (use `@SuppressWarnings("JBCT-STY-03")` if unavoidable)
- Logging: `RouteConfigLoader` uses `System.Logger` (JEP 264) instead of `java.util.logging`
- Null policy: replaced nullable params with `Option<T>` in merge methods (`JbctConfig`, `RouteConfig`, `ErrorPatternConfig`)
- Null policy: `fullPath()`, `findMatchingPattern()`, `extractPromiseTypeArg()` now return `Option<T>`
- Composition: extracted complex lambdas to named methods across codebase
- Fork-Join: `SliceProcessor.generateArtifacts()` uses `Result.all()` for parallel generation
- Fork-Join: `ProjectInitializer` uses `Result.all()` for combining file lists
- Thread safety: `DependencyVersionResolver` uses eager initialization
- Return types: `GitHubVersionResolver.saveCache()/clearCache()` return `Result<Unit>`
- Refactor: `AiToolsUpdater` and `AiToolsInstaller` delegate to `GitHubContentFetcher`

### Fixed
- Slice processor: `ManifestGenerator` writes correct slice artifact naming (`groupId:artifactId-sliceName`)
- Slice processor: `FactoryClassGenerator` adds `.async()` for `Result` to `Promise` conversion (pragmatica-lite 0.10.0 compatibility)
- Slice packaging: filter out all `org.pragmatica-lite` and `org.pragmatica-lite.aether` artifacts from dependencies file
- Slice packaging: only include direct dependencies in dependencies file (exclude transitives of provided deps)
- Slice packaging: read slice/API artifact names from manifest instead of deriving from Maven artifact ID
- Slice processor: factory method name follows RFC-0001 (`{sliceName}` not `create`)
- Slice processor: `RouteSourceGenerator` escapes paths and query param names in generated code
- Slice processor: `DependencyModel.localRecordName()` handles empty strings and acronyms
- Security: URL validation in `JarInstaller` before downloading
- Security: path validation in `AiToolsUpdater` and `AiToolsInstaller` before file operations
- Null policy: `ErrorTypeDiscovery.causeType` field uses `Option<TypeMirror>`
- Null policy: `RouteSourceGenerator` uses `Option.option()` for safe Map lookups
- Factory methods: added `sliceDependency()`, `suppression()`, `releaseInfo()`, `blueprintConfig()`, `sourceLocation()`, `sourceSpan()`
- Immutability: `RouteDsl` and `Suppression` records use defensive copies (`List.copyOf()`, `Set.copyOf()`)
- Composition: `UpgradeCommand` uses `flatMap()` instead of `getOrThrow()`
- Thread safety: `Java25Parser` documented as non-thread-safe with ThreadLocal recommendation

## [0.4.9] - 2026-01-15

### Added
- HTTP routing: generate `RouteSource` and `SliceRouterFactory` from TOML config
- HTTP routing: DSL parser for route definitions (`"GET /users/{id:Long}"`)
- HTTP routing: compile-time error type discovery with pattern matching
- HTTP routing: service loader file generation for factory discovery
- HTTP routing: full parameter support (path, query, body in any combination up to 5 params)
- Slice packaging: fat JAR creation with bundled external dependencies
- Slice packaging: dependency file generation (`META-INF/dependencies/{FactoryClass}`)
- Slice packaging: MANIFEST.MF entries (`Slice-Artifact`, `Slice-Class`)
- Slice packaging: application shared code inclusion in impl JAR
- Docs: Aether ClassLoader hierarchy and dependency model in runtime.md
- Project init: dynamic version resolution from GitHub Releases (pragmatica-lite, aether, jbct)
- Project init: version override CLI options (`--pragmatica-version`, `--aether-version`, `--jbct-version`)
- AI tools: offline cache at `~/.jbct/cache/ai-tools/` for faster installs

### Changed
- Slice init: pom template includes `slice-annotations` and `slice-api` dependencies with `${aether.version}` property
- Slice packaging: API JAR now includes nested request/response types
- Slice packaging: request/response classes handled as API types when nested
- AI tools: fetch from coding-technology repo using GitHub Tree API (dynamic file discovery)
- AI tools: removed bundled copies, now fetched from GitHub on demand
- Build: added tinylog 2.7.0 as SLF4J provider (eliminates "No SLF4J providers" warnings)

### Fixed
- SliceManifest: nested class path conversion (`Outer.Inner` → `Outer$Inner.class`)
- Slice packaging: Aether runtime libs (`slice-annotations`, `slice-api`, `infra-api`) excluded from bundling and dependency file
- Formatter: wildcard spacing in generics (`Route< ?>` → `Route<?>`)
- Formatter: single fluent call kept inline (`none().toResult(cause)` not broken across lines)
- HTTP routing: handler existence validation with compile error on missing methods
- HTTP routing: parameter count validation (max 5 parameters per route)
- HTTP routing: nested type name collision using qualified names in error mapper
- HTTP routing: consolidated pattern matching (removed duplicate regex-based matchesGlob)
- HTTP routing: routes-base.toml merge support for shared config inheritance
- HTTP routing: replaced null with Option in ErrorTypeMapping

## [0.4.8] - 2026-01-10

### Added
- AI tools: add `code-reviewer` agent for general-purpose code reviews
- AI tools: add `jbct-review` skill for parallel JBCT compliance checking
- AI tools: add `fix-all` skill for systematic issue resolution
- AI tools: add `fold-alternatives.md` pattern documentation
- Project init: create `CLAUDE.md` with JBCT workflow and conversation style guidelines
- Slice processor: factory returns `Promise<SliceType>` with `Aspect` parameter
- Slice processor: add `createDynamic()` for runtime-configurable aspects (logging/metrics)
- Docs: add slice factory generation design document

### Changed
- AI tools: sync `jbct-coder` and `jbct-reviewer` agents from coding-technology
- AI tools: update skill files from coding-technology
- AI tools: install to project's `.claude/` directory instead of `~/.claude/`
- AI tools: `jbct update` now updates project-local AI tools

### Fixed
- Slice init: Forge URL port corrected from 8080 to 8888

## [0.4.7] - 2026-01-10

### Changed
- Build: bump Pragmatica Lite to 0.9.10
- Docs: update README with missing CLI options (--config, --version, --artifact-id, etc.)
- Docs: fix CLAUDE.md pragmatica-lite version (0.9.4 → 0.9.10) and lint rule count (36 → 37)

### Fixed
- Performance: eliminate O(n²) measurement patterns in formatter causing memory spikes on complex generic files
  - Skip measureWidth when hasComplexArgs/hasExistingBreaks already triggers breaking (printArgs, printParams, printRecordComponents)
  - Replace text() extraction with CST structure checks for method call detection (printPostfix)
  - Pre-compute operand info to avoid per-child measurements in string concatenation wrapping (printAdditive)
  - Combine duplicate loops in hasComplexArguments to single pass
- Formatter: prevent blank line accumulation between TypeParams and return type in method declarations
- Formatter: prevent leading newline accumulation in files without package declaration

## [0.4.6] - 2026-01-05

### Changed
- Build: bump Pragmatica Lite to 0.9.7
- Slice processor: refactored models to use `Result<T>` instead of exceptions (JBCT compliance)

### Fixed
- Parser: add support for array creation with dimension expressions (`new int[10][]`, `new float[rows][cols]`)
- Formatter: remove errant space before `<` in generics with lowercase type names (`new router<>()`, `record router<T>`)
- Formatter: add veto rules to prevent unwanted spaces in edge cases:
  - No space before `)` after postfix `++`/`--` (`i++)` not `i++ )`)
  - No space before `>` after `]` in generics (`Promise<float[]>` not `Promise<float[] >`)
  - No space after `@` in annotations (`@Override` not `@ Override`)
  - No space after `.` except for varargs `...`
- Style: remove FQCN usage in LintConfig, CstPrinter, ProjectInitializer, CstParsingUtilitiesRule
- Style: rename factory methods to follow `TypeName.typeName()` convention (MethodModel, DependencyModel, SliceModel, SpacingContext)
- Style: replace null returns with `Option.onPresent()` in CstPrinter

## [0.4.5] - 2026-01-02

### Changed
- Build: bump Pragmatica Lite to 0.9.4

## [0.4.4] - 2026-01-01

### Changed
- AI tools: update to JBCT v2.0.10 with Pragmatica Lite Core 0.9.3
- Build: bump Pragmatica Lite to 0.9.3

## [0.4.3] - 2025-12-31

### Added

### Changed
- Build: jbct-maven-plugin moved to dedicated profile (skip with `-Djbct.skip`)
- Build: java-peglib dependency updated to 0.1.8

### Fixed
- Parser: error messages now report actual error position instead of 1:1 (farthest failure tracking)
- Formatter: `}else {` spacing (now `} else {`)
- Formatter: args/params/record components alignment to opening paren when source has newlines
- Formatter: try-with-resources alignment to opening paren
- Formatter: nested blocks inside lambda bodies now properly indented
- Formatter: constructor call args alignment (`new Type(args...)`)
- Formatter: record declaration component alignment
- Formatter: first arg/param/component stays on same line as opening paren
- Formatter: chain alignment for constructor calls (`new Type().method1().method2()`)
- Linter: JBCT-SEAL-01 false positive for sealed interfaces (now checks Modifier nodes)
- Linter: JBCT-PAT-02 no longer flags method references as fork-join (e.g., `Result::allOf`)

## [0.4.2] - 2025-12-30

### Fixed
- Parser: `record` as contextual keyword - works as method name, type name, field type, variable type

## [0.4.1] - 2025-12-30

### Added
- Linter: @SuppressWarnings support for JBCT rules (`@SuppressWarnings("JBCT-RET-01")`, `@SuppressWarnings("all")`)

### Changed

### Fixed
- Parser: add support for array type method references (`String[]::new`, `int[]::new`, `int[]::clone`)
- Linter: JBCT-ACR-01 false positives for 2-letter prefixes (LParen, RParen, etc.)

## [0.4.0] - 2025-12-29

### Added
- Enable jbct-maven-plugin for the project itself (dogfooding)

### Changed

### Fixed
- Formatter: remove trailing comma corruption in enums (was adding extra comma line)

## [0.3.12] - 2025-12-29

### Added
- **Aether Slice Support**: New `slice-processor` module for Aether slice development
  - Annotation processor generates API interfaces, proxy classes, factory classes, and manifests from `@Slice`-annotated interfaces
  - Maven plugin goals: `jbct:collect-slice-deps` and `jbct:verify-slice`
  - CLI commands: `jbct init --slice` and `jbct verify-slice`
  - Model classes: `SliceModel`, `MethodModel`, `DependencyModel`
  - Generators: `ApiInterfaceGenerator`, `ProxyClassGenerator`, `FactoryClassGenerator`, `ManifestGenerator`
  - Deploy scripts: `deploy-forge.sh`, `deploy-test.sh`, `deploy-prod.sh` with Maven profiles for Aether deployment
- **JBCT-SLICE-01**: New lint rule enforces slice API usage
  - External slice dependencies must import from `.api` subpackage
  - Requires `slicePackages` configuration in `jbct.toml` (opt-in rule)
  - Detects violations from both slice and non-slice code

### Fixed
- Parser: add word boundaries to type declaration keywords (`class`, `interface`, `enum`, `record`)
- Grammar: identifiers like `className`, `interfaceType`, `enumValue`, `recordData` now parse correctly

## [0.3.11] - 2025-12-28

### Fixed
- Parser: add TypeExpr rule for class literals (`byte[].class`, `int.class`, `String[].class`)
- Parser: add lookahead to RefType to prevent capturing `.` before keywords like `.class`

### Added
- Unit tests for primitive and reference type class literals
- Golden test ClassLiterals.java for class literal formatting

## [0.3.10] - 2025-12-27

### Fixed
- Parser: keyword-prefixed identifiers no longer corrupted (e.g., `newState` → `new State`)
- Grammar: add word boundary checks for keywords in Primary, PrimType, Modifier, Literal, LocalVarType, LambdaParam
- Grammar: add cut operators to MethodDecl and ConstructorDecl for better error messages
- Grammar: add word boundaries to all statement keywords (if, while, for, do, try, switch, synchronized, catch, finally)
- Grammar: fix `throw` in switch expression arrows (was using raw literal instead of ThrowKW)
- Grammar: fix `when` contextual keyword to prevent misparsing (e.g., `whenever` as `when` + `ever`)

### Added
- Golden test for keyword-prefixed identifiers (newState, oldState, thisValue, etc.)
- Unit tests for keyword boundary parsing
- Unit tests for switch expressions with throw and when guards
- Debug technique documentation in CLAUDE.md (binary search for parse errors)

## [0.3.9] - 2025-12-27

### Added
- 3 new lint rules (36 total):
  - JBCT-ACR-01: Acronym naming convention (HTTPClient → HttpClient)
  - JBCT-SEAL-01: Error interfaces should be sealed
  - JBCT-PAT-02: No Fork-Join inside Sequencer (Result.all inside flatMap)
- FileCollector utility for shared file collection logic
- HttpClients singleton for shared HTTP client instances
- AbstractJbctMojo base class for Maven plugin mojos
- LintContext.fromConfig() factory method
- CstNodes.packageName() helper method

### Changed
- Update to Pragmatica Lite 0.9.0
- Replace local TOML parser with pragmatica-lite toml module
- Maven plugin now reads configuration from jbct.toml (same as CLI)
- Version now read from resource file instead of hardcoded string
- AI tools: sync to JBCT v2.0.7
- AI tools: replace `Causes.forValue()` with `forOneValue()` in examples
- AI tools: replace `Verify.ensureFn()` with `.filter(cause, predicate)` pattern

### Fixed
- Add missing JBCT-ACR-01, JBCT-SEAL-01, JBCT-PAT-02 to LintConfig defaults
- Remove unused includes/excludes Maven parameters
- Fix formatting issues in UpgradeCommand
- Fix spacing in CstReturnKindRule
- Remove unused Trivia import from CstFormatter
- Fix JbctConfig.merge() to use value equality instead of reference equality

### Removed
- Formatter and Linter interfaces (unnecessary abstraction)
- Unused description() method from CstLintRule and all implementations
- Unused isDirty() method from SourceFile
- Unused resourcePath parameter from AiToolsInstaller

## [0.3.8] - 2025-12-23

### Fixed
- Formatter: align multiline record components to opening paren (like method parameters)
- Formatter: preserve pre-broken parameter/component alignment when source has newlines

## [0.3.7] - 2025-12-23

### Fixed
- Formatter: preserve space before underscore/dollar-prefixed identifiers (e.g., `Type _field`)

## [0.3.6] - 2025-12-22

### Added
- 10 new lint rules (33 total):
  - JBCT-STY-04: Utility class pattern (final class → sealed interface)
  - JBCT-STY-05: Method reference preference (lambda → method ref)
  - JBCT-STY-06: Import ordering (java → javax → pragmatica → third-party)
  - JBCT-STATIC-01: Prefer static imports for Pragmatica factories
  - JBCT-UTIL-01: Use Pragmatica parsing utilities (Number.parseInt, etc.)
  - JBCT-UTIL-02: Use Verify.Is predicates for validation
  - JBCT-NEST-01: No nested monadic operations in lambdas
  - JBCT-ZONE-01: Step interfaces should use Zone 2 verbs
  - JBCT-ZONE-02: Leaf functions should use Zone 3 verbs
  - JBCT-ZONE-03: No zone mixing in sequencer chains
- Cut operators in Java 25 grammar for better error messages
- Comprehensive lint rule test suite (114 tests)

## [0.3.5] - 2025-12-21

### Fixed
- Parser: compound assignment operators (`+=`, `-=`, `*=`, `/=`, `%=`, `&=`, `|=`, `^=`, `<<=`, `>>=`, `>>>=`) no longer break into separate tokens

## [0.3.4] - 2025-12-21

### Fixed
- Formatter: preserve required semicolon after enum constants when fields follow

## [0.3.3] - 2025-12-21

### Fixed
- Parser: `assertEquals` no longer parsed as assert statement (keyword word-boundary check)
- Parser: `String.class` no longer produces extra dot (QualifiedName lookahead fix)
- Formatter: `Result.<Integer>failure` no longer has space after `>` (PostOp special handling)
- Build: Fix central-publishing-maven-plugin activation for Maven Central deployment

## [0.3.2] - 2025-12-20

### Changed
- Parser grammar improvements

## [0.3.1] - 2025-12-18

### Added
- TextBlocks golden example for formatter verification

### Changed
- Regenerated parser with ADVANCED error reporting mode (Rust-style diagnostics)
- Improved golden test diff output for easier debugging

## [0.3.0] - 2025-12-18

### Changed
- Complete migration from JavaParser to CST-based implementation
- JbctFormatter now delegates to CstFormatter
- JbctLinter now delegates to CstLinter
- Removed JavaParser dependency entirely

### Removed
- JavaParser-based formatter (printer package)
- JavaParser-based lint rules (rules package)
- JavaParser git submodule dependency

## [0.2.0] - 2025-12-13

### Added
- CLI tool (`jbct`) with format, lint, check, upgrade, init, and update commands
- `jbct upgrade` command for self-updating from GitHub Releases
- `jbct init` command for scaffolding new JBCT projects with AI tools
- `jbct update` command for syncing AI tools from coding-technology repo
- TOML configuration system with priority chain (CLI > project > user > defaults)
- Distribution packaging (tar.gz/zip with shell wrappers)
- Maven plugin with format, format-check, lint, and check goals
- 23 lint rules for JBCT compliance:
  - JBCT-RET-01: Business methods must use T, Option, Result, or Promise
  - JBCT-RET-02: No nested wrappers
  - JBCT-RET-03: Never return null
  - JBCT-RET-04: Use Unit instead of Void
  - JBCT-RET-05: Avoid always-succeeding Result (return T directly)
  - JBCT-VO-01: Value objects should have factory returning Result<T>
  - JBCT-VO-02: Don't bypass factory with direct constructor calls
  - JBCT-EX-01: No business exceptions
  - JBCT-EX-02: Don't use orElseThrow()
  - JBCT-NAM-01: Factory method naming conventions
  - JBCT-NAM-02: Use Valid prefix, not Validated
  - JBCT-LAM-01: No complex logic in lambdas
  - JBCT-LAM-02: No braces in lambdas (extract to methods)
  - JBCT-LAM-03: No ternary in lambdas (use filter or extract)
  - JBCT-UC-01: Use case factories should return lambdas
  - JBCT-PAT-01: Use functional iteration instead of raw loops
  - JBCT-SEQ-01: Chain length limit (2-5 steps)
  - JBCT-STY-01: Prefer fluent failure style (cause.result())
  - JBCT-STY-02: Prefer constructor references (X::new)
  - JBCT-STY-03: No fully qualified class names in code
  - JBCT-LOG-01: No conditional logging
  - JBCT-LOG-02: No logger as method parameter
  - JBCT-MIX-01: No I/O operations in domain packages
- Custom JBCT formatter with:
  - Method chain alignment to receiver
  - Argument/parameter alignment to opening paren
  - Import grouping (pragmatica, java/javax, static)
- GitHub Actions CI workflow with release automation
- Installation script (`install.sh`) for quick setup
- Maven Central publishing configuration

### Technical
- CST-based parser using java-peglib PEG grammar
- Uses pragmatica-lite http-client for HTTP operations
- Supports Java 25
