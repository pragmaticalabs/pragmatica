# SPDX-License-Identifier: BUSL-1.1
# Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
# Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
# See LICENSE in the repository root for full terms.
import sys, collections
from analyze2 import load, summarize, fam_counts
out=summarize(load(sys.argv[1:]))
classes=collections.OrderedDict()
for (c,n),v in out.items(): classes.setdefault(c,{})[n]=v
order=["10","25","50","50r","100","100r"]
print("| class | config | top three families (mean raw commits per event) |")
print("|---|---|---|")
for c,byn in classes.items():
    if c.startswith("control") : continue
    for o in order:
        if o not in byn: continue
        recs=byn[o][0]; acc=collections.Counter()
        for _,raw,_ in recs:
            for k,v in fam_counts(raw).items(): acc[k]+=v/len(recs)
        print(f"| {c} | N={o} | "+", ".join(f"{k.split(':')[1]} ({k.split(':')[0]}) {v:.0f}" for k,v in acc.most_common(3))+" |")
