# SPDX-License-Identifier: BUSL-1.1
# Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
# Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
# See LICENSE in the repository root for full terms.
"""summary.py <jsonl>... : compact class x config table of mean committed mutations per event (raw, idle-netted in parentheses when idle>0.5/s)."""
import sys, collections
from analyze2 import load, summarize
CONF={"10":"N=10 (2x5)","25":"N=25 (5x5)","50":"N=50 (2x25)","100":"N=100 (4x25) default SWIM","100r":"N=100 (4x25) raised SWIM","50r":"N=50 (2x25) raised SWIM"}
order=["10","25","50","50r","100","100r"]
out=summarize(load(sys.argv[1:]))
classes=collections.OrderedDict()
for (c,n),v in out.items(): classes.setdefault(c,{})[n]=v
print("| class | "+" | ".join(CONF[o] for o in order)+" |")
print("|---|"+"---|"*len(order))
for c,byn in classes.items():
    cells=[]
    for o in order:
        if o not in byn: cells.append("-"); continue
        recs=byn[o][0]
        raw=[r_["put.total"] if "put.total" in r_ else 0 for _,r_,_ in recs]
        raw=[(r_.get("put.total",0)+r_.get("remove.total",0)) for _,r_,_ in recs]
        net=[(n_.get("put.total",0)+n_.get("remove.total",0)) for _,_,n_ in recs]
        gov=[r_.get("put:GovernorAnnouncementKey",0) for _,r_,_ in recs]
        cells.append(f"{sum(raw)/len(raw):.0f} [{min(raw)}-{max(raw)}] net {sum(net)/len(net):.0f}; gov {sum(gov)/len(gov):.0f}; n={len(raw)}")
    print(f"| {c} | "+" | ".join(cells)+" |")
