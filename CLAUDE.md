# Pragmatica Aether Distributed Runtime - Development Guide

## Agent Policy (MANDATORY)

**ALWAYS use `jbct-coder` agent for ALL coding and fixing tasks.**

This includes:
- Writing new code (classes, methods, tests)
- Fixing bugs and compilation errors
- Refactoring existing code
- Implementing features from plans
- Any task that involves modifying Java source files

Do NOT write code directly - delegate to `jbct-coder` agent.

## Project Overview

**Pragmatica Aether Distributed Runtime** (v0.7.2) is an AI-driven distributed runtime environment for Java that enables predictive scaling,
intelligent orchestration, and seamless multi-cloud deployment without requiring changes to business logic.

**See [docs/vision-and-goals.md](docs/vision-and-goals.md) for complete vision and design principles.**

### Key Features

- **AI-Driven Management**: External AI learns patterns and makes topology decisions (predictive scaling, complex
  deployments)
- **Slice-based deployment**: Two types - Service slices (multiple entry points) and Lean slices (single use case)
- **Distributed consensus**: Rabia protocol for cluster-wide state consistency
- **Atomic inter-slice calls**: Reliable communication guaranteed by runtime
- **Convergence model**: Runtime continuously reconciles actual state with desired state
- **Multi-cloud ready**: AI decides deployment across clouds/regions

### Distributed Communication Guarantee

**CRITICAL DESIGN PRINCIPLE:** Every inter-slice call will **EVENTUALLY SUCCEED** if the cluster is alive.

The runtime (SliceInvoker) handles all communication complexity:
- Multi-instance failover (tries all available nodes)
- Exponential backoff retry
- Automatic rollback on persistent failure
- Request ID propagation for tracing

**Slices are intentionally NOT prepared for communication errors.** This is by design:
- Business logic stays clean (no try-catch for network errors)
- Runtime handles retries, failover, and recovery transparently
- If a call fails after all retries, the cluster has a systemic problem (alerts trigger, rollback may occur)

This means:
- **DO NOT** add circuit breakers in slice code
- **DO NOT** handle `CommunicationException` in business logic
- **DO** trust that `Promise<T>` from inter-slice calls will resolve if cluster is healthy
- **DO** design slices to be idempotent (calls may be retried)

## Module Structure

### Core Modules

