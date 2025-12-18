# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.3.0] - 2025-12-18

### Added
- **AetherNode** - Complete node assembly wiring all components together
- **AetherNodeConfig** - Node configuration with topology and protocol settings
- **AetherNode integration tests** - Cluster formation, consensus, and replication tests
- **ClusterDeploymentManager** - Leader-based allocation, reconciliation, scale up/down
- **EndpointRegistry** - Round-robin load balancing for slice endpoints
- **Blueprint DSL parser** - Parse blueprint definitions from text format
- **SliceStore implementation** - Complete slice lifecycle management
- **SliceClassLoader** - Isolated class loading for slices
- **DependencyResolver** - Resolve slice dependencies with cycle detection
- **SliceRegistry** - Track loaded slice instances
- **Manifest-based slice discovery** - Discover slices via MANIFEST.MF

### Changed
- Replaced `EntryPoint` with `SliceMethod` for type-safe method definitions
- Updated to Pragmatica Lite 0.8.4
- Applied JBCT compliance fixes (Result<Unit>, functional patterns)

### Fixed
- Option timeout handling in SliceState using Option<TimeSpan>
- LoadedSlice.slice() now returns Slice directly instead of Result<Slice>

## [0.2.0] - 2025-11-01

### Added
- Core slice lifecycle states and transitions
- Artifact type system (GroupId, ArtifactId, Version)
- KV-Store schema (AetherKey, AetherValue)
- Rabia consensus implementation
- Leader manager for deterministic leader selection
- NodeDeploymentManager implementation
- Example slice implementation (StringProcessorSlice)

## [0.1.0] - 2025-10-01

### Added
- Initial project structure
- Slice API definitions
- Basic cluster module with Rabia protocol
