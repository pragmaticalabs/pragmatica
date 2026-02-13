# Changelog

All notable changes to Pragmatica will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

## [0.16.0] - Unreleased

### Added

### Fixed

### Changed

## [0.15.1] - 2026-02-12

### Added
- ClusterEventAggregator for structured event collection (topology, leader, quorum, deployment events)
- MetricsCollector invocation metrics in cluster-wide gossip
- MetricsCollector topology change handlers for departed node cleanup

### Fixed
- SliceStore.unloadSlice() stuck in UNLOADING state when slice loading had previously failed
- Shared dependency loading fails for runtime-provided libraries (e.g. `core` embedded in shaded JAR)
- Orphaned SliceNodeKey entries not cleaned up after undeploy during leader change
- E2E multi-instance deployment test used hardcoded instance count instead of cluster size
- E2E BeforeEach cleanup now retries undeploy to handle leader changes during teardown
- Pre-populate DHT ring with known peers and harden distributed operations
- Distributed DHT wiring — DistributedDHTClient replaces LocalDHTClient for cross-node artifact resolution

### Changed
- Disabled TTM E2E tests (trivial checks not worth 90-minute 5-node cluster overhead)

## [0.15.0] - 2026-02-02

### Added
- Monorepo consolidation of three projects:
  - pragmatica-lite (v0.11.3) - Core functional library
  - jbct-cli (v0.6.1) - CLI and Maven plugin for JBCT formatting/linting
  - aetherx (v0.8.2) - Distributed runtime
- Unified version management across all modules
- Consolidated documentation structure
- Moved `cluster` module from aether to integrations (generic distributed networking)
- AppHttpServer immediate retry on node departure (no more 5-second timeout wait)
- Production tinylog configuration for aether/node
- Tinylog format now includes thread name: `[{thread}]`
- Request ID logging for critical log statements in AppHttpServer and SliceInvoker
- Blueprint CLI commands: `list`, `get`, `delete`, `status`, `validate` (also in REPL)
- Blueprint REST API endpoints: GET/DELETE `/api/blueprint/{id}`, GET `/api/blueprints`,
  GET `/api/blueprint/{id}/status`, POST `/api/blueprint/validate`
- Consolidated startup banner showing node configuration (ID, ports, peers, TTM, TLS)

### Changed
- All modules now use version 0.15.0
- Root POM provides dependency management for entire ecosystem
- Unified CI workflows at monorepo root
- E2E and Forge tests moved to `-Pwith-e2e` profile (require examples to be installed first)
- Standardized tinylog configurations across all modules (24 files)
- Added Fury and Netty logging suppression to all test configs
- Comprehensive logging level overhaul in aether module:
  - Hot paths (SliceInvoker, InvocationHandler, AppHttpServer) moved to DEBUG/TRACE
  - Routine operations (deployment, slice lifecycle) moved to DEBUG
  - Important events (leader/quorum changes, rolling updates) kept at INFO
  - Production logs are now scannable and concise

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
