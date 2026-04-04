# Unified Blueprint-Level Deployment Specification

**Status:** Implementation-ready  
**Version:** 1.0  
**Date:** 2026-04-01  
**Target file:** `/Users/sergiyyevtushenko/IdeaProjects/pragmatica/aether/docs/specs/unified-deploy-spec.md`

---

## 1. Overview and Motivation

### 1.1 Problem

Aether currently has three separate deployment strategy managers, each operating on a **single artifact** (`ArtifactBase`):

- `RollingUpdateManager` -- `/api/rolling-update/*`
- `CanaryDeploymentManager` -- `/api/canary/*`
- `BlueGreenDeploymentManager` -- `/api/blue-green/*`

This is architecturally wrong. An application blueprint contains multiple slices (e.g., `gateway`, `auth-service`, `order-service`). Upgrading only one slice at a time breaks inter-slice compatibility guarantees. Deployments must operate at the **blueprint level**, upgrading all slices atomically.

Additionally, the three separate managers share 80%+ of their code: leader checking, KV-Store persistence, state restoration, terminal pruning, and routing management. The only meaningful differences are:

1. **Immediate**: Update all SliceTargetKeys at once (current CDM behavior for new blueprints)
2. **Rolling**: Deploy new version instances first, then shift traffic gradually
3. **Canary**: Deploy a small canary fleet, advance through staged traffic percentages
4. **Blue-green**: Deploy full green environment, then atomic traffic switch

### 1.2 Goals

- **REQ-1**: Single `DeploymentManager` interface replacing three separate managers
- **REQ-2**: All deployments operate on blueprints (all slices move together)
- **REQ-3**: Four strategies: `IMMEDIATE`, `ROLLING`, `CANARY`, `BLUE_GREEN`
- **REQ-4**: Atomic multi-slice transitions via consensus batch apply
- **REQ-5**: Single REST endpoint namespace: `/api/deploy/*`
- **REQ-6**: Single CLI command group: `aether deploy`
- **REQ-7**: Complete removal of old per-strategy endpoints, managers, and CLI commands
- **REQ-8**: Backward-compatible KV-Store key for deployment state (single `DeploymentKey`)

### 1.3 Non-Goals

- A/B testing (`AbTestManager`) is excluded from this unification -- it serves a different purpose (experimentation vs. upgrades)
- Auto-scaling during deployments
- Cross-cluster deployments

---

## 2. Unified Data Model

### 2.1 Deployment Record

A single `Deployment` record replaces `RollingUpdate`, `CanaryDeployment`, and `BlueGreenDeployment`.

```java
package org.pragmatica.aether.update;

/// Unified deployment record for all strategies.
/// Operates at the blueprint level -- all slices in a blueprint move together.
///
/// Immutable record -- state changes create new instances.
public record Deployment(
    String deploymentId,
    BlueprintId blueprintId,
    DeployStrategy strategy,
    DeploymentState state,
    Version oldVersion,
    Version newVersion,
    List<ArtifactBase> artifacts,      // all slices in the blueprint
    VersionRouting routing,
    DeploymentConfig config,
    int currentStageIndex,             // canary only; -1 for others
    long stageEnteredAt,               // canary only; 0 for others
    long createdAt,
    long updatedAt
) {
    // Factory method
    public static Deployment deployment(String deploymentId,
                                        BlueprintId blueprintId,
                                        DeployStrategy strategy,
                                        Version oldVersion,
                                        Version newVersion,
                                        List<ArtifactBase> artifacts,
                                        DeploymentConfig config) { ... }

    public Result<Deployment> transitionTo(DeploymentState newState) { ... }
    public Result<Deployment> withRouting(VersionRouting newRouting) { ... }
    public Result<Deployment> advanceStage() { ... }  // canary only
    public boolean isTerminal() { ... }
    public boolean isActive() { ... }
    public Option<CanaryStage> currentStage() { ... }
}
```

### 2.2 Deploy Strategy Enum

```java
package org.pragmatica.aether.update;

/// The four deployment strategies.
public enum DeployStrategy {
    /// Update all SliceTargetKeys immediately. No traffic splitting.
    IMMEDIATE,

    /// Deploy new version instances, then shift traffic gradually via routing ratios.
    ROLLING,

    /// Deploy canary fleet, advance through staged traffic percentages with health gates.
    CANARY,

    /// Deploy full green environment, then atomic traffic switch.
    BLUE_GREEN
}
```

### 2.3 Unified Deployment State

