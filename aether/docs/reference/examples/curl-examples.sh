#!/bin/bash
# Aether Management API - curl examples
# Usage: Set BASE_URL to your node address

BASE_URL="${BASE_URL:-http://localhost:8080}"
# security_mode defaults to api-key (issue #290); set API_KEY to an ADMIN/OPERATOR key for
# mutating calls, an authenticated user's key for the rest — every route below requires at
# least ALL_AUTHENTICATED. See reference/management-api.md#authentication.
API_KEY="${API_KEY:-}"
AUTH_HEADER=()
[ -n "$API_KEY" ] && AUTH_HEADER=(-H "X-API-Key: $API_KEY")

echo "=== Cluster Status ==="
curl -s "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/nodes/status" | jq .
echo

echo "=== Health Check ==="
curl -s "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/health" | jq .
echo

echo "=== List Nodes ==="
curl -s "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/nodes" | jq .
echo

echo "=== List Slices ==="
curl -s "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/slices" | jq .
echo

echo "=== Apply Blueprint ==="
curl -s -X POST "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/blueprints" \
  -H "Content-Type: application/json" \
  -d 'id = "my-blueprint"

[[slices]]
artifact = "org.example:my-slice:1.0.0"
instances = 3' | jq .
echo

echo "=== Scale Slice ==="
curl -s -X POST "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/scale" \
  -H "Content-Type: application/json" \
  -d '{"artifact": "org.example:my-slice:1.0.0", "instances": 5}' | jq .
echo

echo "=== Get Metrics ==="
curl -s "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/metrics" | jq .
echo

echo "=== Get Invocation Metrics ==="
curl -s "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/invocations/metrics" | jq .
echo

echo "=== Get Slow Invocations ==="
curl -s "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/invocations/metrics/slow" | jq .
echo

echo "=== Get Controller Config ==="
curl -s "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/controller/config" | jq .
echo

echo "=== Update Controller Config ==="
curl -s -X POST "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/controller/config" \
  -H "Content-Type: application/json" \
  -d '{"cpuScaleUpThreshold": 0.75}' | jq .
echo

echo "=== Get Alerts ==="
curl -s "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/alerts" | jq .
echo

echo "=== Get Thresholds ==="
curl -s "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/thresholds" | jq .
echo

echo "=== Set Threshold ==="
curl -s -X POST "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/thresholds" \
  -H "Content-Type: application/json" \
  -d '{"metric": "cpu.usage", "warning": 0.7, "critical": 0.9}' | jq .
echo

echo "=== Start Rolling Deployment ==="
curl -s -X POST "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/deploy" \
  -H "Content-Type: application/json" \
  -d '{
    "blueprint": "org.example:my-slice:2.0.0",
    "strategy": "ROLLING",
    "instances": 3,
    "thresholds": {"maxErrorRate": 0.01, "maxLatencyMs": 500},
    "cleanupPolicy": "GRACE_PERIOD"
  }' | jq .
echo

echo "=== List Active Deployments ==="
curl -s "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/deploy" | jq .
echo

echo "=== Promote Deployment ==="
# Replace DEPLOYMENT_ID with actual deployment ID
# curl -s -X POST "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/deploy/promote/DEPLOYMENT_ID" | jq .
echo

echo "=== Prometheus Metrics ==="
curl -s "${AUTH_HEADER[@]}" "$BASE_URL/api/v1/metrics/prometheus" | head -20
echo
