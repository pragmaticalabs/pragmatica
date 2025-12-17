# Changelog

## [0.3.0] - Unreleased

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