Replaces `RollingUpdateState`, `CanaryState`, and `BlueGreenState`.

```java
package org.pragmatica.aether.update;

public enum DeploymentState {
    // Common states
    PENDING,
    DEPLOYING,         // new version instances being created
    DEPLOYED,          // new version running, no traffic yet (rolling/canary)
                       // OR green environment ready (blue-green)

    // Traffic management
    ROUTING,           // traffic being shifted (rolling: manual ratio; canary: staged)
    SWITCHED,          // blue-green: all traffic on green

    // Completion
    COMPLETING,        // old version being drained/cleaned up
    COMPLETED,         // terminal: deployment finished successfully

    // Failure
    ROLLING_BACK,      // reverting to old version
    ROLLED_BACK,       // terminal: rollback finished

    // Immediate-only
    APPLIED;           // terminal: immediate deployment applied (no staging)

    public boolean isTerminal() {
        return this == COMPLETED || this == ROLLED_BACK || this == APPLIED;
    }

    public Set<DeploymentState> validTransitions() {
        return switch (this) {
            case PENDING     -> Set.of(DEPLOYING, APPLIED, ROLLING_BACK);
            case DEPLOYING   -> Set.of(DEPLOYED, ROLLING_BACK);
            case DEPLOYED    -> Set.of(ROUTING, SWITCHED, COMPLETING, ROLLING_BACK);
            case ROUTING     -> Set.of(ROUTING, COMPLETING, ROLLING_BACK);
            case SWITCHED    -> Set.of(COMPLETING, ROLLING_BACK);
            case COMPLETING  -> Set.of(COMPLETED, ROLLING_BACK);
            case ROLLING_BACK -> Set.of(ROLLED_BACK);
            case COMPLETED, ROLLED_BACK, APPLIED -> Set.of();
        };
    }
}
```

### 2.4 Deployment Configuration

Strategy-specific parameters in a single config record:

```java
package org.pragmatica.aether.update;

/// Strategy-specific deployment configuration.
///
/// Fields not applicable to a strategy are ignored (use defaults).
public record DeploymentConfig(
    int instances,                     // target instance count per slice
    HealthThresholds thresholds,
    CleanupPolicy cleanupPolicy,

    // Canary-specific
    List<CanaryStage> stages,          // empty for non-canary
    CanaryAnalysisConfig analysisConfig,

    // Blue-green-specific
    long drainTimeoutMs
) {
    public static DeploymentConfig deploymentConfig(int instances,
                                                     HealthThresholds thresholds,
                                                     CleanupPolicy cleanupPolicy) {
        return new DeploymentConfig(instances, thresholds, cleanupPolicy,
                                     List.of(), CanaryAnalysisConfig.DEFAULT, 30_000L);
    }

    public static DeploymentConfig canaryConfig(int instances,
                                                 HealthThresholds thresholds,
                                                 CleanupPolicy cleanupPolicy,
                                                 List<CanaryStage> stages,
                                                 CanaryAnalysisConfig analysisConfig) {
        return new DeploymentConfig(instances, thresholds, cleanupPolicy,
                                     List.copyOf(stages), analysisConfig, 0);
    }

    public static DeploymentConfig blueGreenConfig(int instances,
                                                    HealthThresholds thresholds,
                                                    CleanupPolicy cleanupPolicy,
                                                    long drainTimeoutMs) {
        return new DeploymentConfig(instances, thresholds, cleanupPolicy,
                                     List.of(), CanaryAnalysisConfig.DEFAULT, drainTimeoutMs);
    }
}
```

---

## 3. REST API Design

### 3.1 Endpoint Summary

