# SPDX-License-Identifier: BUSL-1.1
# Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
# Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
# See LICENSE in the repository root for full terms.
"""M-1 counting analysis. usage: analyze2.py <jsonl>... ; prints per (class, N) tables.
net(family) = raw - idleRate(family) * windowMs, idleRate pooled over every idle bracket and idle-class window at that (label-group, N)."""
import json, sys, collections, statistics as st, math

def load(paths):
    rows=[]
    for p in paths:
        for line in open(p):
            line=line.strip()
            if line:
                try: rows.append(json.loads(line))
                except Exception: pass
    return rows

def fam_counts(d):
    return {k:v for k,v in d.items() if k.startswith(("put:","remove:"))}

def idle_rates(samples):
    """per-family idle rate (count/s) and sd over idle windows (brackets + idle class)"""
    wins=[]
    clean=("control-put","control-remove","control-refused-tx","join")
    for r in samples:
        if r["class"]=="idle":
            wins.append((r["windowMs"],r["raw"]))
        if r["class"] in clean:
            wins.append((r["idleBefore"]["ms"],r["idleBefore"]["counts"]))
            if r["class"].startswith("control"): wins.append((r["idleAfter"]["ms"],r["idleAfter"]["counts"]))
    keys=set()
    for _,c in wins: keys|=set(c)
    rate={}; sd={}
    for k in keys:
        xs=[c.get(k,0)/(ms/1000) for ms,c in wins if ms>0]
        rate[k]=st.mean(xs); sd[k]=st.pstdev(xs) if len(xs)>1 else 0.0
    return rate, sd, len(wins)

RAISED={"t1r","t2r","t3r","t4r","s0e","n200r","r50","t2g"}
def key(r):
    return f"{r['n']}r" if r["label"] in RAISED else str(r["n"])

def summarize(rows):
    samples=[r for r in rows if r["kind"]=="sample"]
    by=collections.defaultdict(list)
    for r in samples: by[key(r)].append(r)
    out=collections.OrderedDict()
    for n,rs in sorted(by.items(), key=lambda kv:(int(kv[0].rstrip("r")), kv[0].endswith("r"))):
        rate,sd,nw=idle_rates(rs)
        cls=collections.defaultdict(list)
        for r in rs: cls[r["class"]].append(r)
        for c,xs in cls.items():
            recs=[]
            for r in xs:
                w=r["windowMs"]/1000
                raw=r["raw"]
                net={k:raw.get(k,0)-rate.get(k,0)*w for k in set(raw)|set(rate)}
                recs.append((r,raw,net))
            out[(c,n)]=(recs,rate,sd,nw)
    return out

def mean(xs): return sum(xs)/len(xs) if xs else float("nan")

def show(out, topk=4):
    out={(c,str(n)):v for (c,n),v in out.items()}
    for (c,n),(recs,rate,sd,nw) in out.items():
        mutraw=[raw.get("put.total",0)+raw.get("remove.total",0) for _,raw,_ in recs]
        mutnet=[net.get("put.total",0)+net.get("remove.total",0) for _,_,net in recs]
        acc=[raw.get("tx.accepted",0) for _,raw,_ in recs]; ref=[raw.get("tx.refused",0) for _,raw,_ in recs]
        slots=[raw.get("engine.slots",0) for _,raw,_ in recs]; bts=[raw.get("put.bytes",0) for _,raw,_ in recs]
        win=[r["windowMs"]/1000 for r,_,_ in recs]
        fam=collections.Counter()
        for _,raw,_ in recs:
            for k,v in fam_counts(raw).items(): fam[k]+=v/len(recs)
        top=", ".join(f"{k}={v:.1f}" for k,v in fam.most_common(topk))
        setd=[r["settled"] for r,_,_ in recs]
        print(f"{c:28s} N={n:<5s} reps={len(recs)} mutRaw={mutraw} mutNet={[round(x,1) for x in mutnet]} txAcc={acc} txRef={ref} slots={slots} bytes={bts} win_s={[round(w) for w in win]} settled={setd} top: {top}")
        w=max(win); 
        if c in ("join","idle"): print(f"    idleRate(put.total)={rate.get('put.total',0):.3f}/s sd={sd.get('put.total',0):.3f} from {nw} windows")

if __name__=="__main__":
    show(summarize(load(sys.argv[1:])))
