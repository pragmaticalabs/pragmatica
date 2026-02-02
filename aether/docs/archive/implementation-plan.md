# Aether Implementation Plan

This document provides a comprehensive, actionable implementation plan for building Aether from current state to fully
functional AI-driven distributed runtime.

**See [vision-and-goals.md](vision-and-goals.md) for complete vision and design principles.**

## Current State Assessment

### Completed ✅

- **Rabia Consensus**: Fully implemented and tested (8 integration test suites passing)
- **Artifact Types**: GroupId, ArtifactId, Version, Artifact parsing and validation
- **Slice Lifecycle States**: SliceState enum with transitions and timeouts
- **Test Infrastructure**: Comprehensive testing patterns established
- **Documentation**: Architecture and vision documents aligned
- **Dependency Injection Infrastructure**: Complete resolution framework (VersionPattern, DependencyDescriptor,
  SliceDependencies, DependencyCycleDetector, SliceFactory, SliceRegistry, DependencyResolver) - 119 tests passing

### Partially Implemented 🔄

- **NodeDeploymentManager**: Skeleton exists but consensus integration commented out
- **SliceKVSchema**: Basic structure exists, needs refactoring to structured keys

### Not Started ❌

- **SliceStore**: Interface defined, no implementation
- **ClusterDeploymentManager**: Designed, not implemented
- **EndpointRegistry**: Designed, not implemented
- **AI Agent Interface**: Not designed or implemented
- **Metrics Collection/Shaping**: Not designed or implemented
- **Inter-slice Communication**: Not designed or implemented

## Implementation Phases

### Phase 1: Core Runtime Infrastructure (Foundation)

**Goal**: Establish working slice deployment without AI

#### 1.1 - Structured KV Schema

**Priority**: HIGHEST (blocks everything)
**Estimated Effort**: 2-3 days

**Tasks**:

1. Define `AetherKey` sealed interface with concrete key types:
    - `BlueprintKey`
    - `SliceStateKey`
    - `EndpointKey`
    - **No AllocationKey** (ClusterDeploymentManager writes directly to SliceStateKey)
    - **No MetricsKey** (metrics never stored in KV-Store, flow via MessageRouter)
2. Implement `StructuredPattern` for key matching
3. Add parsing from string to structured keys
4. Update SliceKVSchema to use structured keys
5. Write comprehensive tests (roundtrip, pattern matching)

**Acceptance Criteria**:

- All key types parse and serialize correctly
- Pattern matching works for key filtering
- SliceKVSchema uses only structured keys
- Zero TODO comments related to structured keys

**Files to Create/Modify**:

- `slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherKey.java` (new)
- `slice/src/main/java/org/pragmatica/aether/slice/kvstore/SliceKVSchema.java` (modify)
- `slice/src/test/java/org/pragmatica/aether/slice/kvstore/AetherKeyTest.java` (new)

---

#### 1.2 - SliceStore Implementation

**Priority**: CRITICAL
**Estimated Effort**: 5-7 days
**Depends On**: None (can start in parallel with 1.1)
**Prerequisites**: ✅ Dependency injection infrastructure complete (DependencyResolver, SliceRegistry, etc.)

**Tasks**:

1. ⚠️ **Implement artifact resolution from repository** (BLOCKER)
    - Integrate with LocalRepository.locate() to download JARs
    - Resolve transitive dependencies
    - Cache downloaded artifacts
2. **Design className ↔ artifact mapping**
    - Mechanism to convert class name to artifact coordinates
    - Consider META-INF manifest entries or separate registry
3. **Implement isolated ClassLoader management**
    - Create isolated ClassLoader per slice
    - Share Pragmatica framework classes
    - Proper parent delegation
4. **Integrate DependencyResolver**
    - Use DependencyResolver.resolve() in slice loading
    - Pre-load dependencies into SliceRegistry
    - Handle resolution failures
5. Implement basic ClassLoader-based slice loading
6. Implement ServiceLoader discovery
7. Add lifecycle management (LOAD → LOADING → LOADED)
8. Add activation/deactivation (ACTIVATE → ACTIVATING → ACTIVE)
9. Implement timeout handling at operation level
10. Add resource cleanup on unload
11. Write comprehensive tests (lifecycle, timeouts, errors, dependency resolution)

