# Predictive Auto-Scaling with TTM (Tiny Time Mixers)

## What is TTM?

TTM (Tiny Time Mixers) adds **predictive auto-scaling** to Aether. Instead of only reacting to current load (reactive controller, 1-second evaluations), TTM forecasts future resource needs based on recent metrics history (proactive controller, 60-second evaluations).

### Two-Tier Control System

| Tier | Controller | Interval | Strategy |
|------|-----------|----------|----------|
| 1 | DecisionTreeController | 1 second | Reactive: responds to current metrics |
| 2 | TTM Predictor | 60 seconds | Proactive: predicts future metrics |

When TTM is enabled, both tiers work together via `AdaptiveDecisionTree`:
- TTM adjusts controller thresholds based on predicted load trends
- TTM triggers preemptive scale-up/down before load changes arrive
- The reactive controller handles moment-to-moment adjustments

When TTM is disabled or unavailable, the reactive controller handles all scaling with no degradation.

### Architecture

```
Metrics Collectors ──> ComprehensiveSnapshotCollector (1s)
                              │
                              ▼
                       MinuteAggregator (rolling 120min window)
                              │
                              ▼
                       TTMPredictor (ONNX inference)
                              │
                              ▼
                       ForecastAnalyzer
                              │
                              ▼
                       ScalingRecommendation ──> AdaptiveDecisionTree ──> Controller
```

### When TTM Helps

- **Predictable load patterns**: Diurnal cycles, weekly patterns, scheduled events
- **Gradual ramps**: Load that increases steadily over minutes
- **Recurring spikes**: Periodic burst patterns the model can learn

### When Reactive is Sufficient

- **Unpredictable traffic**: Random spikes with no pattern
- **Very low latency requirements**: When 1-second reaction time is enough
- **Simple workloads**: Steady-state applications with minimal scaling needs

---

## Prerequisites

- Docker + Docker Compose (for deployment)
- Python 3.11+ (for training only)
- NVIDIA GPU (optional, speeds up training)
- At least 4GB RAM for training
- Running Aether cluster (for data collection)

---

## Quick Start (5 Minutes)

### Step 1: Build with TTM Support

```bash
# Without TTM (~32MB jar)
mvn package -pl aether/node -am -DskipTests

# With TTM (~104MB jar, includes ONNX Runtime)
mvn package -pl aether/node -am -DskipTests -Pwith-ttm
```

### Step 2: Get a Model

Option A — Use a pre-trained model (if available):
```bash
cp /path/to/ttm-aether.onnx models/ttm-aether.onnx
```

Option B — Train from synthetic data:
```bash
cd aether/aether-ttm-onnx/training
make all
cp ttm-aether.onnx ../../../models/
```

### Step 3: Enable in Configuration

In your `aether.toml`:
```toml
[ttm]
enabled = true
model_path = "models/ttm-aether.onnx"
input_window_minutes = 60
prediction_horizon = 1
evaluation_interval_ms = 60000
confidence_threshold = 0.7
```

### Step 4: Verify

```bash
# Start the node
java -jar target/aether-node.jar

# Check TTM status
curl -s http://localhost:8080/api/ttm/status | jq .
```

Expected response:
```json
{
  "enabled": true,
  "active": true,
  "state": "RUNNING",
  "modelPath": "models/ttm-aether.onnx",
  "inputWindowMinutes": 60,
  "evaluationIntervalMs": 60000,
  "confidenceThreshold": 0.7,
  "hasForecast": false,
  "lastForecast": null
}
```

TTM will start producing forecasts after collecting ~30 minutes of metrics data.

---

## Deployment

### Option A: Maven Profile (Recommended for CI/CD)

The `with-ttm` profile includes ONNX Runtime in the uber-jar:

```bash
# Build with TTM
mvn package -pl aether/node -am -DskipTests -Pwith-ttm

# Verify jar contains ONNX
unzip -l target/aether-node.jar | grep onnxruntime | head -5
```

Docker image:
```dockerfile
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/aether-node.jar .
COPY models/ models/
COPY aether.toml .
ENTRYPOINT ["java", "-jar", "aether-node.jar"]
```

### Option B: Extensions Directory (Flexible Deployment)

Build base image without TTM, add ONNX at deployment time:

```dockerfile
FROM eclipse-temurin:25-jre-alpine
WORKDIR /app
COPY target/aether-node.jar .
COPY aether.toml .
# Extensions directory for optional modules
RUN mkdir -p /app/extensions
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -cp '/app/aether-node.jar:/app/extensions/*' org.pragmatica.aether.Main"]
```

Deploy TTM by adding the jar:
```bash
# Build the TTM ONNX provider jar
mvn package -pl aether/aether-ttm-onnx -am -DskipTests

# Copy to extensions
cp aether/aether-ttm-onnx/target/aether-ttm-onnx-0.15.0.jar /app/extensions/
cp models/ttm-aether.onnx /app/models/
```

### Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `ttm.enabled` | `false` | Enable TTM predictive scaling |
| `ttm.model_path` | `models/ttm-aether.onnx` | Path to ONNX model file |
| `ttm.input_window_minutes` | `60` | Input window size (must match trained model) |
| `ttm.prediction_horizon` | `1` | Prediction horizon in minutes (1-10) |
| `ttm.evaluation_interval_ms` | `60000` | How often to run predictions (10s-5min) |
| `ttm.confidence_threshold` | `0.7` | Minimum confidence to act on predictions (0.0-1.0) |

Memory considerations:
- ONNX Runtime allocates native memory outside the JVM heap
- Set Docker `--memory` limit to at least `JVM heap + 512MB`
- Monitor RSS vs heap in container metrics

---

## Training a Model

### Step 1: Set Up Training Environment

```bash
cd aether/aether-ttm-onnx/training/
```

**Option A — Docker (recommended):**
```bash
docker build -t ttm-training -f Dockerfile.training .
```

**Option B — Local Python:**
```bash
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements.txt
```

Verify:
```bash
python -c "import torch; print(f'PyTorch {torch.__version__}')"
```

### Step 2: Collect Training Data

From a running Aether cluster:
```bash
# Export last 2 hours of metrics (leader node)
curl -s http://leader:8080/api/ttm/training-data > metrics.json
```

**Minimum data**: 2+ hours (120 data points). **Recommended**: 24+ hours for production models.

To collect over time:
```bash
# Cron job: collect every hour, append to CSV
0 * * * * curl -s http://leader:8080/api/ttm/training-data >> /data/metrics-$(date +\%Y\%m\%d).json
```

### Step 3: Generate Synthetic Data (Alternative)

When no production data is available:

```bash
# See all options
python generate_synthetic.py --help

# Generate 48 hours of mixed pattern data
python generate_synthetic.py --pattern mixed --duration 48h --output synthetic.csv

# Generate with specific seed for reproducibility
python generate_synthetic.py --pattern diurnal --duration 24h --seed 42 --output diurnal.csv
```

Available patterns:

| Pattern | Use Case | Description |
|---------|----------|-------------|
| `diurnal` | Web applications | Day/night cycle with peak at noon |
| `spike` | Event-driven systems | Diurnal base with random spikes |
| `ramp` | Growth scenarios | Gradual load increase |
| `steady` | Stable workloads | Constant base load with noise |
| `mixed` | Realistic (recommended) | Diurnal + spikes + trend |

### Step 4: Train the Model

```bash
python train.py --data metrics.csv --epochs 100 --output model.pt
```

Parameters:

| Flag | Default | Description |
|------|---------|-------------|
| `--data` | (required) | Input CSV file |
| `--epochs` | 100 | Training iterations |
| `--batch-size` | 32 | Training batch size |
| `--learning-rate` | 1e-4 | Step size |
| `--seq-len` | 60 | Input window (must match `input_window_minutes`) |
| `--horizon` | 1 | Prediction horizon (must match `prediction_horizon`) |
| `--validation-split` | 0.2 | Data held for validation |
| `--output` | model.pt | PyTorch model output |

Expected output:
```
Loading data from metrics.csv...
  2880 samples, 11 features
  2819 sequences (seq_len=60, horizon=1)
  Training: 2255, Validation: 564
  Device: cpu
  Model parameters: 53,451
  Epoch    1/100: train_loss=0.042318 val_loss=0.038921
  Epoch   10/100: train_loss=0.012543 val_loss=0.011876
  ...
  Epoch  100/100: train_loss=0.003210 val_loss=0.003456

Training complete. Best validation loss: 0.003210
Model saved to model.pt
```

**Troubleshooting:**
- OOM → Reduce `--batch-size` to 16 or 8
- Loss not decreasing → Increase `--learning-rate` to 1e-3
- Overfitting (val_loss increasing) → Reduce `--epochs` or increase data

### Step 5: Export to ONNX

```bash
python export_onnx.py --model model.pt --output ttm-aether.onnx
```

Expected output:
```
Loading model from model.pt...
  seq_len=60, horizon=1, features=11
  Input shape: [1, 60, 11]
  Exported to ttm-aether.onnx

Validating ttm-aether.onnx...
  ONNX model is valid
  Inputs: ['input']
  Outputs: ['output']
  Test inference: input=[1, 60, 11] -> output=[1, 1, 11]
  Output shape matches expected dimensions
  File size: 214.3 KB
  Validation passed!
```

Standalone validation:
```bash
python export_onnx.py --validate ttm-aether.onnx
```

### Step 6: Deploy the Model

```bash
# Copy to node
cp ttm-aether.onnx /path/to/node/models/

# Verify model loaded (check logs)
# Expected: "Loaded TTM model from models/ttm-aether.onnx"
# Expected: "Warm-up inference complete"

# Verify via API
curl -s http://localhost:8080/api/ttm/status | jq .state
# Expected: "RUNNING"
```

### Using the Makefile

```bash
make setup              # Install Python dependencies
make synthetic          # Generate 48h of synthetic data
make train DATA=data.csv  # Train model
make export             # Export to ONNX
make validate           # Validate ONNX model
make all DATA=data.csv  # Full pipeline
make docker-train DATA=data.csv  # Train in Docker
make clean              # Remove generated files
```

