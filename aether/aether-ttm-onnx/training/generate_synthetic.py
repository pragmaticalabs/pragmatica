#!/usr/bin/env python3
"""Generate synthetic time series data for TTM model training.

Produces realistic MinuteAggregate data matching the Aether FeatureIndex schema:
  0: cpu_usage (0.0-1.0)
  1: heap_usage (0.0-1.0)
  2: event_loop_lag_ms
  3: latency_ms
  4: invocations (count/min)
  5: gc_pause_ms
  6: latency_p50
  7: latency_p95
  8: latency_p99
  9: error_rate (0.0-1.0)
 10: event_count
"""

import argparse
import csv
import math
import random
import sys
from pathlib import Path


FEATURE_NAMES = [
    "cpu_usage", "heap_usage", "event_loop_lag_ms", "latency_ms", "invocations",
    "gc_pause_ms", "latency_p50", "latency_p95", "latency_p99", "error_rate", "event_count"
]


def parse_duration(s: str) -> int:
    """Parse duration string like '48h', '120m', '2d' into minutes."""
    s = s.strip().lower()
    if s.endswith("d"):
        return int(s[:-1]) * 24 * 60
    if s.endswith("h"):
        return int(s[:-1]) * 60
    if s.endswith("m"):
        return int(s[:-1])
    return int(s)


def clamp(v, lo, hi):
    return max(lo, min(hi, v))


def noise(scale=0.02):
    return random.gauss(0, scale)


def diurnal_factor(minute: int) -> float:
    """Simulate day/night cycle. Peak at noon (minute 720), trough at 4am (minute 240)."""
    hour_frac = (minute % 1440) / 1440.0
    return 0.3 + 0.7 * (0.5 + 0.5 * math.sin(2 * math.pi * (hour_frac - 0.25)))


def generate_row(minute: int, pattern: str, base_load: float) -> dict:
    """Generate one minute of synthetic data."""
    t = minute
    factor = 1.0

    if pattern == "diurnal":
        factor = diurnal_factor(t)
    elif pattern == "spike":
        factor = diurnal_factor(t)
        # Random spikes ~every 30 minutes
        if random.random() < 1 / 30:
            factor = min(1.0, factor + random.uniform(0.3, 0.6))
    elif pattern == "ramp":
        # Gradual ramp up over the entire duration
        factor = 0.2 + 0.8 * (t / max(1, t + 500))
    elif pattern == "steady":
        factor = 0.5
    elif pattern == "mixed":
        factor = diurnal_factor(t)
        # Occasional spikes
        if random.random() < 1 / 60:
            factor = min(1.0, factor + random.uniform(0.2, 0.5))
        # Gradual trend component
        factor = factor * (0.8 + 0.2 * min(1.0, t / 1440))

    load = clamp(base_load * factor, 0.05, 0.95)

    cpu = clamp(load + noise(0.03), 0.0, 1.0)
    heap = clamp(0.3 + load * 0.5 + noise(0.02), 0.0, 1.0)
    el_lag = clamp(1.0 + cpu * 20.0 + noise(1.0), 0.1, 100.0)
    latency = clamp(5.0 + cpu * 50.0 + noise(3.0), 1.0, 500.0)
    invocations = max(0, int(100 + load * 900 + random.gauss(0, 20)))
    gc_pause = max(0, int(5 + cpu * 30 + random.gauss(0, 3)))
    p50 = clamp(latency * 0.6 + noise(1.0), 1.0, 300.0)
    p95 = clamp(latency * 1.5 + noise(2.0), 2.0, 600.0)
    p99 = clamp(latency * 2.5 + noise(3.0), 3.0, 1000.0)
    error_rate = clamp(0.001 + (cpu ** 3) * 0.05 + noise(0.002), 0.0, 1.0)
    event_count = max(0, int(invocations * 1.2 + random.gauss(0, 10)))

    return {
        "timestamp": minute * 60_000,
        "cpu_usage": round(cpu, 6),
        "heap_usage": round(heap, 6),
        "event_loop_lag_ms": round(el_lag, 3),
        "latency_ms": round(latency, 3),
        "invocations": invocations,
        "gc_pause_ms": gc_pause,
        "latency_p50": round(p50, 3),
        "latency_p95": round(p95, 3),
        "latency_p99": round(p99, 3),
        "error_rate": round(error_rate, 6),
        "event_count": event_count,
    }


def main():
    parser = argparse.ArgumentParser(description="Generate synthetic TTM training data")
    parser.add_argument("--pattern", choices=["diurnal", "spike", "ramp", "steady", "mixed"],
                        default="mixed", help="Load pattern (default: mixed)")
    parser.add_argument("--duration", default="48h",
                        help="Duration (e.g., 48h, 120m, 2d). Default: 48h")
    parser.add_argument("--base-load", type=float, default=0.5,
                        help="Base load factor 0.0-1.0 (default: 0.5)")
    parser.add_argument("--seed", type=int, default=None, help="Random seed for reproducibility")
    parser.add_argument("--output", "-o", default="synthetic.csv", help="Output CSV file")
    args = parser.parse_args()

    if args.seed is not None:
        random.seed(args.seed)

    minutes = parse_duration(args.duration)
    print(f"Generating {minutes} minutes of '{args.pattern}' data (base_load={args.base_load})...",
          file=sys.stderr)

    output = Path(args.output)
    fieldnames = ["timestamp"] + FEATURE_NAMES
    with output.open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        for m in range(minutes):
            writer.writerow(generate_row(m, args.pattern, args.base_load))

    print(f"Wrote {minutes} rows to {output}", file=sys.stderr)


if __name__ == "__main__":
    main()