All endpoints under `/api/deploy`. Replaces `/api/rolling-update/*`, `/api/canary/*`, `/api/blue-green/*`.

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/deploy` | Start a deployment |
| `GET` | `/api/deploy` | List all deployments |
| `GET` | `/api/deploy/{id}` | Get deployment by ID |
| `GET` | `/api/deploy/{id}/health` | Get deployment health metrics |
| `POST` | `/api/deploy/{id}/promote` | Promote (advance canary stage, or switch blue-green) |
| `POST` | `/api/deploy/{id}/routing` | Adjust traffic routing (rolling) |
| `POST` | `/api/deploy/{id}/complete` | Complete deployment (drain old version) |
| `POST` | `/api/deploy/{id}/rollback` | Rollback deployment |

### 3.2 Request/Response Shapes

#### POST /api/deploy -- Start Deployment

```json
{
  "blueprint": "org.example:commerce:2.0.0",
  "strategy": "ROLLING",
  "instances": 3,
  "maxErrorRate": 0.01,
  "maxLatencyMs": 500,
  "cleanupPolicy": "GRACE_PERIOD",
  "drainTimeoutMs": 30000,
  "stages": [
    { "trafficPercent": 5, "observationMinutes": 10 },
    { "trafficPercent": 25, "observationMinutes": 15 },
    { "trafficPercent": 50, "observationMinutes": 15 },
    { "trafficPercent": 100, "observationMinutes": 5 }
  ]
}
```

Field rules:
- `blueprint` (required): Full Maven coordinates `group:artifact:version`
- `strategy` (optional, default `IMMEDIATE`): One of `IMMEDIATE`, `ROLLING`, `CANARY`, `BLUE_GREEN`
- `instances` (optional, default from existing SliceTarget or 1)
- `stages` (required for `CANARY`, ignored otherwise)
- `drainTimeoutMs` (optional, default 30000, used by `BLUE_GREEN`)
- `maxErrorRate`, `maxLatencyMs` (optional, defaults 0.01 / 500)

#### Response Shape (all endpoints)

```json
{
  "deploymentId": "2fK8x...",
  "blueprintId": "org.example:commerce:2.0.0",
  "strategy": "ROLLING",
  "state": "DEPLOYED",
  "oldVersion": "1.0.0",
  "newVersion": "2.0.0",
  "artifacts": ["org.example:gateway", "org.example:auth", "org.example:orders"],
  "routing": "0:1",
  "instances": 3,
  "currentStage": 0,
  "totalStages": 0,
  "createdAt": 1711929600000,
  "updatedAt": 1711929660000
}
```

#### POST /api/deploy/{id}/routing

```json
{
  "routing": "1:3"
}
```

#### GET /api/deploy/{id}/health

```json
{
  "deploymentId": "2fK8x...",
  "oldVersion": {
    "version": "1.0.0",
    "requestCount": 45000,
    "errorRate": 0.002,
    "p99LatencyMs": 120,
    "avgLatencyMs": 45
  },
  "newVersion": {
    "version": "2.0.0",
    "requestCount": 15000,
    "errorRate": 0.001,
    "p99LatencyMs": 95,
    "avgLatencyMs": 38
  },
  "collectedAt": 1711929900000
}
```

#### GET /api/deploy -- List Deployments

Optional query parameter: `?active=true` (default: only active)

```json
{
  "deployments": [ ... ]
}
```

---

## 4. Internal Architecture

### 4.1 DeploymentManager Interface

Single manager replacing the three existing ones.

```java
package org.pragmatica.aether.update;

public interface DeploymentManager {
    /// Start a new deployment for a blueprint.
    /// Resolves all slices from the currently deployed blueprint (or from the new
    /// blueprint if this is a first deployment).
    Promise<Deployment> startDeployment(BlueprintId newBlueprintId,
                                         DeployStrategy strategy,
                                         DeploymentConfig config);

    /// Promote the deployment:
    ///   - Canary: advance to next stage
    ///   - Blue-green: switch traffic to green
    ///   - Rolling: set routing to ALL_NEW
    Promise<Deployment> promote(String deploymentId);

    /// Adjust traffic routing (rolling strategy).
    Promise<Deployment> adjustRouting(String deploymentId, VersionRouting routing);

    /// Complete the deployment (drain old version, finalize).
    Promise<Deployment> complete(String deploymentId);

    /// Rollback the deployment to old version.
    Promise<Deployment> rollback(String deploymentId);

    /// Get deployment by ID.
    Option<Deployment> getDeployment(String deploymentId);

    /// Get active deployment for a blueprint.
    Option<Deployment> getActiveDeployment(BlueprintId blueprintId);

    /// List all active deployments.
    List<Deployment> activeDeployments();

    /// List all deployments (including terminal).
    List<Deployment> allDeployments();

    /// Get health metrics comparing old/new versions across all slices.
    Promise<DeploymentHealthMetrics> getHealthMetrics(String deploymentId);

    /// Handle leader change (restore state on becoming leader).
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    /// Handle deployment failure for auto-rollback.
    @MessageReceiver
    void onDeploymentFailed(DeploymentEvent.DeploymentFailed event);