---

## Monitoring and Observability

### Key Metrics

| Metric | Type | Description |
|--------|------|-------------|
| `ttm.predictions.count` | Counter | Total predictions made |
| `ttm.recommendations{type=scale_up}` | Counter | Scale-up recommendations |
| `ttm.recommendations{type=scale_down}` | Counter | Scale-down recommendations |
| `ttm.recommendations{type=adjust_thresholds}` | Counter | Threshold adjustments |
| `ttm.recommendations{type=hold}` | Counter | No-action decisions |

### API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/ttm/status` | GET | Current state, model info, last prediction |
| `/api/ttm/training-data` | GET | Export metrics for retraining |
| `/metrics` | GET | Prometheus-compatible metrics |

### Example Prometheus Queries

```promql
# Prediction rate
rate(ttm_predictions_count_total[5m])

# Scale-up ratio
rate(ttm_recommendations_total{type="scale_up"}[1h]) / rate(ttm_predictions_count_total[1h])
```

---

## Safety and Guardrails

### Built-in Safety Bounds

- **Confidence floor** (default: 0.7): Predictions below this threshold are discarded
- **Leader-only**: TTM only runs on the leader node. Followers use the reactive controller.
- **Graceful degradation**: If TTM fails, the reactive controller handles everything

### Cold Start Behavior

TTM needs `inputWindowMinutes / 2` (default: 30 minutes) of data before making predictions.

Timeline from startup:
1. **0-30 min**: Collecting metrics, reactive controller only
2. **30+ min**: First TTM prediction (if leader)
3. **60+ min**: Full input window, best prediction quality

### Fallback Behavior

| Scenario | Behavior |
|----------|----------|
| TTM disabled | Reactive controller only (no degradation) |
| Model file missing | TTM stays disabled, warning in logs |
| Model file invalid | TTM reports error, falls back to reactive |
| ONNX inference fails | Error logged, state set to ERROR, reactive controller continues |
| Low confidence | Prediction discarded, no action taken |

---

## Retraining

### When to Retrain

- Workload patterns change significantly
- Prediction accuracy drops (check `/api/ttm/status` for forecast confidence)
- New features added to FeatureIndex (schema version change)

### Retraining Workflow

1. Collect fresh data (at least 24 hours recommended)
2. Train new model with collected data
3. Validate model produces reasonable outputs
4. Deploy alongside existing model
5. Monitor prediction quality
6. Promote or rollback based on metrics

---

## Troubleshooting

### TTM Won't Enable

- **Check**: Model file exists at configured path
- **Check**: `ttm.enabled = true` in configuration
- **Check**: Node is leader (TTM only runs on leader)
- **Check**: Logs for ONNX initialization errors
- **Check**: `aether-ttm-onnx` is on classpath (with-ttm profile or extensions)

### Poor Predictions

- **Check**: Model was trained on similar workload patterns
- **Check**: Confidence threshold (lower = more predictions but less accurate)
- **Check**: Input window matches training (`seq-len` in training = `input_window_minutes` in config)
- **Action**: Collect more data and retrain

### High Memory Usage

- ONNX Runtime uses native memory outside JVM heap
- Set `-XX:MaxDirectMemorySize` or Docker `--memory` limit
- Monitor: RSS vs heap in container metrics
- Typical overhead: ~200MB for ONNX Runtime + model

### Model Loading Fails

| Error | Cause | Fix |
|-------|-------|-----|
| "No TTM predictor implementation available" | `aether-ttm-onnx` not on classpath | Build with `-Pwith-ttm` or add to extensions |
| "File not found" | Model file missing | Check `ttm.model_path` relative to working directory |
| "Expected input tensor named 'input'" | Model trained with different schema | Re-export with `input_names=["input"]` |
| "ONNX Runtime initialization failed" | Native library issue | Check platform compatibility |

---

## Reference

### FeatureIndex Schema (v1)

| Index | Feature | Unit | Range |
|-------|---------|------|-------|
| 0 | cpu_usage | ratio | 0.0-1.0 |
| 1 | heap_usage | ratio | 0.0-1.0 |
| 2 | event_loop_lag_ms | milliseconds | 0+ |
| 3 | latency_ms | milliseconds | 0+ |
| 4 | invocations | count/min | 0+ |
| 5 | gc_pause_ms | milliseconds | 0+ |
| 6 | latency_p50 | milliseconds | 0+ |
| 7 | latency_p95 | milliseconds | 0+ |
| 8 | latency_p99 | milliseconds | 0+ |
| 9 | error_rate | ratio | 0.0-1.0 |
| 10 | event_count | count | 0+ |

### Model Specification

- **Format**: ONNX (opset 17)
- **Input**: `float[1, window_minutes, 11]` named `"input"`
- **Output**: `float[1, prediction_horizon, 11]` named `"output"`
- **Schema version**: 1 (tied to FeatureIndex above)
