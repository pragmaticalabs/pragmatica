#!/usr/bin/env python3
"""Arm X/K cost estimator. An ESTIMATE from list prices; it never calls the Hetzner API.

Prices: EUR per hour, excluding VAT, the EU (eu-central: NBG1/HEL1) price, read from hetzner.com on 2026-11-01:
  https://www.hetzner.com/cloud/regular-performance  (CPX, "Price incl. IPv4")
  https://www.hetzner.com/cloud/general-purpose      (CCX, dedicated vCPU)
  https://www.hetzner.com/cloud/cost-optimized       (CX)
These follow Hetzner's 2026 price changes (the June 15, 2026 round applies to new orders). The same pages listed every type's Create
button as "currently unavailable" when read, and placed CPX62/CCX in NBG1 and HEL1 only. Re-read both before a run.

Billing: each server is billed per STARTED hour, so the estimate rounds the run up to whole hours per server. The watchdog total is a
lower bound (memory cloudbb-watchdog-total-is-a-lower-bound): the owner reads the console before and after.
"""
import argparse
import json
import math

PRICES_EUR_PER_HOUR = {
    "cpx22": 0.0320, "cpx32": 0.0577, "cpx42": 0.1122, "cpx52": 0.1618, "cpx62": 0.2091,
    "ccx13": 0.0697, "ccx23": 0.1386, "ccx33": 0.2227, "ccx43": 0.4431,
    "cx23": 0.0096, "cx33": 0.0144, "cx43": 0.0264, "cx53": 0.0481,
}
VCPU = {"cpx22": 2, "cpx32": 4, "cpx42": 8, "cpx52": 12, "cpx62": 16, "ccx13": 2, "ccx23": 4, "ccx33": 8, "ccx43": 16,
        "cx23": 2, "cx33": 4, "cx43": 8, "cx53": 16}
DEDICATED = {"ccx13", "ccx23", "ccx33", "ccx43"}
PRICES_READ = "2026-11-01, hetzner.com cloud product pages, EUR excl. VAT, eu-central"


def estimate(core_type, cores, worker_type, worker_hosts, hours, cap_usd, usd_per_eur, limits):
    for t in (core_type, worker_type):
        if t not in PRICES_EUR_PER_HOUR:
            raise SystemExit(f"estimate: no list price recorded for '{t}'; add it from hetzner.com with the date read")
    per_hour = cores * PRICES_EUR_PER_HOUR[core_type] + worker_hosts * PRICES_EUR_PER_HOUR[worker_type]
    billed_hours = math.ceil(hours)
    total = per_hour * billed_hours
    cap_eur = cap_usd / usd_per_eur
    shared = sum(n * VCPU[t] for t, n in ((core_type, cores), (worker_type, worker_hosts)) if t not in DEDICATED)
    dedicated = sum(n * VCPU[t] for t, n in ((core_type, cores), (worker_type, worker_hosts)) if t in DEDICATED)
    servers = cores + worker_hosts
    problems = []
    if servers > limits["instances"]:
        problems.append(f"{servers} servers > instance limit {limits['instances']}")
    if shared > limits["sharedVcpu"]:
        problems.append(f"{shared} shared vCPU > limit {limits['sharedVcpu']}")
    if dedicated > limits["dedicatedVcpu"]:
        problems.append(f"{dedicated} dedicated vCPU > limit {limits['dedicatedVcpu']}")
    if total > cap_eur:
        problems.append(f"EUR {total:.2f} > cap EUR {cap_eur:.2f} (USD {cap_usd} at {usd_per_eur} USD/EUR)")
    return {"pricesRead": PRICES_READ, "coreType": core_type, "cores": cores, "workerType": worker_type, "workerHosts": worker_hosts,
            "eurPerHour": round(per_hour, 4), "hours": hours, "billedHours": billed_hours, "estimateEur": round(total, 2),
            "capUsd": cap_usd, "usdPerEur": usd_per_eur, "capEur": round(cap_eur, 2), "maxWholeHoursUnderCap": int(cap_eur // per_hour),
            "servers": servers, "sharedVcpu": shared, "dedicatedVcpu": dedicated, "limits": limits, "problems": problems,
            "basis": "lower bound: excludes traffic overage, snapshots, volumes; the watchdog/console is the arbiter"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--core-type", default="ccx13")
    ap.add_argument("--cores", type=int, default=5)
    ap.add_argument("--worker-type", default="cpx62")
    ap.add_argument("--worker-hosts", type=int, default=7)
    ap.add_argument("--hours", type=float, default=4.0)  # a 3 h run plus a 1 h margin
    ap.add_argument("--cap-usd", type=float, default=30.0)
    # [unverified: exchange rate not read from a source; pass the day's rate]
    ap.add_argument("--usd-per-eur", type=float, default=1.16)
    # memory cloud-run-build-host-swap-to-dedicated (2026-09-30) and hetzner-capacity-constraint (2026-08-27); the CTO re-reads the
    # "aether testing" project's limits in the console, which is the only authority
    ap.add_argument("--limit-instances", type=int, default=20)
    ap.add_argument("--limit-shared-vcpu", type=int, default=120)
    ap.add_argument("--limit-dedicated-vcpu", type=int, default=12)
    ap.add_argument("--json", action="store_true")
    a = ap.parse_args()
    e = estimate(a.core_type, a.cores, a.worker_type, a.worker_hosts, a.hours, a.cap_usd, a.usd_per_eur,
                 {"instances": a.limit_instances, "sharedVcpu": a.limit_shared_vcpu, "dedicatedVcpu": a.limit_dedicated_vcpu})
    if a.json:
        print(json.dumps(e))
    else:
        print(f"prices: {e['pricesRead']}")
        print(f"{a.cores} x {a.core_type} + {a.worker_hosts} x {a.worker_type} = EUR {e['eurPerHour']}/h; "
              f"{e['billedHours']} billed h -> EUR {e['estimateEur']} (cap EUR {e['capEur']}, {e['maxWholeHoursUnderCap']} whole hours fit)")
        print(f"servers {e['servers']}, shared vCPU {e['sharedVcpu']}, dedicated vCPU {e['dedicatedVcpu']} against limits {e['limits']}")
        for p in e["problems"]:
            print(f"PROBLEM: {p}")
    raise SystemExit(1 if e["problems"] else 0)


if __name__ == "__main__":
    main()
