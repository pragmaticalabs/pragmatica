# TTM Integration Guide

TTM (Tiny Time Mixers) provides predictive scaling for Aether clusters using ONNX-based machine learning inference.

## Architecture

### Two-Tier Control System

TTM operates as Layer 2 in Aether's control hierarchy:

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

**Key principle**: The cluster always survives with only Layer 1 (Decision Tree). TTM enhances, but doesn't replace, the reactive controller.

## Components

### Core Components

| Component | Location | Purpose |
|-----------|----------|---------|
| `TTMPredictor` | `aether-ttm/.../ttm/` | ONNX Runtime wrapper for model inference |
| `TTMManager` | `node/.../ttm/TTMManager.java` | Leader-aware lifecycle management |
| `ForecastAnalyzer` | `aether-ttm/.../ttm/` | Converts predictions to ScalingRecommendation |
| `AdaptiveDecisionTree` | `node/.../ttm/AdaptiveDecisionTree.java` | Wraps DecisionTreeController, adjusts thresholds |
| `MinuteAggregator` | `node/.../metrics/MinuteAggregator.java` | Aggregates metrics for TTM input |

### Data Flow

```
MetricsCollector (1s)
       │
       ▼
MinuteAggregator (accumulates 60 samples)
       │
       ▼
TTMPredictor.predict(aggregatedMetrics)
       │
       ▼
ForecastAnalyzer.analyze(prediction)
       │
       ▼
ScalingRecommendation
       │
       ▼
AdaptiveDecisionTree.adjustThresholds()
       │
       ▼
DecisionTreeController (uses adjusted thresholds)
```

## Configuration

### aether.toml

```toml
[ttm]
enabled = true
model_path = "models/ttm-aether.onnx"
input_window_minutes = 60          # 1-120
evaluation_interval_ms = 60000     # 10000-300000
confidence_threshold = 0.7         # 0.0-1.0
prediction_horizon = 1             # 1-10
```

### Configuration Parameters

| Parameter | Range | Default | Description |
|-----------|-------|---------|-------------|
| `enabled` | boolean | `false` | Enable/disable TTM |
| `model_path` | string | - | Path to ONNX model file |
| `input_window_minutes` | 1-120 | 60 | Historical data window for predictions |
| `evaluation_interval_ms` | 10000-300000 | 60000 | How often to run predictions |
| `confidence_threshold` | 0.0-1.0 | 0.7 | Minimum confidence to act on predictions |
| `prediction_horizon` | 1-10 | 1 | How many intervals ahead to predict |

## TTMConfig Record

```java
public record TTMConfig(
    boolean enabled,
    String modelPath,
    int inputWindowMinutes,
    long evaluationIntervalMs,
    double confidenceThreshold,
    int predictionHorizon
) {
    public static Result<TTMConfig> ttmConfig(TomlDocument doc) {
        // Parse from TOML
    }
}
```

## Error Types

TTM uses a sealed error interface:

```java
public sealed interface TTMError extends Cause {
    record ModelLoadFailed(String path, Throwable cause) implements TTMError;
    record InferenceFailed(String reason, Option<Throwable> cause) implements TTMError;
    record InsufficientData(int required, int actual) implements TTMError;
    record Disabled() implements TTMError;
}
```

## Leader-Only Execution

TTM runs only on the leader node to avoid conflicting scaling decisions:

```java
public class TTMManager {
    public Promise<Unit> start() {
        // Register for leader change notifications
        // Start evaluation loop only when this node is leader
    }

    private void onLeaderChange(boolean isLeader) {
        if (isLeader) {
            startEvaluationLoop();
        } else {
            stopEvaluationLoop();
        }
    }
}
```

## Adaptive Threshold Adjustment

When TTM predicts load increase, it adjusts the Decision Tree thresholds:

```java
public class AdaptiveDecisionTree implements Controller {
    private final DecisionTreeController delegate;

    public void onForecast(ScalingRecommendation recommendation) {
        if (recommendation.confidence() > threshold) {
            // Lower scale-up threshold to be more proactive
            delegate.setScaleUpCpuThreshold(
                delegate.getScaleUpCpuThreshold() * 0.8
            );
        }
    }
}
```

## Model Requirements

### Input Format

The TTM model expects a tensor of shape `[1, input_window_minutes, num_features]`:

| Feature | Description |
|---------|-------------|
| `cpu_usage` | Cluster-wide CPU utilization (0.0-1.0) |
| `request_rate` | Requests per second |
| `latency_p95` | 95th percentile latency (ms) |
| `active_instances` | Total active slice instances |

### Output Format

The model outputs:
- `prediction`: Predicted values for next N intervals
- `confidence`: Confidence score (0.0-1.0)

## Graceful Degradation

If TTM fails:
1. Error is logged
2. `TTMError` recorded in metrics
3. Decision Tree continues with default thresholds
4. No scaling disruption

```java
ttmPredictor.predict(input)
    .onFailure(error -> {
        logger.warn("TTM prediction failed: {}", error.message());
        metrics.recordTTMError(error);
        // Decision tree continues normally
    })
    .onSuccess(this::processRecommendation);
```

## Development Status

**Completed:**
- ONNX Runtime integration
- TTMPredictor implementation
- MinuteAggregator
- AdaptiveDecisionTree wrapper
- Configuration system

**Planned:**
- Model training pipeline
- Model versioning and hot-reload
- A/B testing of model versions
- Custom feature engineering

## Key Files

```
aether-ttm/src/main/java/org/pragmatica/aether/ttm/
├── TTMPredictor.java        # ONNX inference wrapper
├── ForecastAnalyzer.java    # Prediction → recommendation
├── ScalingRecommendation.java
└── TTMError.java

node/src/main/java/org/pragmatica/aether/ttm/
├── TTMManager.java          # Leader-aware lifecycle
└── AdaptiveDecisionTree.java

aether-config/src/main/java/org/pragmatica/aether/config/
└── TTMConfig.java

node/src/main/java/org/pragmatica/aether/metrics/
└── MinuteAggregator.java
```

## See Also

- [Metrics and Control](metrics-control.md) - Overall control architecture
- [Architecture](architecture.md) - System design
- [Scaling](../operators/scaling.md) - Scaling behavior from operator perspective
