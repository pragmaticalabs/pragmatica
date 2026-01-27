# Pragmatica Aether - Development Guide

## Agent Policy (MANDATORY)

**ALWAYS use `jbct-coder` agent for ALL coding and fixing tasks.**

This includes: writing new code, fixing bugs, refactoring, implementing features.
Do NOT write code directly - delegate to `jbct-coder` agent.

## Project Overview

**Pragmatica Aether** (v0.8.1) - AI-driven distributed runtime for Java with predictive scaling and seamless multi-cloud deployment.

**Key Principle:** Every inter-slice call will **EVENTUALLY SUCCEED** if the cluster is alive. Slices are NOT prepared for communication errors - runtime handles retries, failover, recovery. Design slices to be idempotent.

## Module Structure

| Module | Purpose |
|--------|---------|
| `slice-api/` | Slice interface definitions (`Slice`, `SliceMethod`, `SliceRoute`) |
| `slice/` | Slice management (`SliceStore`, `SliceState`, `Artifact` types) |
| `node/` | Runtime node (`NodeDeploymentManager`, `ManagementServer`, metrics, TTM) |
| `cluster/` | Networking and KVStore (`NettyClusterNetwork`, `KVStore`) |
| `aether-ttm/` | TTM ONNX inference for predictive scaling |
| `aether-config/` | Configuration types |
| `forge/` | Simulator CLI with dashboard for load/chaos testing |
| `cli/` | Command-line interface |
| `examples/` | Example slices (`ecommerce/`, `order-demo/`) |
| `infra-slices/` | Infrastructure slices (cache, pubsub, rate limiter, etc.) |

## Core Concepts

### Slices

Two types: **Service Slices** (multiple methods) and **Lean Slices** (single use case). Both managed identically by runtime.

Users write annotated interfaces; `jbct-cli` generates all boilerplate (Slice implementation, routing, factories).

See [docs/contributors/slice-lifecycle.md](docs/contributors/slice-lifecycle.md) for lifecycle states.

### Artifacts

Maven-style coordinates: `groupId:artifactId:version[-qualifier]`

```java
Artifact.artifact("org.example:my-slice:1.0.0")
```

## JBCT Patterns (Essential)

### Four Return Kinds

| Type | Usage |
|------|-------|
| `T` | Synchronous, infallible |
| `Option<T>` | Synchronous, optional value |
| `Result<T>` | Synchronous, fallible |
| `Promise<T>` | Asynchronous, fallible |

**Never** `Promise<Result<T>>` - failures flow through Promise.

### Factory Naming

Always `TypeName.typeName(...)` (lowercase-first):

```java
Artifact.artifact("org.example:slice:1.0.0")
Email.email("user@example.com")
```

### Parse, Don't Validate

```java
public record Email(String value) {
    public static Result<Email> email(String raw) {
        return Verify.ensure(raw, Verify.Is::notNull)
            .map(String::trim)
            .flatMap(v -> Verify.ensure(v, Verify.Is::matches, EMAIL_PATTERN, INVALID_EMAIL))
            .map(Email::new);
    }
}
```

### No Business Exceptions

All failures as typed `Cause`:

```java
public sealed interface SliceError extends Cause {
    record LoadFailed(Artifact artifact, Throwable cause) implements SliceError {
        @Override public String message() { return "Failed to load: " + artifact; }
    }
}
```

### Error Handling with Lift

```java
Promise.lift(SliceError.LoadFailed::cause, () -> classLoader.loadClass(className))
```

### Single Pattern Per Function

- **Leaf** - Single operation
- **Sequencer** - Linear chain
- **Fork-Join** - Parallel operations
- **Condition** - Branching
- **Iteration** - Collection processing

### Record-Based Implementation

```java
public interface RegisterUser {
    Promise<Response> execute(Request request);

    static RegisterUser registerUser(CheckEmail checkEmail, SaveUser saveUser) {
        record registerUser(CheckEmail checkEmail, SaveUser saveUser) implements RegisterUser {
            public Promise<Response> execute(Request request) {
                return ValidRequest.validRequest(request).async()
                    .flatMap(checkEmail::apply)
                    .flatMap(saveUser::apply);
            }
        }
        return new registerUser(checkEmail, saveUser);
    }
}
```

## Coding Conventions

- **Immutability**: All domain objects are immutable records
- **Collections**: Use `List.of()`, `Map.of()`, `Set.of()`
- **Method references**: Prefer `.map(Artifact::asString)` over `.map(a -> a.asString())`
- **Packages**: Group by feature/use case, not by layer
- **Errors**: Sealed interfaces extending `Cause`