**Acceptance Criteria**:

- ✅ Can resolve dependencies from repository (LocalRepository integration)
- ✅ Isolated ClassLoader created per slice
- ✅ Dependencies resolved and loaded recursively
- ✅ Can load slice from Maven artifact
- ✅ Lifecycle transitions work correctly
- ✅ Timeouts cancel operations properly
- ✅ Resources cleaned up on unload
- ✅ Integration test with example-slice and dependencies passes
- ✅ Circular dependency detection prevents infinite loops

**Files to Create/Modify**:

- `slice/src/main/java/org/pragmatica/aether/slice/repository/LocalRepository.java` (new - artifact resolution)
- `slice/src/main/java/org/pragmatica/aether/slice/repository/ArtifactCache.java` (new - caching)
- `slice/src/main/java/org/pragmatica/aether/slice/SliceStore.java` (implement)
- `slice/src/main/java/org/pragmatica/aether/slice/SliceClassLoader.java` (new - isolation)
- `slice/src/main/java/org/pragmatica/aether/slice/ArtifactMapper.java` (new - className ↔ artifact)
- `slice/src/test/java/org/pragmatica/aether/slice/SliceStoreTest.java` (new)
- `slice/src/test/java/org/pragmatica/aether/slice/repository/LocalRepositoryTest.java` (new)

---

#### 1.3 - NodeDeploymentManager Consensus Integration

**Priority**: CRITICAL
**Estimated Effort**: 3-4 days
**Depends On**: 1.1 (Structured Keys), 1.2 (SliceStore)

**Tasks**:

1. Uncomment and fix NodeDeploymentManager implementation
2. Integrate with consensus KV-Store (ValuePut/ValueRemove)
3. Implement SliceStore integration
4. Add proper error handling and recovery
5. Handle abrupt stop scenarios (consensus loss)
6. Write integration tests with mock KV-Store

**Acceptance Criteria**:

- NodeDeploymentManager responds to KV-Store changes
- Slice lifecycle operations triggered correctly
- Abrupt stop handled gracefully
- Integration tests pass
- All NodeDeploymentManager TODOs resolved

**Files to Modify**:

- `node/src/main/java/org/pragmatica/aether/deployment/node/NodeDeploymentManager.java`
- `node/src/test/java/org/pragmatica/aether/deployment/node/NodeDeploymentManagerTest.java` (new)

---

#### 1.4 - ClusterDeploymentManager (Basic)

**Priority**: HIGH
**Estimated Effort**: 4-5 days
**Depends On**: 1.1 (Structured Keys), 1.3 (NodeDeploymentManager)

**Tasks**:

1. Implement ClusterDeploymentManager skeleton
2. Add LeaderNotification integration (activate/deactivate)
3. Implement BlueprintWatcher (watch `blueprints/*` keys)
4. Implement simple round-robin allocation logic (directly writes to `slices/{node-id}/{artifact}`)
5. Implement basic ReconciliationEngine
6. Write integration tests with mock consensus

**Acceptance Criteria**:

- Activates only when leader
- Watches blueprint changes
- Allocates instances round-robin across nodes
- Writes LOAD commands directly to `slices/{node-id}/{artifact}` keys (no intermediate allocations)
- Integration tests with multi-node simulation pass

**Files to Create**:

- `node/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterDeploymentManager.java`
- `node/src/main/java/org/pragmatica/aether/deployment/cluster/BlueprintWatcher.java`
- `node/src/main/java/org/pragmatica/aether/deployment/cluster/ReconciliationEngine.java`
- `node/src/test/java/org/pragmatica/aether/deployment/cluster/ClusterDeploymentManagerIT.java`

**Note**: No separate AllocationEngine component needed - allocation logic embedded in ClusterDeploymentManager.

---

#### 1.5 - EndpointRegistry

**Priority**: MEDIUM
**Estimated Effort**: 2-3 days
**Depends On**: 1.1 (Structured Keys)

**Tasks**:

