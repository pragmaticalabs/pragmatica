#!/usr/bin/env python3
"""
Aether Management API - Python Client Example

Usage:
    python python-client.py [--url http://localhost:8080]
"""

import json
import argparse
import urllib.request
import urllib.error


class AetherClient:
    """Simple Python client for Aether Management API."""

    def __init__(self, base_url: str = "http://localhost:8080"):
        self.base_url = base_url.rstrip("/")

    def _get(self, path: str) -> dict:
        """Make GET request."""
        url = f"{self.base_url}{path}"
        try:
            with urllib.request.urlopen(url) as response:
                return json.loads(response.read().decode())
        except urllib.error.HTTPError as e:
            return {"error": f"HTTP {e.code}: {e.read().decode()}"}
        except Exception as e:
            return {"error": str(e)}

    def _post(self, path: str, data: dict) -> dict:
        """Make POST request."""
        url = f"{self.base_url}{path}"
        try:
            req = urllib.request.Request(
                url,
                data=json.dumps(data).encode(),
                headers={"Content-Type": "application/json"},
                method="POST"
            )
            with urllib.request.urlopen(req) as response:
                return json.loads(response.read().decode())
        except urllib.error.HTTPError as e:
            return {"error": f"HTTP {e.code}: {e.read().decode()}"}
        except Exception as e:
            return {"error": str(e)}

    # Cluster Status
    def status(self) -> dict:
        """Get cluster status."""
        return self._get("/api/status")

    def health(self) -> dict:
        """Get health status."""
        return self._get("/api/health")

    def nodes(self) -> dict:
        """List cluster nodes."""
        return self._get("/api/nodes")

    # Slice Management
    def slices(self) -> dict:
        """List deployed slices."""
        return self._get("/api/slices")

    def apply_blueprint(self, blueprint_content: str) -> dict:
        """Apply a blueprint (deploy slices)."""
        url = f"{self.base_url}/api/blueprint"
        try:
            req = urllib.request.Request(
                url,
                data=blueprint_content.encode(),
                headers={"Content-Type": "application/json"},
                method="POST"
            )
            with urllib.request.urlopen(req) as response:
                return json.loads(response.read().decode())
        except urllib.error.HTTPError as e:
            return {"error": f"HTTP {e.code}: {e.read().decode()}"}
        except Exception as e:
            return {"error": str(e)}

    def delete_blueprint(self, blueprint_id: str) -> dict:
        """Delete a blueprint (undeploy slices)."""
        url = f"{self.base_url}/api/blueprint/{blueprint_id}"
        try:
            req = urllib.request.Request(url, method="DELETE")
            with urllib.request.urlopen(req) as response:
                return json.loads(response.read().decode())
        except urllib.error.HTTPError as e:
            return {"error": f"HTTP {e.code}: {e.read().decode()}"}
        except Exception as e:
            return {"error": str(e)}

    def scale(self, artifact: str, instances: int) -> dict:
        """Scale a slice."""
        return self._post("/api/scale", {"artifact": artifact, "instances": instances})

    # Metrics
    def metrics(self) -> dict:
        """Get cluster metrics."""
        return self._get("/api/metrics")

    def invocation_metrics(self) -> dict:
        """Get invocation metrics."""
        return self._get("/api/invocation-metrics")

    def slow_invocations(self) -> dict:
        """Get slow invocations."""
        return self._get("/api/invocation-metrics/slow")

    # Controller
    def controller_config(self) -> dict:
        """Get controller configuration."""
        return self._get("/api/controller/config")

    def update_controller_config(self, **kwargs) -> dict:
        """Update controller configuration."""
        return self._post("/api/controller/config", kwargs)

    def controller_status(self) -> dict:
        """Get controller status."""
        return self._get("/api/controller/status")

    # Alerts
    def alerts(self) -> dict:
        """Get all alerts."""
        return self._get("/api/alerts")

    def active_alerts(self) -> dict:
        """Get active alerts."""
        return self._get("/api/alerts/active")

    def clear_alerts(self) -> dict:
        """Clear all alerts."""
        return self._post("/api/alerts/clear", {})

    # Thresholds
    def thresholds(self) -> dict:
        """Get all thresholds."""
        return self._get("/api/thresholds")

    def set_threshold(self, metric: str, warning: float, critical: float) -> dict:
        """Set a threshold."""
        return self._post("/api/thresholds", {
            "metric": metric,
            "warning": warning,
            "critical": critical
        })

    # Deployments
    def start_deployment(self, artifact_base: str, version: str,
                         strategy: str = "IMMEDIATE", instances: int = 1, **kwargs) -> dict:
        """Start a deployment."""
        data = {
            "artifactBase": artifact_base,
            "version": version,
            "strategy": strategy,
            "instances": instances,
            **kwargs
        }
        return self._post("/api/deploy", data)

    def deployments(self) -> dict:
        """List active deployments."""
        return self._get("/api/deploy")

    def deployment_status(self, deployment_id: str) -> dict:
        """Get deployment status."""
        return self._get(f"/api/deploy/{deployment_id}")

    def promote_deployment(self, deployment_id: str) -> dict:
        """Advance deployment to next stage."""
        return self._post(f"/api/deploy/{deployment_id}/promote", {})

    def complete_deployment(self, deployment_id: str) -> dict:
        """Complete deployment."""
        return self._post(f"/api/deploy/{deployment_id}/complete", {})

    def rollback_deployment(self, deployment_id: str) -> dict:
        """Rollback deployment."""
        return self._post(f"/api/deploy/{deployment_id}/rollback", {})


def main():
    parser = argparse.ArgumentParser(description="Aether Management API Client")
    parser.add_argument("--url", default="http://localhost:8080", help="Node URL")
    args = parser.parse_args()

    client = AetherClient(args.url)

    print("=== Cluster Status ===")
    print(json.dumps(client.status(), indent=2))
    print()

    print("=== Health ===")
    print(json.dumps(client.health(), indent=2))
    print()

    print("=== Nodes ===")
    print(json.dumps(client.nodes(), indent=2))
    print()

    print("=== Slices ===")
    print(json.dumps(client.slices(), indent=2))
    print()

    print("=== Metrics ===")
    print(json.dumps(client.metrics(), indent=2))
    print()

    print("=== Controller Config ===")
    print(json.dumps(client.controller_config(), indent=2))
    print()

    print("=== Alerts ===")
    print(json.dumps(client.alerts(), indent=2))
    print()

    print("=== Thresholds ===")
    print(json.dumps(client.thresholds(), indent=2))


if __name__ == "__main__":
    main()
