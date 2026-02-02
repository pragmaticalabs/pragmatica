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
        return self._get("/status")

    def health(self) -> dict:
        """Get health status."""
        return self._get("/health")

    def nodes(self) -> dict:
        """List cluster nodes."""
        return self._get("/nodes")

    # Slice Management
    def slices(self) -> dict:
        """List deployed slices."""
        return self._get("/slices")

    def deploy(self, artifact: str, instances: int = 1) -> dict:
        """Deploy a slice."""
        return self._post("/deploy", {"artifact": artifact, "instances": instances})

    def scale(self, artifact: str, instances: int) -> dict:
        """Scale a slice."""
        return self._post("/scale", {"artifact": artifact, "instances": instances})

    def undeploy(self, artifact: str) -> dict:
        """Undeploy a slice."""
        return self._post("/undeploy", {"artifact": artifact})

    # Metrics
    def metrics(self) -> dict:
        """Get cluster metrics."""
        return self._get("/metrics")

    def invocation_metrics(self) -> dict:
        """Get invocation metrics."""
        return self._get("/invocation-metrics")

    def slow_invocations(self) -> dict:
        """Get slow invocations."""
        return self._get("/invocation-metrics/slow")

    # Controller
    def controller_config(self) -> dict:
        """Get controller configuration."""
        return self._get("/controller/config")

    def update_controller_config(self, **kwargs) -> dict:
        """Update controller configuration."""
        return self._post("/controller/config", kwargs)

    def controller_status(self) -> dict:
        """Get controller status."""
        return self._get("/controller/status")

    # Alerts
    def alerts(self) -> dict:
        """Get all alerts."""
        return self._get("/alerts")

    def active_alerts(self) -> dict:
        """Get active alerts."""
        return self._get("/alerts/active")

    def clear_alerts(self) -> dict:
        """Clear all alerts."""
        return self._post("/alerts/clear", {})

    # Thresholds
    def thresholds(self) -> dict:
        """Get all thresholds."""
        return self._get("/thresholds")

    def set_threshold(self, metric: str, warning: float, critical: float) -> dict:
        """Set a threshold."""
        return self._post("/thresholds", {
            "metric": metric,
            "warning": warning,
            "critical": critical
        })

    # Rolling Updates
    def start_rolling_update(self, artifact_base: str, version: str,
                             instances: int = 1, **kwargs) -> dict:
        """Start a rolling update."""
        data = {
            "artifactBase": artifact_base,
            "version": version,
            "instances": instances,
            **kwargs
        }
        return self._post("/rolling-update/start", data)

    def rolling_updates(self) -> dict:
        """List active rolling updates."""
        return self._get("/rolling-updates")

    def rolling_update_status(self, update_id: str) -> dict:
        """Get rolling update status."""
        return self._get(f"/rolling-update/{update_id}")

    def adjust_routing(self, update_id: str, routing: str) -> dict:
        """Adjust traffic routing."""
        return self._post(f"/rolling-update/{update_id}/routing", {"routing": routing})

    def complete_update(self, update_id: str) -> dict:
        """Complete rolling update."""
        return self._post(f"/rolling-update/{update_id}/complete", {})

    def rollback_update(self, update_id: str) -> dict:
        """Rollback rolling update."""
        return self._post(f"/rolling-update/{update_id}/rollback", {})


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