1. Implement passive KV-Store watcher for endpoint events
2. Maintain local cache of endpoints
3. Implement endpoint lookup by artifact and entry point
4. Add simple round-robin load balancing
5. Write unit tests

**Acceptance Criteria**:

- Watches `endpoints/*` keys
- Maintains accurate local cache
- Endpoint lookup works correctly
- Load balancing distributes calls
- Unit tests pass

**Files to Create**:

- `node/src/main/java/org/pragmatica/aether/endpoint/EndpointRegistry.java`
- `node/src/test/java/org/pragmatica/aether/endpoint/EndpointRegistryTest.java`

---

**Phase 1 Milestone**: Manual slice deployment working end-to-end

- Operator publishes blueprint via KV-Store
- ClusterDeploymentManager allocates instances
- NodeDeploymentManager executes lifecycle
- Slices loaded and activated
- Endpoints registered and discoverable

---

### Phase 2: Inter-Slice Communication

**Goal**: Enable atomic, reliable slice-to-slice calls

#### 2.1 - Inter-Slice Call Protocol Design

**Priority**: HIGH
**Estimated Effort**: 2 days

**Tasks**:

1. Design call protocol over consensus transport
2. Define call/response message formats
3. Design timeout and retry semantics
4. Document atomicity guarantees
5. Create protocol specification document

**Deliverables**:

- `docs/inter-slice-communication.md` (new specification)

---

#### 2.2 - Remote Call Implementation

**Priority**: HIGH
**Estimated Effort**: 5-7 days
**Depends On**: 1.5 (EndpointRegistry), 2.1 (Protocol Design)

**Tasks**:

1. Implement RemoteCallManager
2. Add request/response serialization
3. Implement call routing via EndpointRegistry
4. Add timeout and error handling
5. Ensure atomicity guarantees
6. Write comprehensive tests

**Acceptance Criteria**:

- Slice A can call Slice B atomically
- Calls timeout correctly
- Errors propagate properly
- Integration tests with 2+ slices pass
- Atomicity verified under failure scenarios

**Files to Create**:

- `node/src/main/java/org/pragmatica/aether/call/RemoteCallManager.java`
- `node/src/main/java/org/pragmatica/aether/call/CallProtocol.java`
- `node/src/test/java/org/pragmatica/aether/call/RemoteCallManagerIT.java`

---

#### 2.3 - Example Multi-Slice Application

**Priority**: MEDIUM
**Estimated Effort**: 3 days
**Depends On**: 2.2 (Remote Call)

**Tasks**:

1. Create example with 2-3 communicating slices
2. Demonstrate service slice (multiple entry points)
3. Demonstrate lean slice (single use case)
4. Show inter-slice atomic calls
5. Write end-to-end test

**Deliverables**:

- `example-multi-slice/` module with working example

---

**Phase 2 Milestone**: Multiple slices communicating atomically

- Service slices expose multiple entry points
- Lean slices encapsulate single use case
- Inter-slice calls are atomic and reliable
- Example application demonstrates patterns

---

### Phase 3: Metrics and Events

**Goal**: Collect metrics and distribute events for controller consumption

**See [metrics-and-control.md](metrics-and-control.md) for complete specification.**

#### 3.1 - MetricsCollector

**Priority**: MEDIUM
**Estimated Effort**: 3-4 days

**Tasks**:

1. Implement MetricsCollector on each node
2. Collect CPU usage (JMX or OS-level)
3. Collect calls per entry point (instrument slice invocations)
4. Collect total call duration
5. Push MetricsUpdate to leader every 1 second (via MessageRouter)
6. Write tests

**Acceptance Criteria**:

- All three core metrics collected
- Metrics pushed to leader at 1 Hz
- Low overhead (< 0.5% CPU)
- Unit and integration tests pass

**Files to Create**:

- `node/src/main/java/org/pragmatica/aether/metrics/MetricsCollector.java`
- `node/src/main/java/org/pragmatica/aether/metrics/CpuMetrics.java`
- `node/src/main/java/org/pragmatica/aether/metrics/CallMetrics.java`
- `node/src/main/java/org/pragmatica/aether/metrics/MetricsUpdate.java` (message)
- `node/src/test/java/org/pragmatica/aether/metrics/MetricsCollectorTest.java`