    // Factory
    static DeploymentManager deploymentManager(RabiaNode<KVCommand<AetherKey>> clusterNode,
                                                KVStore<AetherKey, AetherValue> kvStore,
                                                InvocationMetricsCollector metricsCollector) { ... }
}
```

### 4.2 Strategy Execution Pattern

The `DeploymentManager` implementation delegates strategy-specific behavior through internal methods, not separate classes. The strategy differences are small enough to handle via `switch`:

```java
private Promise<Deployment> executeStrategy(Deployment deployment) {
    return switch (deployment.strategy()) {
        case IMMEDIATE  -> executeImmediate(deployment);
        case ROLLING    -> executeRollingDeploy(deployment);
        case CANARY     -> executeCanaryDeploy(deployment);
        case BLUE_GREEN -> executeBlueGreenDeploy(deployment);
    };
}
```

Each method builds the appropriate batch of KV commands for **all slices** in the blueprint.

### 4.3 Canary Evaluation Loop

The canary evaluation timer (from current `CanaryDeploymentManager`) moves into `DeploymentManager`. It periodically checks all active canary deployments for:
- Health threshold breaches (auto-rollback)
- Observation period completion (auto-advance stage)

---

## 5. KV-Store Key/Value Changes

### 5.1 New Key: DeploymentKey

```java
// In AetherKey.java
record DeploymentKey(String deploymentId) implements AetherKey {
    private static final String PREFIX = "deployment:";

    @Override public String asString() {
        return PREFIX + deploymentId;
    }

