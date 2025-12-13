# Changelog

## [0.2.0] - 2025-12-13

### Added
- CLI tool (`jbct`) with format, lint, and check commands
- Maven plugin with format, format-check, lint, and check goals
- 13 lint rules for JBCT compliance:
  - JBCT-RET-01: Business methods must use T, Option, Result, or Promise
  - JBCT-RET-02: No nested wrappers
  - JBCT-RET-03: Never return null
  - JBCT-RET-04: Use Unit instead of Void
  - JBCT-VO-01: Value objects should have factory returning Result<T>
  - JBCT-VO-02: Don't bypass factory with direct constructor calls
  - JBCT-EX-01: No business exceptions
  - JBCT-EX-02: Don't use orElseThrow()
  - JBCT-NAM-01: Factory method naming conventions
  - JBCT-NAM-02: Use Valid prefix, not Validated
  - JBCT-LAM-01: No complex logic in lambdas
  - JBCT-UC-01: Use case factories should return lambdas
  - JBCT-PAT-01: Use functional iteration instead of raw loops
- Custom JBCT formatter with:
  - Method chain alignment to receiver
  - Argument/parameter alignment to opening paren
  - Import grouping (pragmatica, java/javax, static)
- Build scripts for JavaParser submodule integration
- GitHub Actions CI workflow
- Maven Central publishing configuration

### Technical
- JavaParser included as git submodule with shading/relocation
- Supports Java 25
