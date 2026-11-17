#!/usr/bin/env python3
"""TLA+ trace validation of SharedPools: each recorded event sequence must be a behaviour of the extracted spec.

Usage: trace.py --spec <dir with SharedPools.tla> --traces <dir from SharedPoolsTraceTest> [--jar tla2tools.jar]

For each trace-<n>.txt it writes SharedPoolsTrace.tla, which steps the spec action named by each event (constrained by
the outcome the event recorded) and lets the unobservable steps (ConcurrentHashMap's count publication, the no-op close
of a failed pool) happen silently. TLC reports the invariant `Unmatched` (i < Len(Log)) violated exactly when the whole
log is matched: ACCEPTED. When it is not, the longest matched prefix is found by re-running with a shorter bound, and
the first event the spec cannot take is printed: REJECTED. Exit 1 if any trace is rejected.

Two guards keep acceptance from being vacuous: a trace with no events is REJECTED (a prefix of length 0 is a prefix of
every behaviour), and a matched trace must END quiescent (no close still pending), so a missing Close_Pool note, which
leaves the closing set non-empty, is REJECTED rather than read as a shorter prefix. The recorded run must therefore
finish every close before the sequence is written (SharedPoolsTraceTest awaits closeAll and the acquirer).

The model constants of the trace configuration are the ones of the spec's own `code` configuration
(<spec dir>/SharedPools_code.cfg, written by extract.py), so a constant added to or dropped from the model cannot
leave this script stale; TRACE_BOUNDS only widens bounds, and only for constants the configuration has.
"""
import argparse
import glob
import os
import re
import shutil
import subprocess
import sys
import tempfile

NAMES = {'a': 'A', 'b': 'B', 'k1': 'K1', 'k2': 'K2'}

TRACE_MODULE = r'''---------------------------- MODULE SharedPoolsTrace ----------------------------
EXTENDS SharedPools, Sequences, TLC
CONSTANTS A, B, K1, K2, Bound

VARIABLE i

Log == << %(log)s >>

TraceInit == Init /\ i = 0

E == Log[i + 1]

AdmitOutcome(t) == IF closed THEN "refused"
                   ELSE IF entry[t[2]].pool = 0 THEN "insert"
                   ELSE IF entry[t[2]].holders[t[1]] # 0 THEN "join"
                   ELSE "add"

FillOutcome(t) == IF SlotFails(t) THEN "failed" ELSE "view"

Logged == /\ i < Len(Log)
          /\ i' = i + 1
          /\ CASE E.a = "Acq_Admit" -> Acq_Admit(E.t) /\ E.r = AdmitOutcome(E.t)
               [] E.a = "Acq_Post" -> /\ Acq_Post(E.t)
                                      /\ E.r = IF closed /\ entry[E.t[2]].pool # 0 THEN "drained" ELSE "nodrain"
               [] E.a = "Acq_Provision" -> Acq_Provision(E.t)
               [] E.a = "Acq_Skip" -> Acq_Skip(E.t)
               [] E.a = "Evict" -> Evict(E.p)
               [] E.a = "Resolve" -> Resolve(E.p) /\ poolSt'[E.p] = E.r
               [] E.a = "Acq_Fill" -> Acq_Fill(E.t) /\ E.r = FillOutcome(E.t)
               [] E.a = "Close_Pool" -> Close_Pool(E.p) /\ poolSt[E.p] = "open"
               [] E.a = "CA_Flag" -> CA_Flag
               [] E.a = "CA_Check" -> CA_Check /\ ca' = E.r
               [] E.a = "CA_Drain" -> /\ CA_Drain(E.k)
                                      /\ E.r = IF entry[E.k].pool # 0 THEN "captured" ELSE "empty"
               [] OTHER -> FALSE

\* Steps with no event: CHM publishes an insert's count; a captured failed pool's close does nothing.
Silent == /\ i' = i
          /\ \/ \E t \in Threads : Acq_Publish(t)
             \/ \E p \in Pools : poolSt[p] = "failed" /\ Close_Pool(p)

TraceNext == Logged \/ Silent

\* Prefix match: violated when the first Bound events were all taken.
Unmatched == i < Bound
\* Full match: also quiescent, no close still pending (every Close_Pool the run performed was recorded).
UnmatchedFinal == ~(i = Bound /\ closing = {})
=============================================================================
'''

TRACE_CONSTANTS = ('A = a', 'B = b', 'K1 = k1', 'K2 = k2')

# Bounds widened for trace matching (a recorded run may hold more than the model-check bound allows); a name the
# model configuration does not carry is ignored, never added.
TRACE_BOUNDS = {'MaxFaults': '2'}

TRACE_TAIL = '''INIT TraceInit
NEXT TraceNext
INVARIANT %(invariant)s
'''

DECLARED = re.compile(r'^CONSTANTS?\s+([A-Za-z0-9_,\s]+?)\s*$', re.M)