---

#### 3.2 - MetricsAggregator

**Priority**: MEDIUM
**Estimated Effort**: 3-4 days
**Depends On**: 3.1 (MetricsCollector)

**Tasks**:

1. Implement MetricsAggregator on leader node
2. Receive MetricsUpdate from all nodes (MessageRouter listener)
3. Aggregate into ClusterMetricsSnapshot every 1 second
4. Broadcast snapshot to all nodes via MessageRouter
5. Maintain 2-hour sliding window in memory
6. Handle stale/missing node metrics
7. Write tests

**Acceptance Criteria**:

- Aggregates metrics from all nodes
- Broadcasts snapshot every 1 second
- Sliding window maintains 2 hours of history
- Handles node failures gracefully (excludes stale metrics)
- Zero KV-Store I/O
- Unit tests verify aggregation correctness

**Files to Create**:

- `node/src/main/java/org/pragmatica/aether/metrics/MetricsAggregator.java`
- `node/src/main/java/org/pragmatica/aether/metrics/ClusterMetricsSnapshot.java` (message)
- `node/src/main/java/org/pragmatica/aether/metrics/SlidingWindow.java`
- `node/src/test/java/org/pragmatica/aether/metrics/MetricsAggregatorTest.java`

---

#### 3.3 - ClusterEventBus

**Priority**: MEDIUM
**Estimated Effort**: 2-3 days

**Tasks**:

1. Implement ClusterEventBus (MessageRouter-based)
2. Define ClusterEvent sealed interface with all event types
3. Add event producers (NodeDeploymentManager, LeaderManager, etc.)
4. Add event buffering (recent history in memory)
5. Write tests

**Acceptance Criteria**:

- All event types defined
- Events published by relevant components
- Recent events buffered (last 100 or 5 minutes)
- Consumers can subscribe to event stream
- Unit tests pass

**Files to Create**:

- `node/src/main/java/org/pragmatica/aether/events/ClusterEventBus.java`
- `node/src/main/java/org/pragmatica/aether/events/ClusterEvent.java`
- `node/src/test/java/org/pragmatica/aether/events/ClusterEventBusTest.java`

---

**Phase 3 Milestone**: Metrics and events flowing

- All nodes collect and push metrics
- Leader aggregates and broadcasts cluster snapshot
- All nodes have cluster-wide visibility
- Events distributed to interested components
- Zero KV-Store I/O for metrics

---

### Phase 4: Cluster Controller

**Goal**: Implement pluggable controller for topology decisions

**See [metrics-and-control.md](metrics-and-control.md) for complete specification.**

#### 4.1 - ClusterController Interface

**Priority**: HIGH
**Estimated Effort**: 2-3 days
**Depends On**: 3.2 (MetricsAggregator), 3.3 (ClusterEventBus)

**Tasks**:

1. Define ClusterController interface
2. Define ControlContext (metrics, events, topology, blueprints)
3. Define ControlDecisions (blueprint changes, node actions)
4. Add safety bounds validator interface
5. Write mock controller for testing

**Acceptance Criteria**:

- Clean interface defined
- All input/output types specified
- Mock controller returns valid decisions
- Unit tests pass

**Files to Create**:

- `node/src/main/java/org/pragmatica/aether/controller/ClusterController.java`
- `node/src/main/java/org/pragmatica/aether/controller/ControlContext.java`
- `node/src/main/java/org/pragmatica/aether/controller/ControlDecisions.java`
- `node/src/main/java/org/pragmatica/aether/controller/SafetyValidator.java`
- `node/src/test/java/org/pragmatica/aether/controller/MockController.java`

---

#### 4.2 - DecisionTreeController

**Priority**: HIGH
**Estimated Effort**: 4-5 days
**Depends On**: 4.1 (ClusterController Interface)

**Tasks**:

1. Implement DecisionTreeController (simplest controller)
2. Design rule definition format (YAML or embedded DSL)
3. Implement rule evaluation engine
4. Add common rules (CPU threshold, call rate scaling)
5. Evaluate every 1 second (fast reactive)
6. Write comprehensive tests

**Acceptance Criteria**:

