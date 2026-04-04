# Canary & Blue-Green Deployment Strategies

## Design Specification

**Version:** 0.1 (Draft)
**Target Release:** 0.22.0+
**Priority:** Medium (#4 in development priorities)
**Prerequisite:** Existing rolling update infrastructure (v0.18.0+)

---

## 1. Overview

### 1.1 Goals

Extend Aether's deployment infrastructure with three advanced deployment strategies that build on the existing rolling update system:

1. **Canary Deployment** -- Route a small percentage of traffic to a new version, automatically monitor health, and progressively increase traffic or roll back based on configurable thresholds.
2. **Blue-Green Deployment** -- Run two complete deployment environments simultaneously with instant atomic switchover and instant rollback.
3. **A/B Testing** -- Split traffic by deterministic criteria (header, cookie, user ID hash) with per-variant metrics collection.

### 1.2 Design Principles

- **Reuse, don't duplicate.** Canary and blue-green build on the existing `RollingUpdateManager`, `VersionRouting`, KV-Store types, and `InvocationMetricsCollector`.
- **JBCT compliance.** Sealed interfaces for state, records for data, `Promise<T>` for async, no nested error channels.
- **Leader-driven orchestration.** All deployment state changes go through consensus via the leader node (CDM pattern).
- **Observable.** All deployment state is in the KV-Store and visible through the Management API and CLI.
- **Fail-safe.** On leader failover, the new leader restores deployment state from KV-Store and resumes orchestration.

### 1.3 What Exists Today

| Component | Location | Relevance |
|-----------|----------|-----------|
| `RollingUpdateManager` | `aether-invoke` | Two-stage deploy/route model; traffic splitting via `VersionRouting` |
| `RollingUpdateState` | `aether-invoke` | State machine: PENDING -> DEPLOYING -> DEPLOYED -> ROUTING -> COMPLETING -> COMPLETED |
| `VersionRouting` | `aether-invoke` | Ratio-based traffic split (e.g., 1:3 = 25% new) |
| `HealthThresholds` | `aether-invoke` | Error rate + latency thresholds for auto-progression |
| `CleanupPolicy` | `aether-invoke` | IMMEDIATE / GRACE_PERIOD / MANUAL |
| `VersionRoutingKey/Value` | `aether/slice` | KV-Store types for routing state |
| `RollingUpdateKey/Value` | `aether/slice` | KV-Store types for update state |
| `PreviousVersionKey/Value` | `aether/slice` | Rollback version tracking |
| `InvocationMetricsCollector` | `aether-metrics` | Per-method metrics with percentiles, error rates |
| `MethodMetrics` | `aether-metrics` | Lock-free counters, histogram buckets, latency samples |
| `RollingUpdateRoutes` | `aether/node` | REST API for rolling updates |
| `ClusterDeploymentManager` | `aether-deployment` | Leader-driven deployment orchestration |
| `AetherCli.UpdateCommand` | `aether/cli` | CLI for rolling update operations |

---

## 2. Architecture

### 2.1 Component Overview

```
                    +-------------------+
                    |  Management API   |  (REST endpoints)
                    |  + CLI            |
                    +--------+----------+
                             |
                    +--------v----------+
                    | DeploymentStrategy|  (sealed interface)
                    |   Manager         |
                    +--------+----------+
                             |
              +--------------+--------------+
              |              |              |
     +--------v---+  +------v------+  +----v--------+
     |  Canary    |  | Blue-Green  |  | A/B Testing |
     |  Manager   |  | Manager     |  | Manager     |
     +--------+---+  +------+------+  +----+--------+
              |              |              |
     +--------v--------------v--------------v--------+
     |           Shared Infrastructure               |
     |  - VersionRouting (traffic split)             |
     |  - HealthThresholds (auto-progression)        |
     |  - InvocationMetricsCollector (monitoring)    |
     |  - KV-Store (state persistence)               |
     |  - CDM (deployment orchestration)             |
     +-----------------------------------------------+
```

### 2.2 Strategy Hierarchy

All deployment strategies share a common `DeploymentStrategy` sealed interface:

```java
/// Sealed interface for all deployment strategies.
/// Each strategy manages a complete lifecycle from initiation to completion.
public sealed interface DeploymentStrategy {
    String strategyId();
    ArtifactBase artifactBase();
    Version oldVersion();
    Version newVersion();
    boolean isTerminal();
    boolean isActive();
    long createdAt();
    long updatedAt();
}
```

The existing `RollingUpdate` record remains unchanged. Three new strategy records implement `DeploymentStrategy`:

1. `CanaryDeployment`
2. `BlueGreenDeployment`
3. `ABTestDeployment`

### 2.3 Module Placement

| New Type | Module | Rationale |
|----------|--------|-----------|
| `CanaryDeployment`, `CanaryState`, `CanaryStage` | `aether-invoke` | Alongside `RollingUpdate` |
| `BlueGreenDeployment`, `BlueGreenState` | `aether-invoke` | Alongside `RollingUpdate` |
| `ABTestDeployment`, `SplitRule`, `ABTestState` | `aether-invoke` | Alongside `RollingUpdate` |
| `CanaryDeploymentKey/Value` | `aether/slice` (AetherKey/AetherValue) | KV-Store types |
| `BlueGreenDeploymentKey/Value` | `aether/slice` (AetherKey/AetherValue) | KV-Store types |
| `ABTestKey/Value` | `aether/slice` (AetherKey/AetherValue) | KV-Store types |
| `CanaryRoutes`, `BlueGreenRoutes`, `ABTestRoutes` | `aether/node` | REST API |
| CLI commands | `aether/cli` | User interface |

---

## 3. Canary Deployment

### 3.1 Concept

A canary deployment routes a small, configurable percentage of traffic to a new version while continuously monitoring health metrics. If the canary version is healthy, traffic is progressively increased through a series of stages until it reaches 100%. If the canary exceeds error or latency thresholds, traffic is automatically rolled back to the old version.

### 3.2 State Machine

```
PENDING --> DEPLOYING --> CANARY_ACTIVE --> PROMOTING --> PROMOTED
                |              |               |
                |              v               v
                |         AUTO_ROLLBACK   AUTO_ROLLBACK
                |              |               |
                v              v               v
              FAILED      ROLLED_BACK     ROLLED_BACK
```

```java
public enum CanaryState {
    /// Canary requested, not yet started
    PENDING,
    /// New version instances being deployed (0% traffic)
    DEPLOYING,
    /// Canary is active -- traffic is being split according to current stage
    CANARY_ACTIVE,
    /// Promoting to next stage (adjusting traffic weights)
    PROMOTING,
    /// Canary promoted to 100% -- old version being cleaned up
    PROMOTED,
    /// Auto-rollback triggered by health threshold breach
    AUTO_ROLLBACK,
    /// Successfully rolled back to old version
    ROLLED_BACK,
    /// Canary failed (unrecoverable error)
    FAILED;

    public Set<CanaryState> validTransitions() {
        return switch (this) {
            case PENDING -> Set.of(DEPLOYING, FAILED);
            case DEPLOYING -> Set.of(CANARY_ACTIVE, AUTO_ROLLBACK, FAILED);
            case CANARY_ACTIVE -> Set.of(PROMOTING, AUTO_ROLLBACK, ROLLED_BACK, FAILED);
            case PROMOTING -> Set.of(CANARY_ACTIVE, PROMOTED, AUTO_ROLLBACK, FAILED);
            case PROMOTED -> Set.of();  // Terminal
            case AUTO_ROLLBACK -> Set.of(ROLLED_BACK, FAILED);
            case ROLLED_BACK, FAILED -> Set.of();  // Terminal
        };
    }

    public boolean isTerminal() {
        return this == PROMOTED || this == ROLLED_BACK || this == FAILED;
    }
}
```

### 3.3 Canary Stages

Stages define the traffic progression schedule. Each stage specifies a traffic weight for the canary version and a minimum observation period.

```java
/// A single stage in the canary progression schedule.
///
/// @param trafficPercent percentage of traffic routed to canary (1-100)
/// @param observationPeriod minimum time to observe at this stage before progressing
public record CanaryStage(int trafficPercent, TimeSpan observationPeriod) {
    public static Result<CanaryStage> canaryStage(int trafficPercent, TimeSpan observationPeriod) {
        if (trafficPercent < 1 || trafficPercent > 100) {
            return INVALID_TRAFFIC_PERCENT.result();
        }
        return Result.success(new CanaryStage(trafficPercent, observationPeriod));
    }
}
```

**Default stages:**

| Stage | Traffic % | Observation Period |
|-------|-----------|-------------------|
| 1 | 1% | 5 minutes |
| 2 | 5% | 5 minutes |
| 3 | 25% | 10 minutes |
| 4 | 50% | 10 minutes |
| 5 | 100% | 0 (promotion) |

### 3.4 Canary Deployment Record

```java
/// Represents a canary deployment operation.
///
/// @param canaryId unique identifier (KSUID)
/// @param artifactBase artifact being updated (version-agnostic)
/// @param oldVersion current production version
/// @param newVersion canary version being tested
/// @param state current canary state
/// @param stages ordered list of progression stages
/// @param currentStageIndex index into stages list (0-based)
/// @param stageEnteredAt timestamp when current stage was entered
/// @param thresholds health thresholds for auto-rollback
/// @param analysisConfig comparison analysis configuration
/// @param newInstances target instance count for canary version
/// @param cleanupPolicy cleanup policy for old version after promotion
/// @param createdAt creation timestamp
/// @param updatedAt last state change timestamp
public record CanaryDeployment(String canaryId,
                               ArtifactBase artifactBase,
                               Version oldVersion,
                               Version newVersion,
                               CanaryState state,
                               List<CanaryStage> stages,
                               int currentStageIndex,
                               long stageEnteredAt,
                               HealthThresholds thresholds,
                               CanaryAnalysisConfig analysisConfig,
                               int newInstances,
                               CleanupPolicy cleanupPolicy,
                               long createdAt,
                               long updatedAt) implements DeploymentStrategy {

    public CanaryStage currentStage() {
        return stages.get(currentStageIndex);
    }

    public boolean isAtFinalStage() {
        return currentStageIndex >= stages.size() - 1;
    }

    public boolean observationPeriodElapsed() {
        return System.currentTimeMillis() - stageEnteredAt >=
               currentStage().observationPeriod().asMillis();
    }
}
```

### 3.5 Canary Analysis Configuration

```java
/// Configuration for canary health comparison analysis.
///
/// @param comparisonMode how to evaluate canary health
/// @param errorRateThreshold maximum absolute error rate for canary
/// @param relativeErrorIncrease maximum relative error rate increase vs baseline (e.g., 1.5 = 50% more)
/// @param latencyThreshold maximum absolute p99 latency (ms) for canary
/// @param relativeLatencyIncrease maximum relative p99 latency increase vs baseline
/// @param minimumRequests minimum requests before analysis is considered valid
public record CanaryAnalysisConfig(ComparisonMode comparisonMode,
                                   double errorRateThreshold,
                                   double relativeErrorIncrease,
                                   long latencyThreshold,
                                   double relativeLatencyIncrease,
                                   long minimumRequests) {
    public static final CanaryAnalysisConfig DEFAULT = new CanaryAnalysisConfig(
        ComparisonMode.RELATIVE_AND_ABSOLUTE,
        0.01,   // 1% absolute error rate max
        1.5,    // 50% relative error increase max
        500,    // 500ms absolute p99 latency max
        1.5,    // 50% relative latency increase max
        100     // Minimum 100 requests before analysis
    );

    public enum ComparisonMode {
        /// Canary must meet absolute thresholds only
        ABSOLUTE_ONLY,
        /// Canary compared relative to baseline only
        RELATIVE_ONLY,
        /// Both absolute and relative must pass
        RELATIVE_AND_ABSOLUTE
    }
}
```

### 3.6 Canary Health Evaluation

The canary manager periodically (every `evaluationInterval`, default 30 seconds) compares canary metrics against the baseline:

```
For each evaluation cycle:
  1. Collect metrics snapshot for canary version (new)
  2. Collect metrics snapshot for baseline version (old)
  3. If canary requests < minimumRequests, skip (insufficient data)
  4. Compare:
     a. ABSOLUTE: canary errorRate <= errorRateThreshold AND canary p99 <= latencyThreshold
     b. RELATIVE: canary errorRate <= baseline errorRate * relativeErrorIncrease
                  AND canary p99 <= baseline p99 * relativeLatencyIncrease
  5. If FAIL: trigger AUTO_ROLLBACK
  6. If PASS and observationPeriodElapsed: advance to next stage
  7. If PASS and not elapsed: continue observing
```

### 3.7 Canary Manager Interface

```java
/// Manages canary deployment operations across the cluster.
/// Only active on the leader node (same pattern as RollingUpdateManager).
public interface CanaryDeploymentManager {

    /// Starts a new canary deployment.
    Promise<CanaryDeployment> startCanary(ArtifactBase artifactBase,
                                          Version newVersion,
                                          int instances,
                                          List<CanaryStage> stages,
                                          HealthThresholds thresholds,
                                          CanaryAnalysisConfig analysisConfig,
                                          CleanupPolicy cleanupPolicy);

    /// Manually promotes the canary to the next stage (bypasses observation period).
    Promise<CanaryDeployment> promote(String canaryId);

    /// Manually rolls back the canary to the old version.
    Promise<CanaryDeployment> rollback(String canaryId);

    /// Manually promotes the canary directly to 100% (skips remaining stages).
    Promise<CanaryDeployment> promoteToFull(String canaryId);

    /// Gets the current state of a canary deployment.
    Option<CanaryDeployment> getCanary(String canaryId);

    /// Gets the active canary for an artifact (if any).
    Option<CanaryDeployment> getActiveCanary(ArtifactBase artifactBase);

    /// Lists all active canary deployments.
    List<CanaryDeployment> activeCanaries();

    /// Lists all canary deployments (including terminal).
    List<CanaryDeployment> allCanaries();

    /// Gets health comparison metrics for a canary.
    Promise<CanaryHealthComparison> getHealthComparison(String canaryId);

    /// Handle leader change (restore state from KV-Store).
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    /// Handle deployment failure for auto-rollback.
    @MessageReceiver
    void onDeploymentFailed(DeploymentEvent.DeploymentFailed event);
}
```

### 3.8 Automatic Progression Loop

When the leader activates, it starts a scheduled evaluation loop:

```
Every evaluationInterval (default 30s):
  For each active canary in CANARY_ACTIVE state:
    1. Collect CanaryHealthComparison
    2. If unhealthy: transition to AUTO_ROLLBACK, revert routing, remove canary instances
    3. If healthy AND observationPeriodElapsed:
       a. If at final stage: transition to PROMOTED, cleanup old version
       b. Else: transition to PROMOTING, advance stage index, update routing weights,
          transition back to CANARY_ACTIVE
    4. If healthy AND not elapsed: no action (continue observing)
```

This loop is leader-only. On leader failover, the new leader:
1. Restores all canary state from KV-Store
2. Recalculates `stageEnteredAt` relative to `updatedAt` timestamp
3. Resumes the evaluation loop

---

## 4. Blue-Green Deployment

### 4.1 Concept

A blue-green deployment maintains two complete, independent deployment environments ("blue" and "green"). At any time, one environment serves all production traffic while the other is idle or being prepared. Switchover is atomic -- all traffic moves from one environment to the other in a single operation.

In Aether, "blue" and "green" are represented by two full sets of slice instances at different versions. The CDM deploys both sets; the `VersionRoutingKey` controls which set receives traffic.

### 4.2 State Machine

```
PENDING --> DEPLOYING_GREEN --> GREEN_READY --> SWITCHED --> DRAINING --> COMPLETED
                  |                 |             |             |
                  v                 v             v             v
                FAILED           FAILED    SWITCH_BACK       FAILED
                                                |
                                                v
                                           ROLLED_BACK
```

```java
public enum BlueGreenState {
    /// Deployment requested
    PENDING,
    /// Deploying the inactive (green) environment
    DEPLOYING_GREEN,
    /// Green environment deployed and healthy, ready for switchover
    GREEN_READY,
    /// Traffic switched to green (green is now active)
    SWITCHED,
    /// Draining in-flight requests from blue (old active)
    DRAINING,
    /// Switch complete, blue cleaned up
    COMPLETED,
    /// Switching back to blue (rollback)
    SWITCH_BACK,
    /// Rolled back to blue
    ROLLED_BACK,
    /// Deployment failed
    FAILED;

    public Set<BlueGreenState> validTransitions() {
        return switch (this) {
            case PENDING -> Set.of(DEPLOYING_GREEN, FAILED);
            case DEPLOYING_GREEN -> Set.of(GREEN_READY, FAILED);
            case GREEN_READY -> Set.of(SWITCHED, ROLLED_BACK, FAILED);
            case SWITCHED -> Set.of(DRAINING, SWITCH_BACK, FAILED);
            case DRAINING -> Set.of(COMPLETED, SWITCH_BACK, FAILED);
            case SWITCH_BACK -> Set.of(ROLLED_BACK, FAILED);
            case COMPLETED, ROLLED_BACK, FAILED -> Set.of();  // Terminal
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == ROLLED_BACK || this == FAILED;
    }
}
```

### 4.3 Blue-Green Deployment Record

```java
/// Represents a blue-green deployment operation.
///
/// @param deploymentId unique identifier (KSUID)
/// @param artifactBase artifact being updated
/// @param blueVersion the currently active version (blue)
/// @param greenVersion the new version being prepared (green)
/// @param state current deployment state
/// @param activeEnvironment which environment is currently serving traffic ("BLUE" or "GREEN")
/// @param blueInstances instance count for blue environment
/// @param greenInstances instance count for green environment
/// @param drainTimeout maximum time to wait for in-flight requests to complete
/// @param healthThresholds health check for green readiness
/// @param cleanupPolicy cleanup policy for inactive environment
/// @param createdAt creation timestamp
/// @param updatedAt last state change timestamp
public record BlueGreenDeployment(String deploymentId,
                                  ArtifactBase artifactBase,
                                  Version blueVersion,
                                  Version greenVersion,
                                  BlueGreenState state,
                                  String activeEnvironment,
                                  int blueInstances,
                                  int greenInstances,
                                  TimeSpan drainTimeout,
                                  HealthThresholds healthThresholds,
                                  CleanupPolicy cleanupPolicy,
                                  long createdAt,
                                  long updatedAt) implements DeploymentStrategy {

    public static final TimeSpan DEFAULT_DRAIN_TIMEOUT = TimeSpan.timeSpan(30).seconds();
}
```

### 4.4 Blue-Green Manager Interface

```java
public interface BlueGreenDeploymentManager {

    /// Deploys the green environment alongside the existing blue.
    /// Green receives 0% traffic until explicitly switched.
    Promise<BlueGreenDeployment> deployGreen(ArtifactBase artifactBase,
                                              Version greenVersion,
                                              int instances,
                                              HealthThresholds healthThresholds,
                                              TimeSpan drainTimeout,
                                              CleanupPolicy cleanupPolicy);

    /// Atomically switches all traffic from blue to green.
    /// Sets VersionRouting to 1:0 (all new) in a single KV-Store write.
    Promise<BlueGreenDeployment> switchOver(String deploymentId);

    /// Atomically switches all traffic back from green to blue (instant rollback).
    /// Sets VersionRouting to 0:1 (all old) in a single KV-Store write.
    Promise<BlueGreenDeployment> switchBack(String deploymentId);

    /// Completes the deployment -- cleans up the inactive environment
    /// after the drain period has elapsed.
    Promise<BlueGreenDeployment> complete(String deploymentId);

    /// Gets current state of a blue-green deployment.
    Option<BlueGreenDeployment> getDeployment(String deploymentId);

    /// Gets active blue-green deployment for an artifact.
    Option<BlueGreenDeployment> getActiveDeployment(ArtifactBase artifactBase);

    /// Lists all active blue-green deployments.
    List<BlueGreenDeployment> activeDeployments();

    /// Handle leader change.
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);
}
```

### 4.5 Switchover Mechanics

The switchover operation is a single atomic KV-Store write:

```
switchOver(deploymentId):
  1. Verify state is GREEN_READY
  2. Write VersionRoutingValue(blueVersion, greenVersion, newWeight=1, oldWeight=0)
     to VersionRoutingKey(artifactBase) via consensus
  3. Transition state to SWITCHED
  4. All nodes immediately route 100% traffic to green

switchBack(deploymentId):
  1. Verify state is SWITCHED or DRAINING
  2. Write VersionRoutingValue(blueVersion, greenVersion, newWeight=0, oldWeight=1)
     to VersionRoutingKey(artifactBase) via consensus
  3. Transition state to SWITCH_BACK -> ROLLED_BACK
  4. All nodes immediately route 100% traffic back to blue
```

Because `VersionRoutingValue` is already used by the existing rolling update system and is applied atomically via Rabia consensus, switchover latency is bounded by a single consensus round (typically < 100ms in a healthy cluster).

### 4.6 Drain Period

After switchover, in-flight requests on the old (blue) environment must complete:

1. State transitions from `SWITCHED` to `DRAINING`
2. Timer starts for `drainTimeout` (default 30 seconds)
3. During drain, blue instances remain running but receive no new traffic
4. After drain timeout, blue instances are eligible for cleanup
5. `complete()` removes blue instances and routing key

If `switchBack()` is called during drain, traffic immediately returns to blue -- no cleanup needed.

---

## 5. A/B Testing

### 5.1 Concept

A/B testing splits traffic between two or more versions based on deterministic criteria rather than random distribution. This enables controlled experiments where specific user segments consistently see the same version.

### 5.2 Split Rules

```java
/// Rule for deterministic traffic splitting.
public sealed interface SplitRule {
    /// Determines which variant receives a request based on request context.
    String resolveVariant(RequestContext context);

    /// Split by HTTP header value hash.
    /// Example: split by X-User-Id header -- users consistently see the same variant.
    record HeaderHashSplit(String headerName, int variantCount) implements SplitRule {
        @Override
        public String resolveVariant(RequestContext context) {
            var value = context.header(headerName).or("");
            var hash = Math.abs(value.hashCode()) % variantCount;
            return "variant-" + hash;
        }
    }

    /// Split by cookie value hash.
    record CookieHashSplit(String cookieName, int variantCount) implements SplitRule {
        @Override
        public String resolveVariant(RequestContext context) {
            var value = context.cookie(cookieName).or("");
            var hash = Math.abs(value.hashCode()) % variantCount;
            return "variant-" + hash;
        }
    }

    /// Split by explicit header value matching.
    /// Example: X-Feature-Group: beta -> variant-1, everything else -> variant-0
    record HeaderMatchSplit(String headerName,
                            Map<String, String> valueToVariant,
                            String defaultVariant) implements SplitRule {
        @Override
        public String resolveVariant(RequestContext context) {
            var value = context.header(headerName).or("");
            return valueToVariant.getOrDefault(value, defaultVariant);
        }
    }

    /// Split by percentage with sticky assignment via request ID hash.
    record PercentageSplit(Map<String, Integer> variantWeights) implements SplitRule {
        @Override
        public String resolveVariant(RequestContext context) {
            var hash = Math.abs(context.requestId().hashCode());
            var total = variantWeights.values().stream().mapToInt(Integer::intValue).sum();
            var target = hash % total;
            var cumulative = 0;
            for (var entry : variantWeights.entrySet()) {
                cumulative += entry.getValue();
                if (target < cumulative) {
                    return entry.getKey();
                }
            }
            return variantWeights.keySet().iterator().next();
        }
    }
}
```

### 5.3 A/B Test State

```java
public enum ABTestState {
    PENDING,
    DEPLOYING_VARIANTS,
    ACTIVE,
    CONCLUDING,
    COMPLETED,
    FAILED;

    public Set<ABTestState> validTransitions() {
        return switch (this) {
            case PENDING -> Set.of(DEPLOYING_VARIANTS, FAILED);
            case DEPLOYING_VARIANTS -> Set.of(ACTIVE, FAILED);
            case ACTIVE -> Set.of(CONCLUDING, FAILED);
            case CONCLUDING -> Set.of(COMPLETED, FAILED);
            case COMPLETED, FAILED -> Set.of();
        };
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
```

### 5.4 A/B Test Record

```java
/// Represents an A/B test deployment.
///
/// @param testId unique identifier
/// @param artifactBase artifact under test
/// @param variants map of variant name -> version
/// @param splitRule how to split traffic
/// @param state current test state
/// @param instancesPerVariant instances per variant
/// @param startedAt when the test started collecting data
/// @param createdAt creation timestamp
/// @param updatedAt last state change
public record ABTestDeployment(String testId,
                               ArtifactBase artifactBase,
                               Map<String, Version> variants,
                               SplitRule splitRule,
                               ABTestState state,
                               int instancesPerVariant,
                               long startedAt,
                               long createdAt,
                               long updatedAt) implements DeploymentStrategy {}
```

### 5.5 A/B Test Manager Interface

```java
public interface ABTestManager {

    /// Creates an A/B test with the specified variants and split rule.
    Promise<ABTestDeployment> createTest(ArtifactBase artifactBase,
                                          Map<String, Version> variants,
                                          SplitRule splitRule,
                                          int instancesPerVariant);

    /// Concludes the test -- selects a winning variant and promotes it.
    Promise<ABTestDeployment> conclude(String testId, String winningVariant);

    /// Gets per-variant metrics for comparison.
    Promise<ABTestMetrics> getMetrics(String testId);

    /// Gets current test state.
    Option<ABTestDeployment> getTest(String testId);

    /// Lists active tests.
    List<ABTestDeployment> activeTests();

    /// Handle leader change.
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);
}
```

### 5.6 A/B Test Metrics

```java
/// Per-variant metrics for an A/B test.
public record ABTestMetrics(String testId,
                            Map<String, VariantMetrics> variants,
                            long collectedAt) {}

/// Metrics for a single variant.
public record VariantMetrics(String variantName,
                             Version version,
                             long requestCount,
                             long errorCount,
                             double errorRate,
                             long p50LatencyMs,
                             long p95LatencyMs,
                             long p99LatencyMs,
                             long avgLatencyMs) {}
```

---

## 6. KV-Store Data Model

### 6.1 New Keys

```java
// In AetherKey sealed interface:

/// Canary deployment key: canary-deployment/{canaryId}
record CanaryDeploymentKey(String canaryId) implements AetherKey { ... }

/// Blue-green deployment key: blue-green/{deploymentId}
record BlueGreenDeploymentKey(String deploymentId) implements AetherKey { ... }

/// A/B test key: ab-test/{testId}
record ABTestKey(String testId) implements AetherKey { ... }

/// A/B test split rule key: ab-test-rule/{testId}
/// Stored separately because split rules can be large (header match maps).
record ABTestRuleKey(String testId) implements AetherKey { ... }
```

### 6.2 New Values

```java
// In AetherValue sealed interface:

/// Canary deployment state in consensus.
record CanaryDeploymentValue(String canaryId,
                             ArtifactBase artifactBase,
                             Version oldVersion,
                             Version newVersion,
                             String state,
                             String stagesJson,  // JSON array of {percent, durationMs}
                             int currentStageIndex,
                             long stageEnteredAt,
                             double maxErrorRate,
                             long maxLatencyMs,
                             boolean requireManualApproval,
                             String comparisonMode,
                             double relativeErrorIncrease,
                             double relativeLatencyIncrease,
                             long minimumRequests,
                             int newInstances,
                             String cleanupPolicy,
                             long createdAt,
                             long updatedAt) implements AetherValue { ... }

/// Blue-green deployment state in consensus.
record BlueGreenDeploymentValue(String deploymentId,
                                ArtifactBase artifactBase,
                                Version blueVersion,
                                Version greenVersion,
                                String state,
                                String activeEnvironment,
                                int blueInstances,
                                int greenInstances,
                                long drainTimeoutMs,
                                double maxErrorRate,
                                long maxLatencyMs,
                                String cleanupPolicy,
                                long createdAt,
                                long updatedAt) implements AetherValue { ... }

/// A/B test deployment state in consensus.
record ABTestValue(String testId,
                   ArtifactBase artifactBase,
                   String variantsJson,  // JSON map of variant -> version
                   String splitRuleJson, // JSON serialization of SplitRule
                   String state,
                   int instancesPerVariant,
                   long startedAt,
                   long createdAt,
                   long updatedAt) implements AetherValue { ... }
```

### 6.3 Reused Keys

| Key | Used By |
|-----|---------|
| `VersionRoutingKey` | Canary (traffic split), Blue-Green (switchover) |
| `PreviousVersionKey` | Canary (rollback), Blue-Green (rollback) |
| `SliceTargetKey` | All strategies (deployment targets) |

---

## 7. Management API

### 7.1 Canary Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/canary/start` | Start a canary deployment |
| `GET` | `/api/canaries` | List all active canary deployments |
| `GET` | `/api/canary/{canaryId}` | Get canary deployment state |
| `GET` | `/api/canary/{canaryId}/health` | Get canary health comparison |
| `POST` | `/api/canary/{canaryId}/promote` | Manually promote to next stage |
| `POST` | `/api/canary/{canaryId}/promote-full` | Promote directly to 100% |
| `POST` | `/api/canary/{canaryId}/rollback` | Manually rollback canary |

**Start request body:**

```json
{
  "artifactBase": "com.example:my-service",
  "version": "2.0.0",
  "instances": 3,
  "stages": [
    {"trafficPercent": 1, "observationMinutes": 5},
    {"trafficPercent": 5, "observationMinutes": 5},
    {"trafficPercent": 25, "observationMinutes": 10},
    {"trafficPercent": 50, "observationMinutes": 10},
    {"trafficPercent": 100, "observationMinutes": 0}
  ],
  "maxErrorRate": 0.01,
  "maxLatencyMs": 500,
  "comparisonMode": "RELATIVE_AND_ABSOLUTE",
  "relativeErrorIncrease": 1.5,
  "relativeLatencyIncrease": 1.5,
  "minimumRequests": 100,
  "cleanupPolicy": "GRACE_PERIOD"
}
```

**Health comparison response:**

```json
{
  "canaryId": "2dG3wKJ...",
  "currentStage": 2,
  "trafficPercent": 5,
  "baseline": {
    "version": "1.0.0",
    "requestCount": 9500,
    "errorRate": 0.002,
    "p99LatencyMs": 120
  },
  "canary": {
    "version": "2.0.0",
    "requestCount": 500,
    "errorRate": 0.004,
    "p99LatencyMs": 150
  },
  "verdict": "HEALTHY",
  "timeUntilNextStage": "3m 22s",
  "collectedAt": 1710700000000
}
```

### 7.2 Blue-Green Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/blue-green/deploy` | Deploy green environment |
| `GET` | `/api/blue-green` | List active blue-green deployments |
| `GET` | `/api/blue-green/{deploymentId}` | Get deployment state |
| `POST` | `/api/blue-green/{deploymentId}/switch` | Switch traffic to green |
| `POST` | `/api/blue-green/{deploymentId}/switch-back` | Switch traffic back to blue |
| `POST` | `/api/blue-green/{deploymentId}/complete` | Complete deployment (cleanup) |

**Deploy request body:**

```json
{
  "artifactBase": "com.example:my-service",
  "version": "2.0.0",
  "instances": 3,
  "maxErrorRate": 0.01,
  "maxLatencyMs": 500,
  "drainTimeoutSeconds": 30,
  "cleanupPolicy": "GRACE_PERIOD"
}
```

### 7.3 A/B Test Endpoints

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/ab-test/create` | Create an A/B test |
| `GET` | `/api/ab-tests` | List active A/B tests |
| `GET` | `/api/ab-test/{testId}` | Get test state |
| `GET` | `/api/ab-test/{testId}/metrics` | Get per-variant metrics |
| `POST` | `/api/ab-test/{testId}/conclude` | Conclude test with winner |

**Create request body:**

```json
{
  "artifactBase": "com.example:my-service",
  "variants": {
    "control": "1.0.0",
    "treatment": "2.0.0"
  },
  "splitRule": {
    "type": "HEADER_HASH",
    "headerName": "X-User-Id",
    "variantCount": 2
  },
  "instancesPerVariant": 3
}
```

---

## 8. CLI Commands

### 8.1 Canary Commands

```
aether canary start <artifactBase> <version> [--instances N] [--stages <json>] \
    [--max-error-rate R] [--max-latency-ms L] [--cleanup-policy P]
aether canary list
aether canary status <canaryId|current>
aether canary health <canaryId|current>
aether canary promote <canaryId|current>
aether canary promote-full <canaryId|current>
aether canary rollback <canaryId|current>
```

**Example:**

```bash
# Start canary with default stages
aether canary start com.example:my-service 2.0.0 --instances 3

# Check health comparison
aether canary health current

# Manually promote to next stage
aether canary promote current

# Emergency rollback
aether canary rollback current
```

### 8.2 Blue-Green Commands

```
aether blue-green deploy <artifactBase> <version> [--instances N] \
    [--drain-timeout-seconds S] [--cleanup-policy P]
aether blue-green list
aether blue-green status <deploymentId|current>
aether blue-green switch <deploymentId|current>
aether blue-green switch-back <deploymentId|current>
aether blue-green complete <deploymentId|current>
```

**Example:**

```bash
# Deploy green environment
aether blue-green deploy com.example:my-service 2.0.0 --instances 3

# Verify green is healthy
aether blue-green status current

# Atomic switchover
aether blue-green switch current

# Instant rollback if issues detected
aether blue-green switch-back current

# Finalize after drain period
aether blue-green complete current
```

### 8.3 A/B Test Commands

```
aether ab-test create <artifactBase> --variants <json> --split-rule <json> \
    [--instances-per-variant N]
aether ab-test list
aether ab-test status <testId>
aether ab-test metrics <testId>
aether ab-test conclude <testId> <winningVariant>
```

---

## 9. TOML Configuration

### 9.1 Blueprint-Level Strategy Configuration

Deployment strategy can be declared in the application blueprint TOML:

```toml
[application]
name = "my-app"
version = "2.0.0"

[deployment]
strategy = "canary"  # "rolling" (default) | "canary" | "blue-green"

[deployment.canary]
stages = [
    { traffic_percent = 1, observation_minutes = 5 },
    { traffic_percent = 5, observation_minutes = 5 },
    { traffic_percent = 25, observation_minutes = 10 },
    { traffic_percent = 50, observation_minutes = 10 },
    { traffic_percent = 100, observation_minutes = 0 },
]
max_error_rate = 0.01
max_latency_ms = 500
comparison_mode = "RELATIVE_AND_ABSOLUTE"
relative_error_increase = 1.5
relative_latency_increase = 1.5
minimum_requests = 100
cleanup_policy = "GRACE_PERIOD"

[deployment.blue_green]
drain_timeout_seconds = 30
cleanup_policy = "GRACE_PERIOD"
```

### 9.2 Node-Level Configuration

In `aether.toml`:

```toml
[deployment]
# Evaluation interval for canary health checks (default: 30s)
canary_evaluation_interval = "30s"

# Default stages if not specified in blueprint or API request
canary_default_stages = [
    { traffic_percent = 1, observation_minutes = 5 },
    { traffic_percent = 5, observation_minutes = 5 },
    { traffic_percent = 25, observation_minutes = 10 },
    { traffic_percent = 50, observation_minutes = 10 },
    { traffic_percent = 100, observation_minutes = 0 },
]
```

---

## 10. Traffic Routing Integration

### 10.1 How Traffic Split Works Today

The existing system uses `VersionRoutingKey/Value` in the KV-Store. When an invocation occurs:

1. `InvocationHandler` resolves the artifact's endpoints
2. If a `VersionRoutingValue` exists for the artifact, endpoints are filtered by version weight
3. Load balancing selects among the filtered endpoints

### 10.2 Canary Extension

Canary reuses `VersionRoutingValue` exactly as rolling updates do:

- Stage at 1% traffic: `VersionRoutingValue(oldVersion, newVersion, newWeight=1, oldWeight=99, ...)`
- Stage at 5%: `VersionRoutingValue(oldVersion, newVersion, newWeight=5, oldWeight=95, ...)`
- Stage at 25%: `VersionRoutingValue(oldVersion, newVersion, newWeight=25, oldWeight=75, ...)`

The ratio-based `VersionRouting` type already supports arbitrary weights. The canary manager converts percentage to weight ratio.

### 10.3 Blue-Green Extension

Blue-green uses `VersionRoutingValue` with binary weights:

- Blue active: `VersionRoutingValue(blueVersion, greenVersion, newWeight=0, oldWeight=1, ...)`
- Green active: `VersionRoutingValue(blueVersion, greenVersion, newWeight=1, oldWeight=0, ...)`

This is identical to the existing rolling update "all old" / "all new" states.

### 10.4 A/B Test Extension

A/B testing requires a new routing mechanism because traffic is split by request context, not random distribution.

**New concept: `ABTestRoutingKey/Value`**

```java
/// A/B test routing key: ab-test-routing/{groupId}:{artifactId}
record ABTestRoutingKey(ArtifactBase artifactBase) implements AetherKey { ... }

/// Maps variant names to versions for request-context-based routing.
record ABTestRoutingValue(String testId,
                          String splitRuleJson,
                          Map<String, Version> variantVersions,
                          long updatedAt) implements AetherValue { ... }
```

The `InvocationHandler` (or `AppHttpServer` for HTTP routes) checks for `ABTestRoutingValue` before `VersionRoutingValue`. If an A/B test is active, it:

1. Deserializes the `SplitRule` from the stored JSON
2. Calls `splitRule.resolveVariant(requestContext)` to determine the variant
3. Maps the variant name to a version
4. Selects endpoints for that version

This approach keeps A/B routing logic out of the core invocation path when no A/B test is active (the KV lookup returns empty).

---

## 11. Failure Scenarios

### 11.1 Leader Failover During Canary

| Scenario | Behavior |
|----------|----------|
| Failover during DEPLOYING | New leader restores state from KV, re-checks deployment status, resumes |
| Failover during CANARY_ACTIVE | New leader restores state, recalculates stage timing from `stageEnteredAt`, resumes evaluation loop |
| Failover during PROMOTING | New leader restores state; if routing update was committed to KV, stage is advanced; otherwise retries promotion |
| Failover during AUTO_ROLLBACK | New leader restores state; if routing was already reverted, completes rollback; otherwise retries revert |

**Key invariant:** All state transitions write to KV-Store **before** transitioning. The new leader always sees the last committed state.

### 11.2 Leader Failover During Blue-Green

| Scenario | Behavior |
|----------|----------|
| Failover during DEPLOYING_GREEN | New leader re-checks deployment status via KV-Store scan |
| Failover during switchover | Atomic: `VersionRoutingValue` either committed or not. New leader reads committed state |
| Failover during DRAINING | New leader restores drain timeout from `updatedAt` + `drainTimeoutMs`, continues drain timer |

### 11.3 Node Crash During Canary

If a node hosting canary instances crashes:
1. SWIM detects node departure (1-2 seconds)
2. CDM reconciliation re-deploys canary instances on remaining nodes
3. Canary evaluation continues with new instance set
4. If canary instance count drops below `minAvailable` and cannot be restored, auto-rollback triggers

### 11.4 Canary Analysis with Insufficient Data

If the canary receives too few requests during an observation period (below `minimumRequests`):
- The observation period is **not** extended automatically
- The evaluation reports `INSUFFICIENT_DATA` verdict
- No auto-progression or auto-rollback occurs
- Manual promotion or rollback is available via API/CLI
- After 3x the observation period with insufficient data, a warning is logged

### 11.5 Concurrent Deployment Strategies

Only one active deployment strategy per artifact is allowed:
- Starting a canary while a rolling update is active: **rejected**
- Starting a blue-green while a canary is active: **rejected**
- Starting an A/B test while a blue-green is active: **rejected**

The `getActiveUpdate()` / `getActiveCanary()` / `getActiveDeployment()` checks enforce mutual exclusion. A new method `getAnyActiveStrategy(ArtifactBase)` provides a unified check.

---

## 12. Implementation Phases

### Phase 1: Canary Deployment (Priority)

**Scope:**
- `CanaryDeployment` record and `CanaryState` enum
- `CanaryStage` and `CanaryAnalysisConfig` records
- `CanaryDeploymentManager` implementation (leader-driven, KV-backed)
- `CanaryDeploymentKey/Value` in AetherKey/AetherValue
- `KVStoreSerializer` extensions for new types
- `CanaryRoutes` REST API
- CLI `canary` command group
- Automatic evaluation loop with health comparison
- Auto-rollback on threshold breach
- Mutual exclusion with existing rolling updates
- Unit tests + integration tests

**Estimated effort:** 5-7 days

### Phase 2: Blue-Green Deployment

**Scope:**
- `BlueGreenDeployment` record and `BlueGreenState` enum
- `BlueGreenDeploymentManager` implementation
- `BlueGreenDeploymentKey/Value` in AetherKey/AetherValue
- `KVStoreSerializer` extensions
- `BlueGreenRoutes` REST API
- CLI `blue-green` command group
- Drain timer integration
- Mutual exclusion with canary and rolling updates
- Unit tests + integration tests

**Estimated effort:** 3-5 days

### Phase 3: A/B Testing

**Scope:**
- `ABTestDeployment` record and `ABTestState` enum
- `SplitRule` sealed interface and implementations
- `ABTestManager` implementation
- `ABTestKey/Value` and `ABTestRoutingKey/Value` in AetherKey/AetherValue
- `InvocationHandler` / `AppHttpServer` A/B routing integration
- `ABTestRoutes` REST API
- CLI `ab-test` command group
- Per-variant metrics collection and comparison
- Unit tests + integration tests

**Estimated effort:** 5-7 days

### Phase 4: TOML Configuration + Blueprint Integration

**Scope:**
- `[deployment]` section parsing in blueprint TOML
- `[deployment]` section in `aether.toml` node config
- Automatic strategy selection from blueprint
- Default stage configuration

**Estimated effort:** 2-3 days

### Phase 5: E2E Tests + Documentation

**Scope:**
- E2E tests for canary progression and auto-rollback
- E2E tests for blue-green switchover and switch-back
- E2E tests for A/B test traffic splitting
- Feature catalog update
- Management API documentation update
- CLI reference update
- Runbook for canary and blue-green operations

**Estimated effort:** 3-4 days

**Total estimated effort:** 18-26 days

---

## 13. Open Questions

1. **[TBD] Canary with worker pools.** Should canary routing apply to worker pool invocations or core-only? If both, the governor needs to understand `VersionRoutingValue`. Current assumption: core-only in Phase 1.

2. **[TBD] A/B test split rule storage format.** JSON was chosen for split rule serialization in KV-Store values. An alternative is the compile-time `@Codec` format. JSON is simpler for initial implementation and human-readable in backups.

3. **[TBD] Dashboard integration.** The dashboard should display canary progression, blue-green status, and A/B test metrics. This is deferred to the dashboard rework (separate spec exists at `aether/docs/specs/dashboard-ui-spec.md`).

4. **[TBD] Multi-artifact canary.** Should canary support updating multiple slices in a blueprint simultaneously? Current design is per-artifact. Blueprint-level canary (all slices advance together) could be added later.

5. **[TBD] Canary evaluation customization.** Should users be able to plug in custom health evaluation logic beyond error rate and latency? Current design uses built-in comparison modes. A callback/webhook approach could be added in a later phase.

---

## 14. Glossary

| Term | Definition |
|------|------------|
| **Canary** | A deployment strategy where a new version receives a small fraction of traffic for health validation before full rollout |
| **Blue-Green** | A deployment strategy with two complete environments and atomic switchover |
| **A/B Test** | A deployment strategy that splits traffic by deterministic criteria for experimental comparison |
| **Stage** | A step in the canary progression schedule defining traffic percentage and observation duration |
| **Observation Period** | Minimum time the canary must run at a given traffic level before auto-advancing |
| **Switchover** | Atomic transfer of all traffic from one environment to another (blue-green) |
| **Drain Period** | Time allowed for in-flight requests to complete on the old environment after switchover |
| **Split Rule** | A deterministic function mapping request context to a variant (A/B testing) |
| **Baseline** | The existing production version against which canary health is compared |
| **Variant** | One of the versions in an A/B test |

---

## References

### Internal References
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/update/RollingUpdateManager.java` -- Existing rolling update infrastructure
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/update/RollingUpdate.java` -- Rolling update record
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/update/RollingUpdateState.java` -- State machine pattern
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/update/VersionRouting.java` -- Traffic split model
- `aether/aether-invoke/src/main/java/org/pragmatica/aether/update/HealthThresholds.java` -- Health threshold model
- `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherKey.java` -- KV-Store key types
- `aether/slice/src/main/java/org/pragmatica/aether/slice/kvstore/AetherValue.java` -- KV-Store value types
- `aether/aether-metrics/src/main/java/org/pragmatica/aether/metrics/invocation/InvocationMetricsCollector.java` -- Metrics collection
- `aether/aether-metrics/src/main/java/org/pragmatica/aether/metrics/invocation/MethodMetrics.java` -- Per-method metrics
- `aether/node/src/main/java/org/pragmatica/aether/api/routes/RollingUpdateRoutes.java` -- REST API pattern
- `aether/aether-deployment/src/main/java/org/pragmatica/aether/deployment/cluster/ClusterDeploymentManager.java` -- CDM orchestration
- `aether/docs/internal/progress/development-priorities.md` -- Priority #4 (Canary & Blue-Green)

### Technical Documentation
- `aether/docs/architecture/02-deployment.md` -- Deployment architecture
- `aether/docs/architecture/03-invocation.md` -- Invocation and routing
- `aether/docs/reference/management-api.md` -- Management API reference
- `aether/docs/reference/cli.md` -- CLI reference
