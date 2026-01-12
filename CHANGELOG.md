# Changelog

## [0.4.9] - Unreleased

### Added
- Slice packaging: fat JAR creation with bundled external dependencies
- Slice packaging: dependency file generation (`META-INF/dependencies/{FactoryClass}`)
- Slice packaging: MANIFEST.MF entries (`Slice-Artifact`, `Slice-Class`)
- Slice packaging: application shared code inclusion in impl JAR
- Docs: Aether ClassLoader hierarchy and dependency model in runtime.md

### Changed
- Slice packaging: API JAR now includes nested request/response types
- Slice packaging: request/response classes handled as API types when nested

### Fixed
- SliceManifest: nested class path conversion (`Outer.Inner` → `Outer$Inner.class`)

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

### Added

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

### Added

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

### Added

### Changed
- Build: bump Pragmatica Lite to 0.9.4

### Fixed

## [0.4.4] - 2026-01-01

### Added

### Changed
- AI tools: update to JBCT v2.0.10 with Pragmatica Lite Core 0.9.3
- Build: bump Pragmatica Lite to 0.9.3

### Fixed

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

### Added

### Changed

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