    public static Result<DeploymentKey> deploymentKey(String key) {
        if (!key.startsWith(PREFIX)) return INVALID_PREFIX.apply(key).result();
        return success(new DeploymentKey(key.substring(PREFIX.length())));
    }
}
```

### 5.2 New Value: DeploymentValue

```java
// In AetherValue.java
record DeploymentValue(
    String deploymentId,
    String blueprintId,         // BlueprintId.asString()
    String strategy,            // DeployStrategy.name()
    String state,               // DeploymentState.name()
    String oldVersion,
    String newVersion,
    String artifactsJson,       // comma-separated ArtifactBase.asString()
    int newWeight,
    int oldWeight,
    int instances,
    double maxErrorRate,
    long maxLatencyMs,
    boolean requireManualApproval,
    String cleanupPolicy,
    String stagesJson,          // canary stages serialized
    long drainTimeoutMs,
    int currentStageIndex,
    long stageEnteredAt,
    String blueprintContextId,  // for linking to AppBlueprintKey
    long createdAt,
    long updatedAt
) implements AetherValue { ... }
```

### 5.3 Keys Retained (unchanged)

- `SliceTargetKey` / `SliceTargetValue` -- still used per-slice for instance count and version
- `VersionRoutingKey` / `VersionRoutingValue` -- still used per-slice for traffic splitting
- `AppBlueprintKey` / `AppBlueprintValue` -- still used for blueprint definition

### 5.4 Keys Removed

- `RollingUpdateKey` / `RollingUpdateValue`
- `CanaryDeploymentKey` / `CanaryDeploymentValue`
- `BlueGreenDeploymentKey` / `BlueGreenDeploymentValue`

---

## 6. Blueprint Resolution Flow

### 6.1 How to Get All Slices from a Deployed Blueprint

When `startDeployment(newBlueprintId, ...)` is called:

1. **Parse the new blueprint**: The `newBlueprintId` is a full Maven coordinate (`group:artifact:version`). The artifact must already be in the cluster repository. The CDM previously expanded it into an `AppBlueprintValue` stored under `AppBlueprintKey(blueprintId)`.

2. **Find the old version**: Look up `AppBlueprintKey` entries in the KV-Store. Find the one with the same `group:artifact` but old version. Extract its `ExpandedBlueprint.loadOrder()` to get the list of `ResolvedSlice` records.

3. **Determine old version**: The old blueprint's `BlueprintId.artifact().version()` gives the old version. All slices in the old blueprint share this version (blueprint-level versioning).

4. **Build artifacts list**: Map `ExpandedBlueprint.loadOrder()` to `List<ArtifactBase>` (drop version from each `ResolvedSlice.artifact()`).

5. **Instance counts**: Each `ResolvedSlice` carries its own `instances` count. During deployment, the unified manager writes one `SliceTargetKey` per slice, each with the per-slice instance count from the blueprint.

### 6.2 Blueprint Lookup Algorithm

```java
private Promise<BlueprintResolution> resolveBlueprint(BlueprintId newBlueprintId) {
    var newArtifact = newBlueprintId.artifact();
    var newBase = newArtifact.base();
    var newVersion = newArtifact.version();

    // Find existing blueprint for same group:artifact (any version)
    var existingBlueprint = findExistingBlueprint(newBase);

    // Get expanded blueprint for new version (must be in artifact store)
    return expandBlueprint(newBlueprintId)
        .map(expanded -> {
            var oldVersion = existingBlueprint
                .map(bp -> bp.id().artifact().version());
            var artifacts = expanded.loadOrder().stream()
                .map(rs -> rs.artifact().base())
                .toList();
            return new BlueprintResolution(expanded, oldVersion, artifacts);
        });
}
```

### 6.3 First Deployment vs. Upgrade

- **First deployment** (`oldVersion` is absent): Strategy must be `IMMEDIATE`. The deployment writes `SliceTargetKey` entries for all slices and transitions directly to `APPLIED`.
- **Upgrade** (`oldVersion` is present): Any strategy is valid. The old `SliceTargetKey` entries already exist; the deployment creates routing entries and updates targets.

---

## 7. Atomic Multi-Slice Transitions

### 7.1 Batch KV Operations

All state transitions that affect multiple slices must be applied as a single consensus batch. The existing `clusterNode.apply(List<KVCommand>)` method already supports this.

### 7.2 IMMEDIATE Strategy

```java
private Promise<Deployment> executeImmediate(Deployment deployment) {
    // Build batch: one SliceTargetKey put per slice
    var commands = deployment.artifacts().stream()
        .map(artifactBase -> {
            var key = SliceTargetKey.sliceTargetKey(artifactBase);
            var value = SliceTargetValue.sliceTargetValue(
                deployment.newVersion(),
                resolveInstances(artifactBase, deployment),
                Option.some(deployment.blueprintId()));
            return (KVCommand<AetherKey>)(KVCommand<?>) new KVCommand.Put<>(key, value);
        })
        .toList();

    return clusterNode.apply(commands)
        .timeout(kvOperationTimeout)
        .flatMap(_ -> persistAndTransition(deployment, DeploymentState.APPLIED));
}
```

### 7.3 ROLLING / CANARY / BLUE_GREEN Deploy Phase

All three deploy new version instances for all slices simultaneously:

```java
private Promise<Deployment> deployNewVersionForAllSlices(Deployment deployment) {
    var commands = new ArrayList<KVCommand<AetherKey>>();

    for (var artifactBase : deployment.artifacts()) {
        // Routing: old -> new with current weights
        var routingKey = VersionRoutingKey.versionRoutingKey(artifactBase);
        var routingValue = new VersionRoutingValue(
            deployment.oldVersion(), deployment.newVersion(),
            deployment.routing().newWeight(), deployment.routing().oldWeight(),
            System.currentTimeMillis());
        commands.add(cast(new KVCommand.Put<>(routingKey, routingValue)));

        // Target: update to new version
        var targetKey = SliceTargetKey.sliceTargetKey(artifactBase);
        var instances = resolveInstances(artifactBase, deployment);
        var targetValue = new SliceTargetValue(
            deployment.newVersion(), instances, instances,
            Option.some(deployment.blueprintId()), "CORE_ONLY",
            System.currentTimeMillis());
        commands.add(cast(new KVCommand.Put<>(targetKey, targetValue)));
    }

    return clusterNode.apply(commands)
        .timeout(kvOperationTimeout)
        .flatMap(_ -> persistAndTransition(deployment, DeploymentState.DEPLOYED));
}
```

### 7.4 Traffic Switch (Promote) -- Atomic

When promoting (canary stage advance, blue-green switch, rolling complete), routing for **all slices** updates in one batch:

```java
private Promise<Deployment> applyRoutingToAllSlices(Deployment deployment) {
    var commands = deployment.artifacts().stream()
        .map(artifactBase -> {
            var key = VersionRoutingKey.versionRoutingKey(artifactBase);
            var value = new VersionRoutingValue(
                deployment.oldVersion(), deployment.newVersion(),
                deployment.routing().newWeight(), deployment.routing().oldWeight(),
                System.currentTimeMillis());
            return cast(new KVCommand.Put<>(key, value));
        })
        .toList();

    return clusterNode.apply(commands).timeout(kvOperationTimeout);
}
```

### 7.5 Rollback -- Atomic

Rollback reverts all slices to old version in a single batch:

```java
private Promise<Deployment> rollbackAllSlices(Deployment deployment) {
    var commands = new ArrayList<KVCommand<AetherKey>>();

    for (var artifactBase : deployment.artifacts()) {
        // Restore old version target
        var targetKey = SliceTargetKey.sliceTargetKey(artifactBase);
        var targetValue = new SliceTargetValue(
            deployment.oldVersion(),
            resolveInstances(artifactBase, deployment),
            resolveInstances(artifactBase, deployment),
            Option.some(deployment.blueprintId()), "CORE_ONLY",
            System.currentTimeMillis());
        commands.add(cast(new KVCommand.Put<>(targetKey, targetValue)));

        // Remove routing
        var routingKey = VersionRoutingKey.versionRoutingKey(artifactBase);
        commands.add(cast(new KVCommand.Remove<>(routingKey)));
    }

    return clusterNode.apply(commands)
        .timeout(kvOperationTimeout)
        .flatMap(_ -> persistAndTransition(deployment, DeploymentState.ROLLED_BACK));
}
```

---

## 8. CLI Command Design

### 8.1 Command Structure

```
aether deploy <group:artifact:version> [--strategy canary|blue-green|rolling] [options]
aether deploy list [--all]
aether deploy status <id>
aether deploy health <id>
aether deploy promote <id>
aether deploy routing <id> <ratio>
aether deploy complete <id>
aether deploy rollback <id>
```

### 8.2 Start Deployment

```
aether deploy org.example:commerce:2.0.0