- Rules evaluated correctly
- Scaling decisions produced for threshold violations
- Evaluates in < 1 ms
- Integration tests demonstrate reactive scaling
- Rule configuration documented

**Files to Create**:

- `node/src/main/java/org/pragmatica/aether/controller/DecisionTreeController.java`
- `node/src/main/java/org/pragmatica/aether/controller/RuleEngine.java`
- `node/src/main/java/org/pragmatica/aether/controller/Rule.java`
- `node/src/test/java/org/pragmatica/aether/controller/DecisionTreeControllerTest.java`
- `docs/decision-tree-rules.md` (rule format documentation)

---

#### 4.3 - Control Loop Integration

**Priority**: HIGH
**Estimated Effort**: 3-4 days
**Depends On**: 4.2 (DecisionTreeController)

**Tasks**:

1. Implement control loop on leader node
2. Integrate controller with MetricsAggregator and ClusterEventBus
3. Apply controller decisions to KV-Store (blueprint updates)
4. Add configurable evaluation frequency per controller type
5. Implement human override mechanism
6. Write end-to-end integration tests

**Acceptance Criteria**:

- Complete control loop working
- Controller decisions trigger slice scaling
- Metrics reflect changes in next iteration
- Human operators can override controller
- End-to-end test demonstrates predictive scaling
- Decision tree controller demonstrates reactive scaling

**Files to Create**:

- `node/src/main/java/org/pragmatica/aether/controller/ControlLoop.java`
- `node/src/main/java/org/pragmatica/aether/controller/DecisionApplicator.java`
- `node/src/test/java/org/pragmatica/aether/controller/ControlLoopIT.java`

---

#### 4.4 - LLM Controller Adapters (Lower Priority)

**Priority**: MEDIUM
**Estimated Effort**: 5-7 days (ongoing)
**Depends On**: 4.3 (Control Loop)

**Tasks**:

1. Implement SmallLLMController (Ollama, LM Studio)
2. Implement LargeLLMController (OpenAI, Anthropic, Gemini)
3. Add prompt engineering for metrics → decisions
4. Add retry and fallback logic
5. Implement LayeredController (decision tree + LLM)
6. Document LLM provider configuration
7. Create example prompts

**Deliverables**:

- Multiple LLM controller implementations
- Layered controller for hybrid intelligence
- Configuration guide for LLM providers
- Example prompts for different scenarios

**Files to Create**:

- `node/src/main/java/org/pragmatica/aether/controller/SmallLLMController.java`
- `node/src/main/java/org/pragmatica/aether/controller/LargeLLMController.java`
- `node/src/main/java/org/pragmatica/aether/controller/LayeredController.java`
- `node/src/main/java/org/pragmatica/aether/controller/llm/OllamaAdapter.java`
- `node/src/main/java/org/pragmatica/aether/controller/llm/OpenAIAdapter.java`
- `docs/llm-controller-configuration.md`

---

**Phase 4 Milestone**: Controller-driven cluster management working

- Decision tree controller makes reactive scaling decisions
- Control loop applies decisions to cluster
- Metrics reflect controller actions
- Foundation ready for LLM controllers
- Predictive scaling demonstrated (with decision tree or LLM)

---

### Phase 5: Advanced Features

**Goal**: Complex deployments and multi-cloud

#### 5.1 - Node Lifecycle Management

**Priority**: MEDIUM
**Estimated Effort**: 5-7 days

**Tasks**:

1. Design node startup/shutdown protocol
2. Implement node provisioning API
3. Add cloud provider adapters (AWS, GCP, Azure)
4. Implement graceful shutdown with slice migration
5. Write tests

**Acceptance Criteria**:

- AI can request new nodes
- Nodes auto-join cluster on startup
- Graceful shutdown migrates slices
- Cloud provider adapters work

---

#### 5.2 - Complex Deployment Strategies

**Priority**: MEDIUM
**Estimated Effort**: 7-10 days

**Tasks**:

1. Implement rolling update strategy
2. Implement canary deployment with metrics validation
3. Implement blue/green deployment
4. Add automatic rollback on failures
5. Write comprehensive tests

