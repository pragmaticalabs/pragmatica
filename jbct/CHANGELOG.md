# Changelog

## [Unreleased]

### Changed
- Linter: package globs (`excludePackages` and every `[lint.layers]` list) — `**` now means **zero or more** package segments, so `com.example.core.**` matches the bare package `com.example.core` itself as well as everything beneath it. Previously it compiled to `com\.example\.core\..*` and, being fully anchored, never matched the declaring package: a class sitting directly in its own declared layer package silently fell out of that layer and was reclassified by whatever broader glob caught it, manufacturing bogus layering findings. The canonical example in `LayerConfig`'s own javadoc exhibited the bug. Measured against a realistic aether `[lint.layers]` config (domain catch-all + the five cloud-provider adapter globs) over the 46 provider files: **37 bogus findings before (32 `JBCT-ARCH-01` + 5 `JBCT-ARCH-02`), 0 after**; aether's default-config baseline is unchanged (1 `JBCT-ARCH-02`), since aether declares no layers. The two duplicated `globToRegex` copies — `LintContext` and `LayerClassifier`, the latter's comment already conceding it "mirrors LintContext" — are replaced by one canonical `PackageGlob` compiler in `jbct-core`, so the two config surfaces can no longer drift apart. Single `*` is unchanged (exactly one segment); `**` mid-glob spans any depth including none (`com.**.impl` matches `com.impl`), and a sibling package is still correctly rejected (`com.example.core.**` does not match `com.example.corex`). Widening `excludePackages` carries no suppression risk: no project in the repo configures it (the root `jbct.toml` uses the separate `[files].excludes` path mechanism). +31 tests (`PackageGlobTest`, `LintContextTest`) plus additive `LayerClassifier` glob-semantics cases; 653 green
- jbct-derive: entry gate now requires **Q4 (consistency contract) to be scoped** — a `system`-scoped consistency answer is rejected as `UNSCOPED`, matching Q3 (its per-data-class sibling) and all four golden sheets, which scope Q4 by `data-class`/`path`. Consistency is a per-data-class property in the method; a single system-wide contract is the one-size-fits-all smell the gate surfaces. Resolves the pending #443 Q4-scoping ruling; SPEC annotated, +1 gate test
- Linter: `JBCT-RET-08` (null literal as call argument) exemption extended from just `.or(null)` to the distinctive JDK boundary-adapter call names `orElse`, `compareAndSet`, `getAndSet` — an `Optional`/`Stream` nullable bridge and `java.util.concurrent.atomic` empty-sentinels are boundary adapters, not absence leaking into business logic, and cannot be `Option`-wrapped. Names are distinctive (no business-method homonyms) so the FN surface is negligible; common JDK names (`set`/`init`/`load`/`invoke`) are deliberately NOT exempted (dispositioned by explicit suppression instead). Corpus contact: of 110 aether sites, ~85% were JDK/framework-boundary or a legitimate data table (the same "correct JDK-boundary" pattern that got RET-08's null-*comparison* arm dropped) — the exemption clears the distinctive-name subset; the rest were fixed (`TypeMapper` type table Option-ified, `printQuery` Option-cored) or justified-suppressed (atomic `.set(null)`, `SSLContext.init`/`KeyStore.load`, reflective static invoke, JMX, Jackson view-DTO absent fields). Three new fixtures (#493)
- Linter: `JBCT-SEAL-02` now exempts the `record unused()` sealed-interface placeholder-filler idiom — a permitted-subtype stub for a sealed cause hierarchy that has no fixed-message variants of its own is a structural placeholder, not a fixed-message cause. Corpus contact showed the 50 same-file-resolvable hits split into 20 real named fixed-message causes (converted to the per-cause `enum … { INSTANCE }` idiom in aether/**) + 30 `unused()` fillers (part of a 136-site repo-wide idiom); the exemption removes the FP class rather than churning the idiom, keeping named-cause detection intact. Two FP-guard fixtures added (#493)
- Linter: `JBCT-ORD-01` member ordering reconciled to the book and the codebase's de-facto convention. **Use-case order corrected** from the #453 ticket's inverted `records → steps → factory → execute` to the manuscript's canonical `records → execute → step interfaces → static factory` (execute early, factory last — per `project-structure.md`'s numbered list and every worked example; the corpus writes execute-early, so the old rule was fighting it). **Value-object order relaxed** to fit real idioms: the static factory and accessors now share one rank (their relative order is not enforced, so serialization pairs like `toJson`/`fromJson` and private factory-helpers near their use stop flagging), and PRIVATE static-final constants (validation patterns, formatters, private pre-built instances placed at the bottom) are exempt from constants-first — public/package-API constants must still come first. Corpus swept to 0: use-case violations cleared by the order fix; of 9 value-object residuals, 7 were private-constants-at-bottom (cleared by the exemption), 1 file-local package-private constant made `private` (`ApplyState`), 1 package-API sentinel set hoisted to the top (`ArtifactDependency`). New `FileTypeClassifier.isPrivate` helper; +5 rule tests. The book's `project-structure.md` value-object note is reconciled separately in the book repo
- Linter: `JBCT-NAM-05` (test-method naming) relaxed from strict 3-segment `methodName_scenario_expectation` to **≥2 underscore-separated segments** (`method_[scenario_]expectation`), matching the codebase's pervasive, readable 2-segment convention. Corpus-validation via the plugin's `jbct.includeTests` opt-in surfaced 578 hits in just 4 aether modules (thousands corpus-wide), all the readable `method_expectation` / `scenario_expectation` form — the strict rule was over-flagging a good de-facto convention, not catching real defects. Single-word names (`testFoo`, `shouldWork`) are still flagged. Plugin test-source support confirmed already wired (the `jbct.includeTests` flag collects `src/test/java`); the project `CLAUDE.md` test-naming note is relaxed to match (the book's `chapter-summaries.md` statement reconciled separately in the book repo)
- Linter: `JBCT-BND-01` restored to its design ERROR severity — all three corpus sites dispositioned same-day (AwsLoadBalancerProvider fixed by aether-main, SliceStore CompletableFuture eviction hop removed; #493)
- Linter: `JBCT-RET-06`, `JBCT-TOT-01`, `JBCT-TOT-02` all at ERROR — the full aether corpus burn-down (#489) completed same release: 143 RET-06 sites resolved (69 real Option/Verify totalizations, the rest justified parse-don't-validate/framework-boundary suppressions) and all mapper-safety findings cleared (real fixes + rule FP corrections — see Fixed). The interim WARNING window existed only during the burn-down

### Added
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
- Linter: `JBCT-SLICE-01` config entry and the dead `slicePackages` / `LintContext` slice-pattern machinery behind it. The rule was declared at ERROR in the default config but had no rule implementation, scorer, or fixture referencing it, and the slice-processor does not check cross-slice import boundaries either — it only generates factories/manifests for `@Slice`-annotated interfaces. Cross-slice import-boundary enforcement is deferred to the layering-rules engine (#452, #450)

### Changed
- Linter: method-shape classifier (#448 phase 3) — a lambda-argument descent primitive (`chainLambdaLinks` + `classifyLambdaBody`, which the phase-1/2 spine extraction deliberately discarded) plus the absorption of three string-heuristic rules into classifier facets. `JBCT-PAT-02` (fork-join nested in a sequencer lambda) became a true structural facet locating `flatMap`/`andThen` argument lambdas via the descent. `JBCT-ZONE-03` (Zone-3 verb in a `map`/`flatMap` lambda) and `JBCT-NEST-01` (nested monadic ops) kept their exact detection logic but now run it over `MapperSafety.blankNonCode`-masked text — a deliberate scoping call: full structural re-classification would drop real single-combinator re-chains (`inner(x).map(f)` classifies LEAF), a regression, so only the masking was adopted (a safety-net that neutralizes a verb/op/join token spelled inside a string or comment). All three rules keep their IDs, severities, score categories, messages, and byte-identical reported lines. Non-regression is guaranteed by construction (NEW ⊆ OLD; every dropped hit would be a masked comment/string token) and confirmed empirically: the full 67-module corpus fires byte-identical (ZONE-03 296, NEST-01 202, PAT-02 0 — zero added, zero dropped), so nothing downstream changes. Also: PAT-02 gained the `shouldLint`/`excludePackages` gate its siblings already had (pre-facet gap), and `monadicOpCount` precompiles its alternation

### Fixed
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