aether deploy org.example:commerce:2.0.0 --strategy canary \
    --stages "5:10,25:15,50:15,100:5" \
    -n 3

aether deploy org.example:commerce:2.0.0 --strategy blue-green \
    -n 3 --drain-timeout 60000

aether deploy org.example:commerce:2.0.0 --strategy rolling \
    -n 3 --error-rate 0.02 --latency-ms 1000
```

Options:
- `--strategy` / `-s`: `immediate` (default), `canary`, `blue-green`, `rolling`
- `--instances` / `-n`: Target instance count per slice (default: from existing blueprint)
- `--error-rate`: Max error rate threshold (default: 0.01)
- `--latency-ms`: Max p99 latency threshold (default: 500)
- `--cleanup`: `GRACE_PERIOD` (default) or `MANUAL`
- `--stages`: Canary stages as `percent:minutes` pairs, comma-separated
- `--drain-timeout`: Blue-green drain timeout in ms (default: 30000)

### 8.3 Lifecycle Commands

```
aether deploy list              # active deployments only
aether deploy list --all        # include terminal
aether deploy status <id>       # detailed status of one deployment
aether deploy health <id>       # health metrics comparison
aether deploy promote <id>      # advance canary stage / switch blue-green / promote rolling
aether deploy routing <id> 1:3  # adjust rolling traffic ratio
aether deploy complete <id>     # finalize (drain old version)
aether deploy rollback <id>     # rollback to old version
```

### 8.4 CLI Implementation

```java
@Command(name = "deploy",
         description = "Deploy and manage blueprint upgrades",
         subcommands = {
             DeployCommand.StartCommand.class,
             DeployCommand.ListCommand.class,
             DeployCommand.StatusCommand.class,
             DeployCommand.HealthCommand.class,
             DeployCommand.PromoteCommand.class,
             DeployCommand.RoutingCommand.class,
             DeployCommand.CompleteCommand.class,
             DeployCommand.RollbackCommand.class
         })
static class DeployCommand implements Runnable { ... }
```

The `StartCommand` is the default when `aether deploy <coords>` is invoked:

```java
@Command(name = "start", description = "Start a blueprint deployment (default subcommand)")
static class StartCommand implements Callable<Integer> {
    @Parameters(index = "0", description = "Blueprint coordinates (group:artifact:version)")
    private String coordinates;

    @Option(names = {"-s", "--strategy"}, description = "Deployment strategy",
            defaultValue = "immediate")
    private String strategy;

    @Option(names = {"-n", "--instances"}, description = "Instances per slice")
    private Integer instances;

    @Option(names = {"--error-rate"}, description = "Max error rate", defaultValue = "0.01")
    private double errorRate;

    @Option(names = {"--latency-ms"}, description = "Max p99 latency ms", defaultValue = "500")
    private long latencyMs;

    @Option(names = {"--cleanup"}, description = "Cleanup policy", defaultValue = "GRACE_PERIOD")
    private String cleanupPolicy;

    @Option(names = {"--stages"}, description = "Canary stages (percent:minutes,...)")
    private String stages;

