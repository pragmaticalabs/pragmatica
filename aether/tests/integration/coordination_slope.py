#!/usr/bin/env python3
"""#591 — core coordination-load slope vs worker count.

Samples QUIC protocol-message rate and CPU/heap on each CORE node while worker count is swept,
and emits the slope table.

WHAT THIS MEASURES, AND WHAT IT DOES NOT
  Measures : QUIC protocol-message rate per CORE NODE (quic_messages_sent_total +
             quic_messages_received_total, differenced over a window), plus cpu.usage / heap.used.
  Does NOT : sample bytes — this script does not read them, not because they are unreachable.
             `quic_bytes_sent_total` / `quic_bytes_received_total` (#726) DO reach the wire, over
             the same transport-gauge path as the message counters above; they count PAYLOAD bytes
             at the lane boundary (the serialized frame handed to/decoded from the channel, after
             QUIC framing/TLS overhead/retransmits are stripped — never a bandwidth figure). A
             future revision could sample them the same way it samples quic_messages_*_total.
             Still missing: per-peer attribution (the counters are node-level totals);
             consensus-specific load (decisions/proposals are collected but never serialised,
             also #674).

  The deliverable is therefore a WORKER-COUNT slope for the shipping topology, not a
  community-count slope. That distinction is the whole point of the #591 re-scope — do not let a
  reader infer the latter from this table.

WORKER PARTICIPATION IS MEASURED, NOT ASSUMED (#728)
  This script used to emit a hard-coded `"communities": 1` in every row. It read as data and was
  never read from anything, and it hid a real defect: the published 4/8/12 slope was measured
  against workers that had never been activated and never joined a community, so it recorded
  MEMBERSHIP GOSSIP rather than worker coordination — a FLOOR, which under-provisions anything
  extrapolated from it. The constant is gone. `communitiesObserved` / `workersInCommunities` are
  now read from the leader's /api/workers view, and a row whose participating-worker count falls
  short of --workers is flagged loudly, exactly like the saturation guard. See community_census()
  for what that number can and cannot prove.

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
import os
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
    # RBAC-authed clusters (the remote/cloud harness) gate the management routes on X-API-Key;
    # dev/in-JVM clusters don't. Header attached only when AETHER_API_KEY is set, so both
    # environments work unchanged — discovered against the live remote cluster, where the bare
    # request 401s (the 08-27 validation ran unauthed and could not see this).
    request = urllib.request.Request(base.rstrip("/") + path)
    api_key = os.environ.get("AETHER_API_KEY", "")

    if api_key:
        request.add_header("X-API-Key", api_key)

    with urllib.request.urlopen(request, timeout=timeout) as resp:
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

    # #728: the participation census must COUNT, never assume. The old hard-coded
    # `"communities": 1` passed every run because it never looked at anything.
    census = census_from_workers([{"nodeId": "w-1", "community": "src-a-w-0"},
                                  {"nodeId": "w-2", "community": "src-a-w-0"},
                                  {"nodeId": "w-3", "community": "src-b-w-0"}])
    check("counts DISTINCT communities, not worker rows",
          census["communitiesObserved"] == 2 and census["workersInCommunities"] == 3)

    empty = census_from_workers([])
    check("an empty worker roster reports 0 communities, not the assumed 1",
          empty["communitiesObserved"] == 0 and empty["workersInCommunities"] == 0)

    print("SELF-TEST:", "OK" if ok else "FAILED")

    return 0 if ok else 1


def census_from_workers(workers):
    """Pure parse of the /api/workers payload, so the selftest can exercise it without a cluster."""
    return {"communitiesObserved": len({w.get("community") for w in workers if w.get("community")}),
            "workersInCommunities": len(workers)}


def community_census(base):
    """Observed community/worker participation, read from the leader's /workers view.

    Replaces the hard-coded `"communities": 1` this script shipped with until #728. That constant
    read as data in every published row and was never read from anything — the instrument-illusion
    family in its purest form. It also hid the #728 defect: the 4/8/12 slope was measured against
    workers that had never been activated, and nothing in the output could show it.

    HONEST LIMITS OF THIS NUMBER, because they decide what it may be used for. `/api/workers` is
    built by iterating GovernorAnnouncementKey and expanding each community roster
    (`WorkerRoutes.buildWorkersResponse`) — and so is every other observable surface: cluster
    generation and community ownership are governor-keyed too. NO management endpoint reports the
    CommunityKey entries themselves, so a community that has been MINTED but is still FORMING (no
    governor yet) is invisible here.

    Therefore: a worker that APPEARS here is definitely participating — it is in a committed
    community roster under an announced governor. A worker that is ABSENT is either a
    non-participating node (the #728 shape) or a member of a community that has not yet elected a
    governor. Presence proves participation; absence is a flag to investigate, never by itself a
    proof of the defect. Reported as observed counts, never as an assumed topology.
    """
    try:
        workers = fetch(base, "/api/workers").get("workers", [])
    except Exception as exc:                                    # noqa: BLE001 - report, never abort a sweep
        return {"communitiesObserved": None,
                "workersInCommunities": None,
                "censusError": str(exc)}

    return census_from_workers(workers)


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
    census = community_census(bases[0])
    row = {
        "workers": args.workers,
        "cores": len(samples),
        "totalCoreMessagesPerSecond": round(total_rate, 2),
        "perCoreMessagesPerSecond": round(total_rate / len(samples), 2),
        "meanCoreCpuUsage": round(sum(cpus) / len(cpus), 4),
        "meanCoreHeapUsedBytes": int(sum(heaps) / len(heaps)),
        "anyCoreSaturated": any(s["saturated"] for s in samples),
        "samples": samples,
    }
    row.update(census)

    print(json.dumps(row, indent=2))
    if row["anyCoreSaturated"]:
        print("\nWARNING: backpressure or write failures moved during this window. This row measures "
              "congestion, not coordination cost — do not put it in the slope without saying so.",
              file=sys.stderr)

    # The participation guard (#728). A slope measured against workers that never joined a
    # community measures MEMBERSHIP GOSSIP, not worker coordination — and gossip is a FLOOR, so
    # extrapolating it under-provisions. That is exactly what #591 published before the defect was
    # found, and nothing in the row could show it. Loud, and never silently averaged.
    observed = row.get("workersInCommunities")
    if observed is None:
        print("\nWARNING: could not read /api/workers ({}). Worker participation is UNVERIFIED for "
              "this row — do not claim it measures coordination cost."
              .format(row.get("censusError")), file=sys.stderr)
    elif observed < args.workers:
        print("\nWARNING: {} of {} workers appear in a community roster. The rest are either "
              "non-participating (the #728 shape) or in a still-FORMING community. This row may be "
              "measuring membership gossip rather than worker coordination — verify before putting "
              "it in a slope, and never extrapolate it to a larger round."
              .format(observed, args.workers), file=sys.stderr)

    if args.out:
        with open(args.out, "a") as fh:
            fh.write(json.dumps(row) + "\n")

    return 0


if __name__ == "__main__":
    sys.exit(main())
