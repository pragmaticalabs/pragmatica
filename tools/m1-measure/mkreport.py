# SPDX-License-Identifier: BUSL-1.1
# Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
# Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
# See LICENSE in the repository root for full terms.
"""mkreport.py <jsonl>...  -> markdown tables (counts vs N per class) + log-log slope per class.
Per (class, N): raw mean [min-max] committed mutations on the reference core, net mean (idle-rate subtracted, per family),
net excluding GovernorAnnouncementKey (the idle-noisy family), tx accepted/refused, slots, put bytes; top families."""
import sys, math, collections, json
import os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from analyze2 import load, summarize, fam_counts

GOV="put:GovernorAnnouncementKey"
def stats(recs, f):
    xs=[f(raw,net) for _,raw,net in recs]
    return sum(xs)/len(xs), min(xs), max(xs), len(xs)

def fit(points):
    pts=[(math.log(n),math.log(v)) for n,v in points if v>0.5]
    if len(pts)<2: return None
    mx=sum(x for x,_ in pts)/len(pts); my=sum(y for _,y in pts)/len(pts)
    sxx=sum((x-mx)**2 for x,_ in pts)
    if sxx==0: return None
    b=sum((x-mx)*(y-my) for x,y in pts)/sxx
    return b

def label(b):
    if b is None: return "n/a"
    if b<0.3: return "constant"
    if b<0.7: return "sublinear"
    if b<=1.3: return "linear"
    return "worse than linear"

def main(paths):
    out=summarize(load(paths))
    classes=collections.OrderedDict()
    for (c,n),v in out.items(): classes.setdefault(c,{})[n]=v
    ns=sorted({n for (c,n) in out}, key=lambda k:(int(k.rstrip("r")), k.endswith("r")))
    for c,byn in classes.items():
        base=c.split("(")[0]
        print(f"\n#### {c}\n")
        print("| metric | "+" | ".join((f"N={n[:-1]} (raised SWIM)" if n.endswith("r") else f"N={n}") for n in ns)+" |")
        print("|---|"+"---|"*len(ns))
        rows=collections.OrderedDict()
        def cell(n,fn):
            if n not in byn: return "-"
            m,lo,hi,k=stats(byn[n][0],fn)
            return f"{m:.1f} [{lo:.0f}-{hi:.0f}] n={k}"
        spec=[("committed mutations, raw", lambda raw,net: raw.get("put.total",0)+raw.get("remove.total",0)),
              ("committed mutations, net of idle", lambda raw,net: net.get("put.total",0)+net.get("remove.total",0)),
              ("... excluding GovernorAnnouncementKey, net", lambda raw,net: (net.get("put.total",0)+net.get("remove.total",0))-net.get(GOV,0)),
              ("leader tx accepted (raw)", lambda raw,net: raw.get("tx.accepted",0)),
              ("leader tx refused (raw)", lambda raw,net: raw.get("tx.refused",0)),
              ("slots completed (raw)", lambda raw,net: raw.get("engine.slots",0)),
              ("value bytes put (raw)", lambda raw,net: raw.get("put.bytes",0)),
              ("window s", None)]
        for name,fn in spec:
            if fn is None:
                cells=[ (f"{sum(r['windowMs'] for r,_,_ in byn[n][0])/len(byn[n][0])/1000:.0f}" if n in byn else "-") for n in ns]
            else:
                cells=[cell(n,fn) for n in ns]
            print(f"| {name} | "+" | ".join(cells)+" |")
        # slope on net-excl-gov and on net total
        for mode,sel in (("default SWIM",lambda n: not n.endswith("r")),("raised SWIM",lambda n: n.endswith("r"))):
            for name,fn in (("net total",spec[1][1]),("net excl. GovernorAnnouncementKey",spec[2][1])):
                pts=[(int(n.rstrip("r")),stats(byn[n][0],fn)[0]) for n in ns if n in byn and sel(n)]
                b=fit(pts)
                print(f"\nslope [{mode}] ({name}): " + (f"exponent {b:.2f} over N={[n for n,_ in pts]} -> **{label(b)}**" if b is not None else "n/a (need >= 2 N with positive counts)"))
        top=collections.Counter()
        nmax=max(byn)
        for _,raw,net in byn[nmax][0]:
            for k,v in net.items():
                if k.startswith(("put:","remove:")): top[k]+=v/len(byn[nmax][0])
        print(f"\ntop families at N={nmax} (net mean per event): "+", ".join(f"{k}={v:.1f}" for k,v in top.most_common(3)))
        # per-family table at all N for families with >= 1 mean anywhere
        fams=collections.defaultdict(dict)
        for n in ns:
            if n not in byn: continue
            acc=collections.Counter()
            for _,raw,net in byn[n][0]:
                for k,v in net.items():
                    if k.startswith(("put:","remove:")): acc[k]+=v/len(byn[n][0])
            for k,v in acc.items(): fams[k][n]=v
        print("\n| family (net mean/event) | "+" | ".join((f"N={n[:-1]} (raised SWIM)" if n.endswith("r") else f"N={n}") for n in ns)+" |")
        print("|---|"+"---|"*len(ns))
        for k,d in sorted(fams.items(), key=lambda kv:-max(kv[1].values())):
            if max(d.values())<0.75: continue
            print(f"| {k} | "+" | ".join(f"{d[n]:.1f}" if n in d else "-" for n in ns)+" |")

if __name__=="__main__":
    main(sys.argv[1:])