    @Option(names = {"--drain-timeout"}, description = "Blue-green drain timeout ms",
            defaultValue = "30000")
    private long drainTimeoutMs;

    @Override public Integer call() {
        var body = buildDeployBody();
        var response = parent.postToNode("/api/deploy", body);
        return OutputFormatter.printQuery(response, parent.outputOptions());
    }
}
```

---

## 9. Files to Create, Modify, and Delete

### 9.1 Files to Create

| File | Description |
|------|-------------|
| `aether/aether-invoke/.../update/Deployment.java` | Unified deployment record |
| `aether/aether-invoke/.../update/DeployStrategy.java` | Strategy enum |
| `aether/aether-invoke/.../update/DeploymentState.java` | Unified state machine |
| `aether/aether-invoke/.../update/DeploymentConfig.java` | Strategy-specific config |
| `aether/aether-invoke/.../update/DeploymentManager.java` | Unified manager interface + implementation |
| `aether/aether-invoke/.../update/DeploymentError.java` | Unified error causes |
| `aether/aether-invoke/.../update/DeploymentHealthMetrics.java` | Aggregated health across all slices |
| `aether/node/.../api/routes/DeployRoutes.java` | Unified REST routes |

### 9.2 Files to Modify

| File | Change |
|------|--------|
| `aether/slice/.../kvstore/AetherKey.java` | Add `DeploymentKey`; remove `RollingUpdateKey`, `CanaryDeploymentKey`, `BlueGreenDeploymentKey` |
| `aether/slice/.../kvstore/AetherValue.java` | Add `DeploymentValue`; remove `RollingUpdateValue`, `CanaryDeploymentValue`, `BlueGreenDeploymentValue` |
| `aether/node/.../api/ManagementServer.java` | Replace `rollingUpdateRoutes`, `canaryRoutes`, `blueGreenRoutes` with `deployRoutes` |
| `aether/node/.../api/ManagementApiResponses.java` | Replace per-strategy response records with unified `DeploymentInfo`, `DeploymentListResponse`, `DeploymentHealthResponse` |
| `aether/node/.../node/AetherNode.java` | Replace `rollingUpdateManager()`, `canaryDeploymentManager()`, `blueGreenDeploymentManager()` with `deploymentManager()` |
| `aether/aether-invoke/.../update/DeploymentStrategy.java` | Update sealed permits to drop old types (or remove if `Deployment` replaces it) |
| `aether/aether-invoke/.../update/DeploymentStrategyCoordinator.java` | Simplify to wrap single `DeploymentManager` |
| `aether/cli/.../cli/AetherCli.java` | Replace `UpdateCommand`, `CanaryCommand`, `BlueGreenCommand` with `DeployCommand` |
| `aether/aether-deployment/.../cluster/ClusterDeploymentManager.java` | Update message routing for `DeploymentKey`/`DeploymentValue` |
| `aether/docs/reference/management-api.md` | Update API documentation |
| `aether/docs/reference/cli.md` | Update CLI documentation |

### 9.3 Files to Delete

| File | Reason |
|------|--------|
| `aether/aether-invoke/.../update/RollingUpdate.java` | Replaced by `Deployment` |
| `aether/aether-invoke/.../update/RollingUpdateManager.java` | Replaced by `DeploymentManager` |
| `aether/aether-invoke/.../update/RollingUpdateState.java` | Replaced by `DeploymentState` |
| `aether/aether-invoke/.../update/RollingUpdateError.java` | Replaced by `DeploymentError` |
| `aether/aether-invoke/.../update/CanaryDeployment.java` | Replaced by `Deployment` |
| `aether/aether-invoke/.../update/CanaryDeploymentManager.java` | Replaced by `DeploymentManager` |
| `aether/aether-invoke/.../update/CanaryState.java` | Replaced by `DeploymentState` |
| `aether/aether-invoke/.../update/BlueGreenDeployment.java` | Replaced by `Deployment` |
| `aether/aether-invoke/.../update/BlueGreenDeploymentManager.java` | Replaced by `DeploymentManager` |
| `aether/aether-invoke/.../update/BlueGreenState.java` | Replaced by `DeploymentState` |
| `aether/aether-invoke/.../update/BlueGreenDeploymentError.java` | Replaced by `DeploymentError` |
| `aether/node/.../api/routes/RollingUpdateRoutes.java` | Replaced by `DeployRoutes` |
| `aether/node/.../api/routes/CanaryRoutes.java` | Replaced by `DeployRoutes` |
| `aether/node/.../api/routes/BlueGreenRoutes.java` | Replaced by `DeployRoutes` |

### 9.4 Files to Retain (shared infrastructure)

These types are used by the unified model and remain unchanged:

- `VersionRouting.java` -- traffic ratio type
- `HealthThresholds.java` -- health threshold config
- `CleanupPolicy.java` -- cleanup policy enum
- `CanaryStage.java` -- canary stage definition (used by `DeploymentConfig`)
- `CanaryAnalysisConfig.java` -- canary analysis params
- `CanaryHealthComparison.java` -- health comparison result (rename to `DeploymentHealthComparison`)

---

## 10. Migration Path

### 10.1 Execution Order

This is a breaking change with no backward compatibility requirement (per the problem statement). Execute in this order:

1. **Create new types**: `Deployment`, `DeployStrategy`, `DeploymentState`, `DeploymentConfig`, `DeploymentError`
2. **Create `DeploymentManager`**: Implement the unified manager with all four strategies
3. **Create `DeployRoutes`**: Unified REST routes
4. **Create `DeployCommand`**: Unified CLI command group
5. **Update `AetherKey`/`AetherValue`**: Add `DeploymentKey`/`DeploymentValue`, remove old keys
6. **Update `AetherNode`**: Wire `DeploymentManager`, remove old manager references
7. **Update `ManagementServer`**: Wire `DeployRoutes`, remove old route sources
8. **Update `DeploymentStrategyCoordinator`**: Simplify to wrap single manager
9. **Update `ClusterDeploymentManager`**: Handle new key type in message routing
10. **Delete old files**: All per-strategy managers, records, routes, states
11. **Update CLI**: Remove `UpdateCommand`, `CanaryCommand`, `BlueGreenCommand`
12. **Update docs**: management-api.md, cli.md, feature-catalog.md

### 10.2 Test Updates

- Remove per-strategy unit tests
- Add unified `DeploymentTest`, `DeploymentManagerTest`, `DeployRoutesTest`
- Test each strategy through the unified interface
- Test atomic multi-slice transitions (verify all slices move in one batch)
- Test rollback atomicity
- Test canary auto-advance and auto-rollback
- Test blueprint resolution (first deploy vs. upgrade)

### 10.3 KV-Store Schema Migration

Since old deployment keys (`RollingUpdateKey`, `CanaryDeploymentKey`, `BlueGreenDeploymentKey`) tracked in-flight deployments, and all deployments are leader-managed:

- On leader startup, the new `DeploymentManager` only looks for `DeploymentKey` entries
- Old key types are simply not restored -- any in-flight old-format deployments are lost on upgrade
- [ASSUMPTION] This is acceptable because deployments are short-lived and the upgrade itself requires a cluster restart

---

## 11. Edge Cases

### 11.1 Concurrent Deployments

Only one active deployment per blueprint is allowed. Attempting a second deployment for the same `group:artifact` (any version) returns `DeploymentError.DeploymentAlreadyExists`.

### 11.2 Mixed Blueprint and Standalone Slices

If a slice exists as standalone (no `owningBlueprint`) and is also part of a blueprint being deployed, the deployment takes ownership. The `SliceTargetValue.owningBlueprint` is updated.

### 11.3 Partial Failure During Batch Apply

If the consensus batch apply fails (timeout, leader change), the deployment transitions to `ROLLING_BACK` and the auto-rollback mechanism handles cleanup.

### 11.4 Leader Failover During Deployment

Same as current behavior: the new leader restores `DeploymentKey` entries from KV-Store and resumes managing active deployments. The evaluation loop (for canary) is restarted.

### 11.5 Blueprint With Changed Slice Set

If the new blueprint version adds or removes slices compared to the old version:
- **Added slices**: Treated as first deployment for those slices (deployed with new version, no routing needed)
- **Removed slices**: Drained during completion phase (their `SliceTargetKey` is removed in the cleanup batch)

---

## References

### Internal References
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/update/` -- current deployment strategy implementations
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/` -- current REST routes
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterDeploymentManager.java` -- CDM blueprint handling
- `aether/slice/src/main/java/org/pragmatica/aether/slice/blueprint/` -- blueprint model (ExpandedBlueprint, ResolvedSlice, BlueprintId)
- `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherKey.java` -- KV-Store key definitions
- `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java` -- KV-Store value definitions
- `aether/cli/src/main/java/org/pragmatica/aether/cli/AetherCli.java` -- current CLI commands