## Testing Patterns

### Promise Testing

```java
@Test
void loadSlice_succeeds_withValidArtifact() {
    loadSlice.apply(artifact).await()
        .onFailure(Assertions::fail)
        .onSuccess(loaded -> assertThat(loaded.artifact()).isEqualTo(artifact));
}

@Test
void loadSlice_fails_whenNotFound() {
    loadSlice.apply(artifact).await()
        .onSuccessRun(Assertions::fail)
        .onFailure(cause -> assertThat(cause).isInstanceOf(SliceError.NotFound.class));
}
```

### Result Testing

```java
@Test
void parsing_with_valid_input() {
    SomeType.parse("valid-input")
        .onSuccess(result -> assertThat(result.field()).isEqualTo("expected"))
        .onFailureRun(Assertions::fail);
}
```

### Test Naming

`methodName_scenario_expectation()` - e.g., `loadSlice_succeeds_withValidArtifact`

## Build Commands

```bash
mvn clean install          # Build all
mvn test                   # Run all tests
mvn test -Dtest=ClassName  # Specific test class
mvn test -pl module-name   # Specific module
```

**Important:** Run `mvn install -DskipTests` before testing forge module. Forge tests depend on locally installed artifacts from other modules.

## Parallel Releases with Circular Dependencies

When releasing projects with circular dependencies (e.g., aether ↔ jbct-cli), use this approach:

1. **Comment out** the unpublished plugin in `pom.xml`:
   ```xml
   <!-- jbct-maven-plugin commented out until X.Y.Z is published
   <plugin>
       <groupId>org.pragmatica-lite</groupId>
       <artifactId>jbct-maven-plugin</artifactId>
   </plugin>
   -->
   ```

2. **Release the first project** (aether) without the plugin dependency

3. **Release the second project** (jbct-cli) which depends on the first

4. **Uncomment the plugin** and release a patch version with full functionality

This breaks the circular dependency chain while preserving functionality.

## Key Files

### Core
- `slice-api/.../slice/Slice.java` - Slice interface
- `slice/.../slice/SliceStore.java` - Slice lifecycle management
- `slice/.../artifact/` - Artifact types
- `node/.../node/AetherNode.java` - Main node assembly
- `node/.../node/AetherNodeConfig.java` - Node configuration

### Deployment
- `node/.../deployment/node/NodeDeploymentManager.java`
- `node/.../deployment/cluster/ClusterDeploymentManager.java`

### Metrics & Control
- `node/.../metrics/MetricsCollector.java`
- `node/.../controller/ControlLoop.java`
- `node/.../ttm/TTMManager.java` - Predictive scaling

### Communication
- `node/.../invoke/SliceInvoker.java` - Inter-slice calls
- `node/.../api/ManagementServer.java` - HTTP API
- `cluster/.../kvstore/KVStore.java`

### CLI & Tools
- `cli/.../cli/AetherCli.java`
- `forge/.../forge/ForgeServer.java`

## Type Conversions

```java
result.async()              // Result<T> → Promise<T>
option.toResult(cause)      // Option<T> → Result<T>
cause.result()              // Cause → Result<T>
cause.promise()             // Cause → Promise<T>
result.unwrap()             // Result<T> → T (throws on failure)
```

## Documentation

| Topic | Location |
|-------|----------|
| **Future Work** | [docs/internal/progress/development-priorities.md](docs/internal/progress/development-priorities.md) |
| Architecture | [docs/contributors/architecture.md](docs/contributors/architecture.md) |
| Slice Lifecycle | [docs/contributors/slice-lifecycle.md](docs/contributors/slice-lifecycle.md) |
| Metrics & Control | [docs/contributors/metrics-control.md](docs/contributors/metrics-control.md) |
| TTM Integration | [docs/contributors/ttm-integration.md](docs/contributors/ttm-integration.md) |
| Consensus | [docs/contributors/consensus.md](docs/contributors/consensus.md) |
| Slice Development | [docs/slice-developers/slice-patterns.md](docs/slice-developers/slice-patterns.md) |
| CLI Reference | [docs/reference/cli.md](docs/reference/cli.md) |
| Configuration | [docs/reference/configuration.md](docs/reference/configuration.md) |

## Checklist

When implementing:
1. Use `jbct-coder` agent for all code
2. Follow JBCT patterns (`/jbct` skill)
3. Write tests (success + failure paths)
4. Use sealed interfaces for errors
5. Keep lambdas simple
6. One pattern per function

When reviewing:
- No `Promise<Result<T>>`
- Factory naming correct
- No business exceptions
- Proper `Cause` handling
- Test coverage complete
