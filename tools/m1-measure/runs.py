# SPDX-License-Identifier: BUSL-1.1
# Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
# Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
# See LICENSE in the repository root for full terms.
import json,glob,collections
print("| file | N | SWIM | first sample UTC | last sample UTC | samples | classes (samples) |")
print("|---|---|---|---|---|---|---|")
RAISED={"t1r","t2r","t3r","t4r","s0e","n200r","r50","t2g"}
for f in sorted(glob.glob("data/*.jsonl")):
    if "dbg" in f: continue
    rows=[json.loads(l) for l in open(f) if l.strip()]
    s=[r for r in rows if r["kind"]=="sample"]
    if not s: continue
    cl=collections.Counter(r["class"] for r in s)
    print(f"| {f.split('/')[-1]} | {s[0]['n']} | {'raised' if s[0]['label'] in RAISED else 'default'} | {s[0]['startUtc'][:19]}Z | {s[-1]['utc'][:19]}Z | {len(s)} | "+", ".join(f"{k} {v}" for k,v in cl.items())+" |")