**Acceptance Criteria**:

- Rolling updates work without downtime
- Canary deployments validate before full rollout
- Blue/green switches atomically
- Rollback triggers on errors

---

#### 5.3 - Multi-Cloud Management

**Priority**: LOW
**Estimated Effort**: 7-10 days

**Tasks**:

1. Extend ClusterDeploymentManager allocation logic for multi-cloud
2. Add cost modeling per cloud provider
3. Implement cross-cloud migration
4. Add latency-aware routing
5. Write tests

**Acceptance Criteria**:

- Slices can deploy to multiple clouds
- AI considers cost in decisions
- Migration works without stopping processing

---

**Phase 5 Milestone**: Production-ready feature set

- Complex deployments work reliably
- Multi-cloud deployment functional
- Cost optimization enabled
- Zero-downtime migrations proven

---

## Testing Strategy

### Unit Tests

- All new code has unit tests
- Coverage target: 80%+
- Follow existing patterns (Result<T> testing, Promise testing)

### Integration Tests (IT)

- Cluster-level scenarios with multiple nodes
- Named `*IT.java` to run separately
- Test consensus integration, failures, recoveries

### End-to-End Tests

- Complete workflows: blueprint → deployment → execution → metrics → AI → scaling
- Use docker-compose for multi-node setup
- Verify AI-driven scenarios

## Risk Mitigation

### Risk: AI Making Destructive Decisions

**Mitigation**:

- Implement safety bounds validator
- Rate-limit AI-initiated changes
- Human override always available
- Gradual rollout with monitoring

### Risk: Metrics Overhead Impacts Performance

**Mitigation**:

- Keep metrics minimal (3 core metrics)
- Publish asynchronously to KV-Store
- Measure overhead continuously (target < 1%)

### Risk: Consensus Bottleneck Under Load

**Mitigation**:

- Batch KV-Store updates when possible
- Use TTL for ephemeral metrics
- Profile and optimize hot paths

### Risk: ClassLoader Leaks

**Mitigation**:

- Thorough testing of unload scenarios
- Memory profiling during development
- WeakReference usage where appropriate

## Success Metrics

### Phase 1 Success

- Can deploy and manage slices manually
- End-to-end lifecycle works
- Zero crashes in 24-hour stress test

### Phase 2 Success

- Inter-slice calls work atomically
- Example multi-slice app runs successfully
- Call latency < 10ms within same node

### Phase 3 Success

- Metrics collected with < 1% overhead
- Leader aggregates metrics successfully
- Shaped output ready for AI

### Phase 4 Success

- AI makes valid scaling decisions
- Predictive scaling prevents performance degradation
- Control loop completes in < 60 seconds

### Phase 5 Success

- Rolling update completes without errors
- Multi-cloud deployment functional
- Production workload runs for 1 week without issues

## Timeline Estimate

**Assumptions**:

- 1 full-time developer
- 5 productive days per week

### Aggressive Timeline (Minimum)

- Phase 1: 3-4 weeks
- Phase 2: 2-3 weeks
- Phase 3: 1-2 weeks
- Phase 4: 2-3 weeks
- Phase 5: 3-4 weeks
  **Total: 11-16 weeks (~3-4 months)**

### Realistic Timeline (Recommended)

- Phase 1: 4-6 weeks
- Phase 2: 3-4 weeks
- Phase 3: 2-3 weeks
- Phase 4: 3-4 weeks
- Phase 5: 4-6 weeks
  **Total: 16-23 weeks (~4-6 months)**

## Next Steps

### Immediate Priorities (Week 1)

1. Start **Task 1.1 - Structured KV Schema** (highest priority blocker)
2. Start **Task 1.2 - SliceStore Implementation** (in parallel)
3. Review and refine AI agent interface design

### First Month Goals

- Complete Phase 1 (Core Runtime Infrastructure)
- Begin Phase 2 (Inter-Slice Communication)
- Have working manual deployment end-to-end

### First Quarter Goals

- Complete Phases 1-3
- Begin Phase 4 (AI Integration)
- Demonstrate predictive scaling in controlled environment

---

*This plan is a living document and will be updated as implementation progresses and requirements evolve.*
