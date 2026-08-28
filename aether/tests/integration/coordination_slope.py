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
  ./coordination_slope.py --cores http://h:20100,http://h:20101,http://h:20102 \
                          --node-ids core-1,core-2,core-3 --workers 4 --window 60 --out results.json
"""
import argparse
import json
import sys
import time
import urllib.error
import urllib.request

TRANSPORT_PATH = "/api/v1/metrics/transport"
METRICS_PATH = "/api/v1/metrics"

# The two routes behave DIFFERENTLY despite both being declared LOCAL in ManagementRoute
# (METRICS, METRICS_TRANSPORT — both versioned under the /api/v1 base since the management-api
# versioning cutover), and the difference is easy to get backwards:
#   /api/v1/metrics/transport — genuinely per-node. Each core must be polled for its own counters.
#   /api/v1/metrics           — CLUSTER-WIDE `load` map. Any node returns an entry for every node it
#                               knows, so it is fetched once and the cores are selected by id.
# Assuming the second was per-node is what the live 3-node validation caught.

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


def load_map(base):
    """`GET /api/v1/metrics` returns {"load": {"<nodeId>": {"cpu.usage": .., "heap.used": ..}}, ...}.

    IMPORTANT, and not what this script first assumed: the load map is CLUSTER-WIDE. Although the
    route is declared LOCAL, the payload carries an entry for every node the collector knows, not
    just the node being polled. Confirmed against a live 3-node cluster, where every node answered
    with all three ids. So this is fetched ONCE, and the core entries are selected by id — polling
    it per core would return the same cluster-wide map N times.
    """
    metrics = fetch(base, METRICS_PATH)
    load = metrics.get("load") or {}

    if not load:
        raise KeyError("metrics payload carries no 'load' map")

    return load


def core_load(load, node_id):
    if node_id not in load:
        raise KeyError(f"node id {node_id!r} absent from load map {sorted(load)}; "
                       "--node-ids must name the CORE nodes as the cluster knows them")

    entry = load[node_id]

    return float(entry.get("cpu.usage", 0.0)), float(entry.get("heap.used", 0.0))


def sample_cores(bases, window):
    """Differences every core's counters over ONE SHARED window.

    The first version sampled each core in turn, sleeping per core — so with three cores and a 60s
    window each core was measured over a DIFFERENT minute, and the "slope" summed rates that never
    coexisted. A coordination-load figure is only meaningful if the cores are observed
    simultaneously, so all `before` reads happen, then one sleep, then all `after` reads.
    """
    t0 = time.monotonic()
    before = {b: fetch(b, TRANSPORT_PATH) for b in bases}
    start = {b: message_total(before[b]) for b in bases}

    time.sleep(window)

    after = {b: fetch(b, TRANSPORT_PATH) for b in bases}
    elapsed = time.monotonic() - t0

    out = []
    for b in bases:
        delta = message_total(after[b]) - start[b]

        if delta < 0:
            raise ValueError(f"{b}: message counter went BACKWARDS ({delta}) — the node restarted "
                             "mid-window; this sample is void, re-run it rather than reporting it")

        gd = {k: guards(after[b])[k] - guards(before[b])[k] for k in GUARD_KEYS}
        out.append({
            "endpoint": b,
            "windowSeconds": round(elapsed, 2),
            "messages": delta,
            "messagesPerSecond": round(delta / elapsed, 2) if elapsed > 0 else 0.0,
            "guardDeltas": gd,
            "saturated": any(v > 0 for k, v in gd.items() if k != "quic_backpressure_queue_depth"),
        })

    return out



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

    metrics_load = {"core-1": {"cpu.usage": 0.42, "heap.used": 268435456.0},
                    "worker-9": {"cpu.usage": 0.9, "heap.used": 1.0}}
    cpu, heap = core_load(metrics_load, "core-1")
    check("selects the named core from a CLUSTER-WIDE load map", cpu == 0.42 and heap == 268435456.0)

    try:
        core_load(metrics_load, "core-absent")
        check("an unknown node id raises rather than silently picking another node", False)
    except KeyError:
        check("an unknown node id raises rather than silently picking another node", True)

    g = guards({"quic_backpressure_drops_total": 3})
    check("absent guard keys default to 0 without masking present ones",
          g["quic_backpressure_drops_total"] == 3 and g["quic_write_failures_total"] == 0)

    print("SELF-TEST:", "OK" if ok else "FAILED")

    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser(description="#591 coordination-load slope sampler")
    ap.add_argument("--selftest", action="store_true")
    ap.add_argument("--cores", help="comma-separated CORE node management base URLs")
    ap.add_argument("--node-ids", help="CORE node ids as the cluster knows them, aligned 1:1 with --cores. Required: the load map is cluster-wide, so the cores must be named.")
    ap.add_argument("--workers", type=int, help="worker count this sample is labelled with")
    ap.add_argument("--window", type=int, default=60, help="sampling window, seconds")
    ap.add_argument("--out", help="append the row to this JSON-lines file")
    args = ap.parse_args()

    if args.selftest:
        return selftest()

    if not args.cores or args.workers is None:
        ap.error("--cores and --workers are required unless --selftest")

    bases = [b.strip() for b in args.cores.split(",") if b.strip()]

    if not args.node_ids:
        ap.error("--node-ids is required: the load map is cluster-wide, so the CORE nodes must be "
                 "named explicitly. Averaging every entry would fold workers into the core mean and "
                 "silently understate per-core load.")

    ids = [h.strip() for h in args.node_ids.split(",") if h.strip()]
    if len(ids) != len(bases):
        ap.error("--node-ids must align 1:1 with --cores")

    samples = sample_cores(bases, args.window)
    load = load_map(bases[0])
    cpus, heaps = [], []

    for sample, node_id in zip(samples, ids):
        cpu, heap = core_load(load, node_id)
        sample["nodeId"] = node_id
        sample["cpuUsage"] = round(cpu, 4)
        sample["heapUsedBytes"] = int(heap)
        cpus.append(cpu)
        heaps.append(heap)

    total_rate = sum(s["messagesPerSecond"] for s in samples)
    row = {
        "workers": args.workers,
        "cores": len(samples),
        "communities": 1,
        "totalCoreMessagesPerSecond": round(total_rate, 2),
        "perCoreMessagesPerSecond": round(total_rate / len(samples), 2),
        "meanCoreCpuUsage": round(sum(cpus) / len(cpus), 4),
        "meanCoreHeapUsedBytes": int(sum(heaps) / len(heaps)),
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