def model_constants(spec):
    """The constant assignments of the spec's `code` configuration, and the constants SharedPools.tla declares."""
    text = open(os.path.join(spec, 'SharedPools.tla')).read()
    declared = [n.strip() for m in DECLARED.finditer(text) for n in m.group(1).split(',') if n.strip()]
    cfg = os.path.join(spec, 'SharedPools_code.cfg')
    if not os.path.exists(cfg):
        sys.exit(f'trace: {cfg} not found; run tools/tla/check.sh or extract.py first')
    keep, taking = [], False
    for line in open(cfg).read().split('\n'):
        word = line.split()[0] if line.split() else ''
        if word in ('CONSTANT', 'CONSTANTS'):
            taking = True
        elif word and not line[0].isspace():
            taking = False
        if taking and line.strip():
            keep.append(line)
    assigned = {re.sub(r'^CONSTANTS?\s+', '', l).strip().split()[0] for l in keep}
    missing = [n for n in declared if n not in assigned]
    if missing:
        sys.exit(f'trace: SharedPools_code.cfg does not assign {", ".join(missing)} declared by SharedPools.tla')
    for name, value in TRACE_BOUNDS.items():
        keep = [re.sub(r'(\b' + name + r'\s*=\s*).*', r'\g<1>' + value, l) for l in keep]
    return '\n'.join(keep) + '\n'


def record(line):
    f = line.split()
    rec = {'a': f[0], 't': '<<A, K1>>', 'p': '0', 'k': 'K1', 'r': ''}
    if f[0].startswith('Acq_'):
        rec['t'] = f'<<{NAMES[f[1]]}, {NAMES[f[2]]}>>'
        rec['r'] = f[3] if len(f) > 3 else ''
    elif f[0] in ('Resolve', 'Close_Pool', 'Evict'):
        rec['p'] = f[1]
        rec['r'] = f[2] if len(f) > 2 else ''
    elif f[0] == 'CA_Drain':
        rec['k'] = NAMES[f[1]]
        rec['r'] = f[2]
    elif f[0] == 'CA_Check':
        rec['r'] = f[1]
    return '[a |-> "%(a)s", t |-> %(t)s, p |-> %(p)s, k |-> %(k)s, r |-> "%(r)s"]' % rec


def tlc(work, jar, bound, constants, final):
    """True when TLC finds the invariant violated, i.e. the first `bound` events were matched (and, for `final`, the
    run ended quiescent)."""
    invariant = 'UnmatchedFinal' if final else 'Unmatched'
    with open(os.path.join(work, 'SharedPoolsTrace.cfg'), 'w') as f:
        f.write(constants + ''.join(f'CONSTANT {c}\n' for c in TRACE_CONSTANTS)
                + f'CONSTANT Bound = {bound}\n' + TRACE_TAIL % {'invariant': invariant})
    out = subprocess.run(['java', '-cp', jar, 'tlc2.TLC', '-workers', '1', '-deadlock', '-cleanup',
                          '-config', 'SharedPoolsTrace.cfg', 'SharedPoolsTrace.tla'],
                         cwd=work, capture_output=True, text=True).stdout
    if f'Invariant {invariant} is violated' in out:
        return True
    if 'Model checking completed. No error has been found' in out:
        return False
    sys.exit(f'trace: TLC failed unexpectedly:\n{out[-3000:]}')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--spec', required=True)
    ap.add_argument('--traces', required=True)
    ap.add_argument('--jar', default=os.path.expanduser('~/tools/tla/tla2tools.jar'))
    a = ap.parse_args()
    counts = {}
    cpath = os.path.join(a.traces, 'counts.tsv')
    if os.path.exists(cpath):
        counts = dict(l.split('\t') for l in open(cpath).read().split('\n') if l)
    files = sorted(glob.glob(os.path.join(a.traces, 'trace-*.txt')), key=lambda p: int(re.findall(r'\d+', p)[-1]))
    if not files:
        sys.exit('trace: no trace-*.txt files')
    constants = model_constants(a.spec)
    rejected = 0
    for path in files:
        lines = [l for l in open(path).read().split('\n') if l.strip()]
        if not lines:
            rejected += 1
            print(f'REJECTED {os.path.basename(path)} events=0: an empty trace matches every behaviour and proves nothing')
            continue
        work = tempfile.mkdtemp(prefix='tlatrace-')
        shutil.copy(os.path.join(a.spec, 'SharedPools.tla'), work)
        with open(os.path.join(work, 'SharedPoolsTrace.tla'), 'w') as f:
            f.write(TRACE_MODULE % {'log': ',\n        '.join(record(l) for l in lines)})
        name = os.path.basename(path)
        ok = tlc(work, a.jar, len(lines), constants, True)
        if ok:
            print(f'ACCEPTED {name} events={len(lines)} rounds={counts.get(name, "?")}')
        else:
            rejected += 1
            n = len(lines)
            while n > 0 and not tlc(work, a.jar, n, constants, False):
                n -= 1
            if n == len(lines):
                why = 'every event matched but the run does not end quiescent (a close the spec still owes was not recorded)'
            else:
                why = f'matched {n}, the spec cannot take event {n + 1}: "{lines[n]}"'
            print(f'REJECTED {name} events={len(lines)} rounds={counts.get(name, "?")}: {why}  trace: {" | ".join(lines)}')
        shutil.rmtree(work)
    print(f'trace: {len(files) - rejected} accepted, {rejected} rejected')
    sys.exit(1 if rejected else 0)


if __name__ == '__main__':
    main()
