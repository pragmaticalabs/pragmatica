# Changelog

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
