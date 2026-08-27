#!/usr/bin/env python3
"""#591 — core coordination-load slope vs worker count.

Samples QUIC protocol-message rate and CPU/heap on each CORE node while worker count is swept,
and emits the slope table.

WHAT THIS MEASURES, AND WHAT IT DOES NOT
  Measures : QUIC protocol-message rate per CORE NODE (quic_messages_sent_total +
             quic_messages_received_total, differenced over a window), plus cpu.usage / heap.used.
  Does NOT : bytes (no byte counter reaches the wire — NetworkMetrics lives only inside
             ComprehensiveSnapshot, which the HTTP DTO drops, see #674); per-peer attribution
             (the counters are node-level totals); consensus-specific load (decisions/proposals
             are collected but never serialised, also #674); community count (group splitting is
             dead code, so communities are pinned at 1 per source, see #673).

  The deliverable is therefore a WORKER-COUNT slope for the shipping topology, not a
  community-count slope. That distinction is the whole point of the #591 re-scope — do not let a
  reader infer the latter from this table.

SATURATION GUARD
  A slope measured while backpressure climbs is measuring congestion, not coordination cost. The
  backpressure and write-failure counters are sampled and reported alongside every row; a run
  where they move is flagged, not silently averaged.

SELF-TEST
  `--selftest` runs the parsing and rate maths against fixtures shaped like the real DTOs. Today's
  repeated lesson is that an instrument gets validated against its own failure modes before its
  output is trusted; a measurement script that silently reports zeros is the same class of defect
  as a positive control that swallows its trigger.

Usage:
  ./coordination_slope.py --selftest
  ./coordination_slope.py --cores http://h:8080,http://h:8081,http://h:8082 \
                          --workers 4 --window 60 --out results.json
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

TRANSPORT_PATH = "/api/metrics/transport"
METRICS_PATH = "/api/metrics"

# Both routes are declared LOCAL in ManagementRoute (`:243` METRICS, `:248` METRICS_TRANSPORT) —
# each node answers for ITSELF rather than forwarding to the leader. That is what makes per-core
# sampling possible, and it is also why every core's own management port must be polled: hitting
# one node repeatedly would report that node's load three times, not the cluster's.

MSG_KEYS = ("quic_messages_sent_total", "quic_messages_received_total")
GUARD_KEYS = ("quic_backpressure_drops_total", "quic_backpressure_retries_total",
              "quic_write_failures_total", "quic_backpressure_queue_depth")


def fetch(base, path, timeout=10):
    with urllib.request.urlopen(base.rstrip("/") + path, timeout=timeout) as resp:
        return json.loads(resp.read().decode())


def message_total(transport):
    """Sum of the two monotonic protocol-message counters.

    Missing keys are an ERROR, not a zero. A node that reports no counters would otherwise look
    like a perfectly idle node and drag the slope toward flat — the silent-zero failure this
    script exists to avoid.
    """
    missing = [k for k in MSG_KEYS if k not in transport]
    if missing:
        raise KeyError(f"transport payload missing {missing}; got keys {sorted(transport)[:8]}")

    return sum(int(transport[k]) for k in MSG_KEYS)


def guards(transport):
    return {k: int(transport.get(k, 0)) for k in GUARD_KEYS}


def load_for(metrics, node_hint):
    """`GET /api/metrics` returns {"load": {"<nodeId>": {"cpu.usage": .., "heap.used": ..}}, ...}.

    One node's endpoint reports that node's own id, so with a single entry take it; with several,
    match on the hint. Returns (nodeId, cpu, heapUsed).
    """
    load = metrics.get("load") or {}
    if not load:
        raise KeyError("metrics payload carries no 'load' map")

    key = node_hint if node_hint in load else (list(load)[0] if len(load) == 1 else None)
    if key is None:
        raise KeyError(f"cannot disambiguate node in load map {sorted(load)}; pass --node-ids")

    entry = load[key]

    return key, float(entry.get("cpu.usage", 0.0)), float(entry.get("heap.used", 0.0))


def sample_core(base, window, node_hint=None):
    t0 = time.monotonic()
    before = fetch(base, TRANSPORT_PATH)
    start_total = message_total(before)

    time.sleep(window)

    after = fetch(base, TRANSPORT_PATH)
    elapsed = time.monotonic() - t0
    delta = message_total(after) - start_total

    if delta < 0:
        raise ValueError(f"{base}: message counter went BACKWARDS ({delta}) — the node restarted "
                         "mid-window; this sample is void, re-run it rather than reporting it")

    metrics = fetch(base, METRICS_PATH)
    node_id, cpu, heap = load_for(metrics, node_hint)
    before_guards, after_guards = guards(before), guards(after)
    guard_delta = {k: after_guards[k] - before_guards[k] for k in GUARD_KEYS}

    return {
        "endpoint": base,
        "nodeId": node_id,
        "windowSeconds": round(elapsed, 2),
        "messages": delta,
        "messagesPerSecond": round(delta / elapsed, 2) if elapsed > 0 else 0.0,
        "cpuUsage": round(cpu, 4),
        "heapUsedBytes": int(heap),
        "guardDeltas": guard_delta,
        "saturated": any(v > 0 for k, v in guard_delta.items() if k != "quic_backpressure_queue_depth"),
    }


def selftest():
    ok = True

    def check(name, cond):
        nonlocal ok
        print(f"  {'PASS' if cond else 'FAIL'}  {name}")
        ok = ok and cond

    print("self-test: parsing and rate maths against DTO-shaped fixtures")

    transport = {"quic_messages_sent_total": 1000, "quic_messages_received_total": 500,
                 "quic_active_connections": 4, "quic_backpressure_drops_total": 0}
    check("sums both message counters", message_total(transport) == 1500)

    try:
        message_total({"quic_active_connections": 4})
        check("missing counters raise rather than returning a silent zero", False)
    except KeyError:
        check("missing counters raise rather than returning a silent zero", True)

    metrics = {"load": {"core-1": {"cpu.usage": 0.42, "heap.used": 268435456.0}}}
    node, cpu, heap = load_for(metrics, None)
    check("single-node load map resolves without a hint", node == "core-1" and cpu == 0.42 and heap == 268435456.0)

    multi = {"load": {"core-1": {"cpu.usage": 0.1}, "core-2": {"cpu.usage": 0.2}}}
    check("ambiguous load map resolves via hint", load_for(multi, "core-2")[1] == 0.2)
    try:
        load_for(multi, None)
        check("ambiguous load map without a hint raises", False)
    except KeyError:
        check("ambiguous load map without a hint raises", True)

    g = guards({"quic_backpressure_drops_total": 3})
    check("absent guard keys default to 0 without masking present ones",
          g["quic_backpressure_drops_total"] == 3 and g["quic_write_failures_total"] == 0)

    print("SELF-TEST:", "OK" if ok else "FAILED")

    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description="#591 coordination-load slope sampler")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--cores", help="comma-separated CORE node management base URLs")
    ap.add_argument("--node-ids", help="comma-separated node ids, aligned with --cores")
    ap.add_argument("--workers", type=int, help="worker count this sample is labelled with")
    ap.add_argument("--window", type=int, default=60, help="sampling window, seconds")
    ap.add_argument("--out", help="append the row to this JSON-lines file")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if not args.cores or args.workers is None:
        ap.error("--cores and --workers are required unless --selftest")

    bases = [b.strip() for b in args.cores.split(",") if b.strip()]
    hints = [h.strip() for h in (args.node_ids or "").split(",")] if args.node_ids else [None] * len(bases)
    if len(hints) != len(bases):
        ap.error("--node-ids must align 1:1 with --cores")

    samples = [sample_core(b, args.window, h) for b, h in zip(bases, hints)]
    total_rate = sum(s["messagesPerSecond"] for s in samples)
    row = {
        "workers": args.workers,
        "cores": len(samples),
        "communities": 1,
        "totalCoreMessagesPerSecond": round(total_rate, 2),
        "perCoreMessagesPerSecond": round(total_rate / len(samples), 2),
        "meanCoreCpuUsage": round(sum(s["cpuUsage"] for s in samples) / len(samples), 4),
        "meanCoreHeapUsedBytes": int(sum(s["heapUsedBytes"] for s in samples) / len(samples)),
        "anyCoreSaturated": any(s["saturated"] for s in samples),
        "samples": samples,
    }

    print(json.dumps(row, indent=2))
    if row["anyCoreSaturated"]:
        print("\nWARNING: backpressure or write failures moved during this window. This row measures "
              "congestion, not coordination cost — do not put it in the slope without saying so.",
              file=sys.stderr)
    if args.out:
        with open(args.out, "a") as fh:
            fh.write(json.dumps(row) + "\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
