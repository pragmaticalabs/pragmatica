# Changelog

All notable changes to Pragmatica will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [0.15.0] - 2026-02-02

### Added
- Monorepo consolidation of three projects:
  - pragmatica-lite (v0.11.3) - Core functional library
  - jbct-cli (v0.6.1) - CLI and Maven plugin for JBCT formatting/linting
  - aetherx (v0.8.2) - Distributed runtime
- Unified version management across all modules
- Consolidated documentation structure
- Moved `cluster` module from aether to integrations (generic distributed networking)

### Changed
- All modules now use version 0.15.0
- Root POM provides dependency management for entire ecosystem
- Unified CI workflows at monorepo root
- E2E and Forge tests moved to `-Pwith-e2e` profile (require examples to be installed first)

### Technical Notes
- Group IDs preserved for Maven Central compatibility:
  - `org.pragmatica-lite` for core, integrations, jbct modules
  - `org.pragmatica-lite.aether` for aether modules
- Build: `mvn install -DskipTests` (bootstraps jbct-maven-plugin automatically)
- E2E tests: `mvn verify -Pwith-e2e -pl aether/e2e-tests,aether/forge/forge-tests`

---

## Pre-Monorepo History

Historical changelogs for individual projects:

- [Pragmatica Lite CHANGELOG](core/CHANGELOG.md)
- [JBCT CHANGELOG](jbct/CHANGELOG.md)
- [Aether CHANGELOG](aether/CHANGELOG.md)
