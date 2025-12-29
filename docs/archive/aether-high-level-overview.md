# Aether - Intelligent Distributed Runtime Environment

## Project Overview

Aether (v0.6.2) is a clusterized runtime environment built on Pragmatica Lite that transforms monolithic applications
into distributed systems transparently. The runtime absorbs complexity instead of adding weight to applications.

## Core Concept

**Runtime Intelligence**: Unlike traditional frameworks that force applications to add weight, Aether's runtime handles
as much complexity as applications are ready to offload. This enables business teams to work with high-level APIs while
the runtime manages distributed concerns.

## Architecture

### Slice-Based Deployment

- **Slices**: Containerized application components with independent lifecycle
- **Slice Methods**: Type-safe service methods with parameter validation
- **Slice Registry**: Discovery and lifecycle management for deployed components
- **Dynamic Loading**: Maven artifact resolution with ClassLoader isolation

### Multi-Cloud Deployment

- **Unified Abstraction**: Single deployment model across public/private clouds
- **Resource Allocation**: Automatic scaling and resource management
- **Health Monitoring**: Failure detection and recovery orchestration
- **Configuration Management**: Environment-specific deployment configuration

## Module Structure

### Core Modules

- **slice-api/** - Slice interface definitions
- **slice/** - Slice management implementation, ClassLoader handling
- **node/** - Main runtime node implementation
- **cluster/** - Consensus protocol implementation (Rabia)
- **common/** - Shared utilities (MessageRouter, etc.)
- **example-slice/** - Reference slice implementation
- **demo-order/** - Complete order domain demo (5 slices)
- **cli/** - Command-line interface for cluster management

### Slice API Components

- **Slice.java** - Core lifecycle interface (start/stop)
- **SliceMethod.java** - Method definitions with type tokens
- **MethodName.java**, **ArtifactId.java** - Type-safe identifiers
- **SliceRegistry.java** - Registry interface for slice discovery

### Slice Management

- **SliceStore.java** - Slice loading and lifecycle management
- **SliceClassLoader.java** - Isolation and resource management
- **Repository.java** - Artifact resolution and dependency management
- **Descriptor.java** - Slice deployment configuration

### Architecture Decisions (Resolved) ✅

- **Slice Isolation**: Hybrid ClassLoader model (shared framework, isolated slices)
- **Security vs Performance**: Balanced approach with framework sharing
- **Inter-Slice Communication**: Type-safe calls using shared framework types

### In Progress 🔄

- Slice Registry implementation (slice discovery and lifecycle)
- Deployment orchestration system
- Health monitoring and failure recovery

### Planned Features 📋

- Multi-cloud deployment abstraction
- Layered AI integration (SLM → LLM, see [ai-integration.md](ai-integration.md))
- Security manager integration
- Slice versioning and hot updates

## Technical Implementation

### Slice Isolation Model

```java
// Hybrid ClassLoader approach - optimal balance
var loader = new SliceClassLoader(urls, Slice.class.getClassLoader());
// Slices isolated from each other, share Pragmatica framework
```

**Benefits**:

- Balanced security (slices cannot interfere)
- Performance (shared framework, JIT optimizations)
- Clean serialization (framework types work across boundaries)
- Memory efficient (no framework duplication)

### Slice Lifecycle Management

```java
Promise<ActiveSlice> loadSlice(Artifact artifact, Repository repository)
Promise<Unit> unload(Artifact artifact)
List<LoadedSlice> loadedSlices()
```

### Service Discovery

- ServiceLoader pattern for slice discovery
- Type-safe method registration via SliceMethod
- Automatic lifecycle management
- Resource cleanup on unload

## Integration with Pragmatica Lite

### Dependencies

- **org.pragmatica-lite:core** - Promise/Result monads, error handling (external dependency)

### Internal Modules

- **common/** - MessageRouter, utilities
- **cluster/** - Distributed consensus (Rabia protocol, KV-Store, LeaderManager)

### API Consistency

- All async operations return Promise<T>
- Error handling via Result<T> and Cause
- Interface-based design patterns

## Development Phases

### Phase 1: Slice Architecture ✅

- Slice isolation model ✅
- Basic lifecycle management ✅
- ServiceLoader discovery ✅

### Phase 2: Deployment Orchestration ✅

- Slice registry implementation ✅
- HTTP Router for external requests ✅
- Management API for cluster control ✅
- Health monitoring and failure recovery ✅
- CLI with REPL and batch modes ✅

### Phase 3: AI Integration (Planned)

- Layered autonomy architecture (see [ai-integration.md](ai-integration.md))
- Layer 1: Decision tree controller (done)
- Layer 2: SLM integration (planned)
- Layer 3: LLM integration (planned)
- Direct agent API (no MCP)

## Business Value Proposition

- **Transform monoliths** into distributed applications without code changes
- **Business team productivity** - work with high-level APIs, runtime handles distribution
- **Transparent scaling** - applications scale without architectural changes
- **Multi-cloud deployment** - unified model across cloud providers
- **Runtime intelligence** - environment adapts to application needs

---
*Last Updated: 2025-12-24*
*Status: Early development, core architecture complete*
*Architecture: Slice-based, runtime-intelligent, transparent distribution*