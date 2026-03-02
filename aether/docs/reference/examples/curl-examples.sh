#!/bin/bash
# Aether Management API - curl examples
# Usage: Set BASE_URL to your node address

BASE_URL="${BASE_URL:-http://localhost:8080}"

echo "=== Cluster Status ==="
curl -s "$BASE_URL/api/status" | jq .
echo

echo "=== Health Check ==="
curl -s "$BASE_URL/api/health" | jq .
echo

echo "=== List Nodes ==="
curl -s "$BASE_URL/api/nodes" | jq .
echo

echo "=== List Slices ==="
curl -s "$BASE_URL/api/slices" | jq .
echo

echo "=== Apply Blueprint ==="
curl -s -X POST "$BASE_URL/api/blueprint" \
  -H "Content-Type: application/json" \
  -d 'id = "my-blueprint"

[[slices]]
artifact = "org.example:my-slice:1.0.0"
instances = 3' | jq .
echo

echo "=== Scale Slice ==="
curl -s -X POST "$BASE_URL/api/scale" \
  -H "Content-Type: application/json" \
  -d '{"artifact": "org.example:my-slice:1.0.0", "instances": 5}' | jq .
echo

echo "=== Get Metrics ==="
curl -s "$BASE_URL/api/metrics" | jq .
echo

echo "=== Get Invocation Metrics ==="
curl -s "$BASE_URL/api/invocation-metrics" | jq .
echo

echo "=== Get Slow Invocations ==="
curl -s "$BASE_URL/api/invocation-metrics/slow" | jq .
echo

echo "=== Get Controller Config ==="
curl -s "$BASE_URL/api/controller/config" | jq .
echo

echo "=== Update Controller Config ==="
curl -s -X POST "$BASE_URL/api/controller/config" \
  -H "Content-Type: application/json" \
  -d '{"cpuScaleUpThreshold": 0.75}' | jq .
echo

echo "=== Get Alerts ==="
curl -s "$BASE_URL/api/alerts" | jq .
echo

echo "=== Get Thresholds ==="
curl -s "$BASE_URL/api/thresholds" | jq .
echo

echo "=== Set Threshold ==="
curl -s -X POST "$BASE_URL/api/thresholds" \
  -H "Content-Type: application/json" \
  -d '{"metric": "cpu.usage", "warning": 0.7, "critical": 0.9}' | jq .
echo

echo "=== Start Rolling Update ==="
curl -s -X POST "$BASE_URL/api/rolling-update/start" \
  -H "Content-Type: application/json" \
  -d '{
    "artifactBase": "org.example:my-slice",
    "version": "2.0.0",
    "instances": 3,
    "maxErrorRate": 0.01,
    "maxLatencyMs": 500,
    "cleanupPolicy": "GRACE_PERIOD"
  }' | jq .
echo

echo "=== List Rolling Updates ==="
curl -s "$BASE_URL/api/rolling-updates" | jq .
echo

echo "=== Adjust Routing (25% new) ==="
# Replace UPDATE_ID with actual update ID
# curl -s -X POST "$BASE_URL/api/rolling-update/UPDATE_ID/routing" \
#   -H "Content-Type: application/json" \
#   -d '{"routing": "1:3"}' | jq .
echo

echo "=== Prometheus Metrics ==="
curl -s "$BASE_URL/api/metrics/prometheus" | head -20
echo