- **slice-api/** - Slice interface definitions (`Slice`, `SliceMethod`, `SliceRoute`)
- **slice/** - Slice management (`SliceStore`, `SliceState`, `Artifact` types, KV schema)
- **node/** - Runtime node implementation (`NodeDeploymentManager`, `ManagementServer`, metrics, controller, TTM integration)
- **cluster/** - Cluster networking and KVStore (`NettyClusterNetwork`, `KVStore`)
- **aether-ttm/** - TTM (Tiny Time Mixers) ONNX inference for predictive scaling
- **aether-config/** - Configuration types (`AetherConfig`, `TTMConfig`)
- **forge/** - Aether Forge: Standalone simulator CLI with visual dashboard for load/chaos testing
- **examples/** - Example slices and demos
  - **ecommerce/** - E-commerce domain with @Slice annotation pattern
  - **order-demo/** - Complete order domain demo (5 slices)
- **cli/** - Command-line interface for cluster management

### Module Dependencies

```
slice-api (minimal dependencies)
    ↑
slice (depends on slice-api, cluster)
    ↑
node (depends on slice, cluster)
    ↑
examples (depends on slice-api)
```

## Core Concepts

### Slices

Deployable units implementing the `Slice` interface:

```java
public interface Slice {
    Promise<Unit> start();
    Promise<Unit> stop();
    List<SliceMethod<?, ?>> methods();
}
```

**Two Types (unified management)**:

#### Service Slices

Traditional microservice-style components with multiple entry points.

- Multiple `SliceMethod<?, ?>` entries
- Suitable for CRUD operations, API gateways, data services
- Example: User authentication service with login, logout, register methods

#### Lean Slices

Single-purpose components handling one use case or event type.

- Single `SliceMethod<?, ?>` entry
- Encapsulates complete business use case (DDD-style) or event handler
- Written using JBCT patterns (use `/jbct` skill for guidance)
- Example: "RegisterUser" use case, "OrderPaymentProcessed" event handler

**From runtime perspective**: Both types are identical - same lifecycle, same atomic communication, same management.

**Lifecycle States**:

```
LOAD → LOADING → LOADED → ACTIVATE → ACTIVATING → ACTIVE
         ↓                                ↓
      FAILED ←---------------------------+
         ↓
      UNLOAD → UNLOADING → [removed]
         ↑
   DEACTIVATE ← ACTIVE → DEACTIVATING → LOADED
```

### Artifacts

Maven-style coordinates for slices:

```java
// Format: groupId:artifactId:version[-qualifier]
Artifact.artifact("org.pragmatica-lite.aether.demo:inventory-service:0.1.0")
```

**Components**:

- `GroupId` - Organization/group identifier
- `ArtifactId` - Slice identifier
- `Version` - Semantic version with optional qualifier

### Blueprints

Desired cluster configuration stored in consensus KV-Store. Created by:

- Human operators via CLI/API
- AI based on observed metrics and learned patterns

```json
{
  "slices": [
    {
      "artifact": "org.example:slice:1.0.0",
      "instances": 3
    }
  ],
  "timestamp": 1234567890
}
```

### Cluster Controller & AI Integration

**See [docs/metrics-and-control.md](docs/metrics-and-control.md) for complete specification.**

**Layered Autonomy Architecture** - cluster survives with only the lowest layer:

```
┌─────────────────────────────────────────────────────────┐
│  Layer 4: User                                          │
│  Frequency: On-demand                                   │
│  Role: Strategic decisions, overrides, teaching         │
├─────────────────────────────────────────────────────────┤
│  Layer 3: LLM (Claude, etc.)                            │
│  Frequency: Minutes-hours                               │
│  Role: Complex reasoning, anomaly analysis, planning    │
├─────────────────────────────────────────────────────────┤
│  Layer 2: SLM (Small Language Model)                    │
│  Frequency: Seconds-minutes                             │
│  Role: Pattern recognition, simple decisions            │
├─────────────────────────────────────────────────────────┤
│  Layer 1: Lizard Brain (DecisionTreeController)         │
│  Frequency: Milliseconds (every 1 second)               │
│  Role: Immediate reactions, scaling rules, health       │
│  REQUIRED: Cluster MUST survive with only this layer    │
└─────────────────────────────────────────────────────────┘
```

**Key Principles:**

- **Layer 1 is mandatory** - all other layers are optional enhancements
- **Graceful degradation** - if LLM unavailable, SLM handles; if SLM unavailable, decision tree handles
- **Escalation flow** - problems flow up (Layer 1 → 4), decisions flow down (Layer 4 → 1)
- **Each layer is replaceable** - can add/remove/swap/configure/teach upper layers
- **No MCP** - agents interact directly with Management API (simpler, more reliable)

**Controller Types:**

- **DecisionTreeController** (Layer 1): Deterministic rules, always running, evaluated every 1 second
- **TTM/AdaptiveDecisionTree** (Layer 2): ONNX-based predictions, leader-only, evaluated every 60 seconds
- **Future: LLM integration** (Layer 3): Cloud-based strategic planning, evaluated every 30-60 seconds

**TTM Two-Tier Architecture:**

```
┌─────────────────────────────────────────────────────────┐
│  Tier 2: TTM Model (Proactive)                          │
│  Frequency: 60 seconds                                   │
│  Scope: Leader-only                                      │
│  Input: MinuteAggregator (60-minute sliding window)     │
│  Output: ScalingRecommendation, threshold adjustments   │
├─────────────────────────────────────────────────────────┤
│  Tier 1: Decision Tree (Reactive)                       │
│  Frequency: 1 second                                     │
│  Scope: Leader-only                                      │
│  Input: ClusterMetricsSnapshot                          │
│  Output: BlueprintChanges                               │
└─────────────────────────────────────────────────────────┘
```

**TTM Components:**
- `TTMPredictor` - ONNX Runtime wrapper for model inference
- `TTMManager` - Leader-aware lifecycle, periodic evaluation
- `ForecastAnalyzer` - Converts predictions to `ScalingRecommendation`
- `AdaptiveDecisionTree` - Wraps `DecisionTreeController`, adjusts thresholds on forecast

**TTM Configuration (aether.toml):**

```toml
[ttm]
enabled = true
model_path = "models/ttm-aether.onnx"
input_window_minutes = 60          # 1-120
evaluation_interval_ms = 60000     # 10000-300000
confidence_threshold = 0.7         # 0.0-1.0
prediction_horizon = 1             # 1-10
```

**Controller Responsibilities**:

- **Predictive Scaling**: Learn traffic patterns, scale BEFORE load increases
- **Second-Level Scaling**: Start/stop compute nodes, choose environments
- **Complex Deployments**: Rolling updates, canary, blue/green, multi-cloud migration

**Controller Input**:

- Current ClusterMetricsSnapshot
- Historical metrics (2-hour sliding window)
- Cluster events (node joins/leaves, slice lifecycle, etc.)
- Current topology and blueprints

**Controller Output**:

- Blueprint changes (scale/deploy/remove slices)
- Node actions (start/stop nodes, migrate slices)
- Reasoning (for transparency)

**Key Points**:

- Only leader node runs controller
- Controllers make strategic decisions (seconds to minutes), not tactical (milliseconds)
- Controller updates desired state in KV-Store, runtime executes convergence
- Decision tree provides fast reactive fallback

### Metrics Collection

**See [docs/metrics-and-control.md](docs/metrics-and-control.md) for complete specification.**

**Core Metrics** (minimal set):

- **Node CPU Usage**: Per-node CPU utilization (0.0-1.0)
- **Calls Per Entry Point**: Request count per entry point per cycle
- **Total Call Duration**: Aggregate processing time per cycle

**Collection Architecture**:

```
Every 1 Second:
1. MetricsCollector (all nodes) → push MetricsUpdate to leader
2. MetricsAggregator (leader) → aggregate + broadcast ClusterMetricsSnapshot
3. All nodes receive cluster-wide metrics snapshot
```

**Key Benefits**:

- Zero KV-Store I/O (all via MessageRouter)
- Fast leader failover (< 2 sec data loss)
- All nodes have cluster-wide visibility
- 2-hour sliding window for pattern detection

### Consensus KV-Store

Single source of truth for **persistent** cluster state using structured keys.

**Note**: Metrics do NOT flow through KV-Store (zero consensus I/O for metrics).

**Key Schema**:

- `blueprint/{artifact}` → Blueprint configuration
- `slices/{nodeId}/{artifact}` → Slice state on specific node
- `endpoints/{artifact}/{entryPointId}:{instance}` → Endpoint locations

**Value Schema**:

- `BlueprintValue(instanceCount)` - Desired instance count
- `SliceNodeValue(state)` - Current slice state
- `EndpointValue(nodeId)` - Endpoint location

**Note**: No separate allocations key - ClusterDeploymentManager writes allocation decisions directly to
`slices/{nodeId}/{artifact}` with LOAD state.

## Architecture Components

### SliceStore

Manages slice lifecycle on individual nodes:

```java
public interface SliceStore {
    Promise<LoadedSlice> loadSlice(Artifact artifact);
    Promise<LoadedSlice> activateSlice(Artifact artifact);
    Promise<LoadedSlice> deactivateSlice(Artifact artifact);
    Promise<Unit> unloadSlice(Artifact artifact);
}
```

### NodeDeploymentManager

Watches KV-Store for slice state changes and coordinates with `SliceStore` to perform lifecycle operations on local
node.

### ClusterDeploymentManager

Leader-based cluster-wide orchestration:

- Blueprint monitoring
- Allocation decisions (round-robin, writes directly to slice-node-keys)
- Reconciliation on topology changes
- Automatic rebalancing

### Rabia Consensus

CFT (crash-fault-tolerant) leaderless consensus algorithm:

- No persistent event log required
- Batch-based command processing
- Automatic state synchronization
- Deterministic leader selection for special operations

## Java Backend Coding Technology (JBCT)

This project follows Pragmatica Lite Core patterns strictly:

### Four Return Kinds

Every function returns exactly one of:

- `T` - Synchronous, infallible
- `Option<T>` - Synchronous, infallible, optional value
- `Result<T>` - Synchronous, fallible
- `Promise<T>` - Asynchronous, fallible

**Never** `Promise<Result<T>>` - failures flow through Promise directly.

### Parse, Don't Validate

Valid objects constructed only when validation succeeds:

```java
public record Email(String value) {
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[a-z0-9+_.-]+@[a-z0-9.-]+$");
    private static final Fn1<Cause, String> INVALID_EMAIL = Causes.forOneValue("Invalid email format: {}");

    public static Result<Email> email(String raw) {
        return Verify.ensure(raw, Verify.Is::notNull)
            .map(String::trim)
            .map(String::toLowerCase)
            .flatMap(v -> Verify.ensure(v, Verify.Is::matches, EMAIL_PATTERN, INVALID_EMAIL))
            .map(Email::new);
    }
}
```

**Examples in Aether**: `Artifact`, `GroupId`, `ArtifactId`, `Version`, `SliceState`, `MethodName`

### No Business Exceptions

Business logic never throws exceptions. All failures flow through `Result` or `Promise` as typed `Cause`:

```java
public sealed interface SliceError extends Cause {
    record LoadFailed(Artifact artifact, Throwable cause) implements SliceError {
        @Override
        public String message() {
            return "Failed to load slice " + artifact + ": " + cause.getMessage();
        }
    }
}
```

### Factory Naming Convention

Always `TypeName.typeName(...)` (lowercase-first):

```java
Artifact.artifact("org.example:slice:1.0.0")
GroupId.groupId("org.example")
Version.version("1.0.0")
SliceState.sliceState("ACTIVE")
```

### Error Handling with Lift

Use `Promise.lift()` and `Result.lift()` for exception-prone operations:

```java
Promise.lift(
    SliceError.LoadFailed::cause,
    () -> classLoader.loadClass(className)
)
```

### Single Pattern Per Function

- **Leaf** - Single operation (business logic or adapter)
- **Sequencer** - Linear chain of dependent steps
- **Fork-Join** - Parallel independent operations
- **Condition** - Branching logic
- **Iteration** - Collection processing

### Adapter Leaves for I/O

Strongly prefer adapter leaves for all I/O operations (database, HTTP, file system). This ensures framework
independence.

## Coding Conventions

### Record-Based Implementation

Use records for data carriers and implementations:

```java
public interface RegisterUser {
    Promise<Response> execute(Request request);

    static RegisterUser registerUser(CheckEmail checkEmail, SaveUser saveUser) {
        record registerUser(CheckEmail checkEmail, SaveUser saveUser) implements RegisterUser {
            public Promise<Response> execute(Request request) {
                return ValidRequest.validRequest(request)
                    .async()
                    .flatMap(checkEmail::apply)
                    .flatMap(saveUser::apply);
            }
        }
        return new registerUser(checkEmail, saveUser);
    }
}
```

### Package Structure

- Group by feature/use case, not by layer
- Keep related types together
- Use sealed interfaces for error hierarchies

### Method References Over Lambdas

Prefer method references when lambda only calls a single method:

```java
// Prefer
.map(Artifact::asString)
.onFailure(Assertions::fail)

// Over
.map(a -> a.asString())
.onFailure(cause -> Assertions.fail())
```

### Immutability

- All domain objects are immutable records
- Use `List.of()`, `Map.of()`, `Set.of()` for collections
- No mutable state in business logic

## Common Patterns in Aether

### Structured Keys Pattern

```java
public sealed interface AetherKey extends StructuredKey {
    String asString();
    boolean matches(StructuredPattern pattern);

    record BlueprintKey(Artifact artifact) implements AetherKey {
        public static Result<BlueprintKey> blueprintKey(String key) {
            // Parse and validate
        }
    }
}
```

### State Machine Pattern

Using sealed interfaces and enums for states:

```java
public enum SliceState {
    LOADING(timeSpan(2).minutes()),
    LOADED,
    ACTIVATING(timeSpan(1).minutes()),
    ACTIVE;

    private final TimeSpan timeout;

    public Set<SliceState> validTransitions() {
        return switch (this) {
            case LOADING -> Set.of(LOADED, FAILED);
            case LOADED -> Set.of(ACTIVATE, UNLOAD);
            // ...
        };
    }
}
```

### Message Router Pattern

For decoupled component communication using ImmutableRouter with Entry-based configuration:

```java
// Collect route entries (production code)
var entries = new ArrayList<MessageRouter.Entry<?>>();
entries.add(MessageRouter.Entry.route(ValuePut.class, this::onValuePut));
entries.add(MessageRouter.Entry.route(ValueRemove.class, this::onValueRemove));
entries.add(MessageRouter.Entry.route(QuorumStateNotification.class, this::onQuorumStateChange));

// For sealed hierarchies, use SealedBuilder for compile-time validation
var sealedEntries = SealedBuilder.from(TopologyChangeNotification.class)
    .route(route(NodeAdded.class, this::onNodeAdded),
           route(NodeRemoved.class, this::onNodeRemoved),
           route(NodeDown.class, this::onNodeDown));

// Build and wire router (validates sealed hierarchies)
RabiaNode.buildAndWireRouter(delegateRouter, allEntries);
```

**Key Points:**
- Use `DelegateRouter` for components that need routing before router is built
- Use `ImmutableRouter` in production (MutableRouter only for tests)
- `SealedBuilder` validates that all sealed hierarchy variants have routes

## Development Status

### Completed ✅

- Core slice lifecycle states and transitions
- Artifact type system (GroupId, ArtifactId, Version)
- KV-Store schema (AetherKey, AetherValue)
- Rabia consensus implementation
- Leader manager for deterministic leader selection
- SliceStore interface and implementation
- NodeDeploymentManager implementation
- Slice class loading and isolation (SliceClassLoader)
- DependencyResolver with cycle detection
- Blueprint DSL parser
- Endpoint registry
- ClusterDeploymentManager (allocation, reconciliation, scale up/down)
- AetherNode assembly (wires all components together)
- AetherNode integration tests (cluster formation, consensus, replication)
- Example slice implementation
- MetricsCollector (per-node JVM and call metrics)
- MetricsScheduler (leader-driven ping-pong distribution)
- DecisionTreeController (programmatic scaling rules)
- ControlLoop (leader-only control evaluation)
- SliceInvoker (inter-slice invocation with retry)
- InvocationHandler (server-side method dispatch)
- ManagementServer (HTTP API for cluster management)
- AetherCli (CLI with REPL and batch modes)
- TTM integration (aether-ttm module, ONNX inference, AdaptiveDecisionTree)
- MinuteAggregator for TTM input preparation

### Planned 📋

- CLI polish and documentation
- Agent API documentation for direct cluster management
- TTM model training pipeline
- LLM integration experiments (Layer 3)

## Important Implementation Notes

### Slice Isolation

Hybrid ClassLoader model:

- Slices isolated from each other
- Share Pragmatica framework classes
- Balances security and performance

### Consensus Integration

- All cluster state flows through KVStore
- ValuePut/ValueRemove notifications drive state changes
- Deterministic leader selection (first node in topology)
- Automatic reconciliation on leader changes

### Promise Timeouts

Timeouts should be as close to actual operations as possible:

```java
// Good - timeout on actual operation
sliceStore.loadSlice(artifact)
    .timeout(configuration.timeoutFor(SliceState.LOADING))
    .flatMap(nextStep::apply)

// Bad - timeout on chain, doesn't cancel operation
sliceStore.loadSlice(artifact)
    .flatMap(nextStep::apply)
    .timeout(someTimeout)  // Too late!
```

### Error Categories

Use sealed interfaces for domain-specific errors:

- `SliceError` - Slice lifecycle failures
- `RegistrationError` - User registration failures
- `RepositoryError` - Data access failures
- `TTMError` - TTM inference failures (ModelLoadFailed, InferenceFailed, InsufficientData, Disabled)

## Testing Framework and Patterns

This project uses JUnit 5 and AssertJ for testing. Follow these established patterns when writing tests:

### Test Class Structure

- Test classes should be package-private (no visibility modifier)
- Use descriptive test method names with underscores: `method_scenario_expectation()`
- Group related test methods logically

### Import Patterns

```java
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
```

### Promise<T> Testing Patterns

#### Async Success Cases

Use `.await()` to block, then test like Result:

```java
@Test
void loadSlice_succeeds_withValidArtifact() {
    LoadSlice loadSlice = artifact -> Promise.success(new LoadedSlice(artifact));
    var artifact = Artifact.artifact("org.example:slice:1.0.0").unwrap();

    loadSlice.apply(artifact)
        .await()
        .onFailure(Assertions::fail)
        .onSuccess(loaded -> {
            assertThat(loaded.artifact()).isEqualTo(artifact);
        });
}
```

#### Async Failure Cases

```java
@Test
void loadSlice_fails_whenArtifactNotFound() {
    LoadSlice loadSlice = artifact -> SliceError.NotFound.INSTANCE.promise();
    var artifact = Artifact.artifact("org.example:slice:1.0.0").unwrap();

    loadSlice.apply(artifact)
        .await()
        .onSuccessRun(Assertions::fail)
        .onFailure(cause -> {
            assertThat(cause).isInstanceOf(SliceError.NotFound.class);
        });
}
```

#### Testing with Stubs

Use type declarations for stub dependencies:

```java
@Test
void execute_succeeds_forValidInput() {
    CheckEmailUniqueness checkEmail = req -> Promise.success(req);
    HashPassword hashPassword = pwd -> Result.success(new HashedPassword("hashed"));
    SaveUser saveUser = user -> Promise.success(new UserId("user-123"));

    var useCase = RegisterUser.registerUser(checkEmail, hashPassword, saveUser);
    var request = new Request("user@example.com", "Valid1234");

    useCase.execute(request)
        .await()
        .onFailure(Assertions::fail)
        .onSuccess(response -> {
            assertThat(response.userId().value()).isEqualTo("user-123");
        });
}
```

### Result<T> Testing Patterns

#### Success Cases

For successful Result operations, use `.onSuccess()` with lambda assertions:

```java
@Test
void parsing_with_valid_input() {
    SomeType.parse("valid-input")
           .onSuccess(result -> {
               assertThat(result.field1()).isEqualTo("expected");
               assertThat(result.field2()).isEqualTo(42);
           })
           .onFailureRun(Assertions::fail);
}
```

#### Failure Cases

For expected failures, use `.onSuccessRun(Assertions::fail)` followed by failure assertions:

```java
@Test
void parsing_rejects_invalid_format() {
    SomeType.parse("invalid-format")
           .onSuccessRun(Assertions::fail)
           .onFailure(cause -> assertThat(cause.message()).contains("Invalid format"));
}
```

#### Simple Success Validation

For cases where you only need to verify success without checking details:

```java
@Test
void parsing_handles_simple_cases() {
    SomeType.parse("valid-input")
           .onFailureRun(Assertions::fail);
}
```

### Complex Object Testing

#### Multiple Component Validation

Use `Result.all()` for complex object construction in tests:

```java
@Test
void complex_object_creation() {
    Result.all(Component1.create("value1"),
               Component2.create("value2"),
               Component3.create("value3"))
          .map(ComplexType::create)
          .map(ComplexType::serialize)
          .onSuccess(result -> assertThat(result).isEqualTo("expected:serialized:format"))
          .onFailureRun(Assertions::fail);
}
```

#### Roundtrip Testing

Always include roundtrip tests for parseable types:

```java
@Test
void parsing_roundtrip_consistency() {
    var originalString = "original:input:format";
    
    SomeType.parse(originalString)
           .map(SomeType::asString)
           .onSuccess(result -> assertThat(result).isEqualTo(originalString))
           .onFailureRun(Assertions::fail);
}
```

### Test Coverage Guidelines

#### Success Path Testing

- Valid input with all components
- Valid input with optional components (qualifiers, etc.)
- Edge cases (zeros, empty optional fields)
- Complex but valid scenarios

#### Failure Path Testing

- Invalid format strings
- Invalid individual components
- Edge case failures (negative numbers, etc.)
- Empty/null inputs

#### Utility Method Testing

- String representation methods (`toString()`, `asString()`, `bareVersion()`)
- Conversion methods between formats
- Optional field handling

### Error Assertion Patterns

#### Detailed Error Messages

When error messages are important, check them specifically:

```java
.onFailure(cause -> assertThat(cause.message()).contains("Invalid version format"))
```

#### Generic Error Validation

When the specific error doesn't matter, just validate failure occurred:

```java
.onFailure(cause -> assertThat(cause).isNotNull())
```

### Test Organization

- Group related tests (valid cases, invalid cases, edge cases)
- Use consistent naming patterns within test classes
- Test both public API methods and core functionality
- Include integration-style tests for complex interactions

### Method Reference Usage

- Use `Assertions::fail` for method references in failure cases
- Use `SomeType::methodName` for method references in mapping operations
- Prefer method references over lambdas when the lambda only calls a single method

## Project-Specific Testing Notes

- All parsing methods return `Result<T>` and should be tested with both success and failure paths
- String serialization methods should have roundtrip tests
- Complex objects built from multiple components should use `Result.all()` patterns
- Error messages should be descriptive and tested when they provide user value

## Build and Test Commands

### Maven Commands

- Build all modules: `mvn clean install`
- Run all tests: `mvn test`
- Run specific test class: `mvn test -Dtest=ClassName`
- Run specific test method: `mvn test -Dtest=ClassName#methodName`
- Skip tests: `mvn install -DskipTests`
- Compile only: `mvn compile`
- Package without tests: `mvn package -DskipTests`

### Module-Specific Commands

```bash
# Test specific module
cd slice && mvn test

# Run tests in examples
cd examples && mvn test
```

## Quick Reference

### Key Files and Locations

- **AetherNode**: `node/src/main/java/org/pragmatica/aether/node/AetherNode.java` - Main node assembly
- **AetherNodeConfig**: `node/src/main/java/org/pragmatica/aether/node/AetherNodeConfig.java` - Node configuration
- **Slice interface**: `slice-api/src/main/java/org/pragmatica/aether/slice/Slice.java`
- **SliceStore**: `slice/src/main/java/org/pragmatica/aether/slice/SliceStore.java`
- **Artifact types**: `slice/src/main/java/org/pragmatica/aether/artifact/`
- **KV Schema**: `slice/src/main/java/org/pragmatica/aether/slice/kvstore/`
- **NodeDeploymentManager**: `node/src/main/java/org/pragmatica/aether/deployment/node/`
- **ClusterDeploymentManager**: `node/src/main/java/org/pragmatica/aether/deployment/cluster/`
- **MetricsCollector**: `node/src/main/java/org/pragmatica/aether/metrics/MetricsCollector.java`
- **InvocationMetricsCollector**: `node/src/main/java/org/pragmatica/aether/metrics/invocation/`
- **ControlLoop**: `node/src/main/java/org/pragmatica/aether/controller/ControlLoop.java`
- **SliceInvoker**: `node/src/main/java/org/pragmatica/aether/invoke/SliceInvoker.java`
- **ManagementServer**: `node/src/main/java/org/pragmatica/aether/api/ManagementServer.java`
- **AetherCli**: `cli/src/main/java/org/pragmatica/aether/cli/AetherCli.java`
- **TTM Core**: `aether-ttm/src/main/java/org/pragmatica/aether/ttm/` - ONNX predictor, forecast types
- **TTMConfig**: `aether-config/src/main/java/org/pragmatica/aether/config/TTMConfig.java`
- **TTMManager**: `node/src/main/java/org/pragmatica/aether/ttm/TTMManager.java` - Leader-aware lifecycle
- **AdaptiveDecisionTree**: `node/src/main/java/org/pragmatica/aether/ttm/AdaptiveDecisionTree.java`
- **MinuteAggregator**: `node/src/main/java/org/pragmatica/aether/metrics/MinuteAggregator.java`
- **Rabia Consensus**: pragmatica-lite `consensus` module (`org.pragmatica.consensus.rabia`)
- **KVStore**: `cluster/src/main/java/org/pragmatica/cluster/state/kvstore/`
- **ForgeServer**: `forge/src/main/java/org/pragmatica/aether/forge/ForgeServer.java` - Aether Forge main entry
- **ForgeCluster**: `forge/src/main/java/org/pragmatica/aether/forge/ForgeCluster.java` - Forge cluster management
- **ForgeApiHandler**: `forge/src/main/java/org/pragmatica/aether/forge/ForgeApiHandler.java` - Forge REST API
- **ArtifactStore**: `infra-services/artifact-repo/src/main/java/org/pragmatica/aether/infra/artifact/ArtifactStore.java` - DHT-backed artifact storage
- **BuiltinRepository**: `node/src/main/java/org/pragmatica/aether/repository/BuiltinRepository.java` - Repository adapter for ArtifactStore
- **RepositoryFactory**: `node/src/main/java/org/pragmatica/aether/repository/RepositoryFactory.java` - Creates Repository instances from config
- **ArtifactDeploymentTracker**: `node/src/main/java/org/pragmatica/aether/metrics/artifact/ArtifactDeploymentTracker.java` - Tracks deployed artifacts
- **ArtifactMetricsCollector**: `node/src/main/java/org/pragmatica/aether/metrics/artifact/ArtifactMetricsCollector.java` - Storage and deployment metrics
- **SliceConfig**: `aether-config/src/main/java/org/pragmatica/aether/config/SliceConfig.java` - Repository configuration
- **RepositoryType**: `aether-config/src/main/java/org/pragmatica/aether/config/RepositoryType.java` - Repository type enum (LOCAL, BUILTIN)

### Documentation

- **Architecture**: `docs/architecture-overview.md` - Comprehensive architecture documentation
- **High-level**: `docs/aether-high-level-overview.md` - Project overview and concepts
- **Infrastructure Services**: `docs/infrastructure-services.md` - Platform services (artifact repo, caching, etc.)
- **Artifact Repository**: `docs/artifact-repository.md` - Artifact storage and deployment guide
- **Slice lifecycle**: `docs/slice-lifecycle.md` - Detailed lifecycle documentation
- **Cluster manager**: `docs/cluster-deployment-manager.md` - ClusterDeploymentManager design
- **Invocation metrics**: `docs/invocation-metrics.md` - Per-method metrics and slow call capture
- **Slice Developer Guide**: `docs/slice-developer-guide.md` - How to write slices
- **Rabia**: `cluster/README.md` - Consensus algorithm documentation

### Common Type Conversions

```java
// Lift to higher types
result.async()                    // Result<T> → Promise<T>
option.async()                    // Option<T> → Promise<T>
option.toResult(cause)            // Option<T> → Result<T>

// Extract values (use carefully, prefer map/flatMap)
result.unwrap()                   // Result<T> → T (throws on failure)
option.orElse(defaultValue)       // Option<T> → T

// Error creation
cause.result()                    // Cause → Result<T>
cause.promise()                   // Cause → Promise<T>
Result.unitResult()               // → Result<Unit>
```

### Naming Conventions Summary

- **Factory methods**: `TypeName.typeName(...)` (lowercase-first)
- **Test methods**: `methodName_outcome_condition`
- **Error types**: Sealed interfaces extending `Cause`
- **Packages**: Feature-based, not layer-based
- **Records**: Use for data carriers and implementations

## Working with This Project

When implementing new features:

1. **Ask questions first** if requirements are unclear
2. **Follow JBCT patterns** (use `/jbct` skill for guidance)
3. **Write tests** following established patterns
4. **Use sealed interfaces** for error hierarchies
5. **Prefer adapter leaves** for all I/O operations
6. **Keep lambdas simple** - extract complex logic to methods
7. **One pattern per function** - split if mixing patterns
8. **Update tests** when changing existing code

When reviewing code:

- Check for `Promise<Result<T>>` anti-pattern
- Verify factory naming convention
- Ensure no business exceptions thrown
- Confirm proper error handling with `Cause`
- Validate test coverage (success and failure paths)
- Look for proper use of method references

Follow these patterns consistently to maintain code quality and test reliability across the Aether project.