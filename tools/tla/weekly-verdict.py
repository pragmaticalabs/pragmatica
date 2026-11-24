#!/usr/bin/env python3
"""Verdict of one weekly host-only TLC run (#3659), from the two files the run leaves on the host.

Usage: weekly-verdict.py <run-host.log> <results.tsv>     prints one line: STATUS<TAB>rows<TAB>red-rows<TAB>reason
  STATUS is GREEN, RED or NOT-GREEN. Nothing else is ever printed, and the exit code is 0 for all three (the caller reads the line).

The rule is deliberately stricter than run-host.sh: its exit 0 is NOT trusted, because run-host.sh exits 0 when no row is over the
hosted limit ("nothing to run"), and a run in which nothing ran is never GREEN (the #3653 rule). GREEN needs ALL of:
  - the run-host summary line (`run-host: profile=full rows=N green=G red=R not-started=S ... GREEN`) is present and says GREEN,
  - N >= 1 and G == N,
  - the results file holds exactly N evidence rows with verdict OK and none with another verdict.
RED: any evidence row whose verdict is not OK, any `NOT-STARTED <row>` line, any `run-host: RED <row>:` line; every such row is named
as <module>/<variant>. Anything else that is not GREEN is NOT-GREEN with a reason: zero-rows, no-summary, inconsistent, no-evidence.
"""
import re
import sys

SUMMARY = re.compile(r'^run-host: profile=\S+ rows=(\d+) green=(\d+) red=(\d+) not-started=(\d+) wall=\S+ (GREEN|RED)\s*$')
NOT_STARTED = re.compile(r'^NOT-STARTED (\S+/\S+)')
ROW_RED = re.compile(r'^run-host: RED (\S+/\S+):')


def evidence(results_text):
    """(ok_count, [module/variant of every row whose verdict is not OK]) from a results.tsv text; header skipped."""
    ok, bad = 0, []
    lines = [l for l in results_text.splitlines() if l.strip()]
    if not lines:
        return 0, []
    head = lines[0].split('\t')
    mi, vi, ri = head.index('module'), head.index('variant'), head.index('verdict')
    for l in lines[1:]:
        f = l.split('\t')
        if f[ri] == 'OK':
            ok += 1
        else:
            bad.append(f'{f[mi]}/{f[vi]}')
    return ok, bad


def verdict(log_text, results_text):
    """(status, rows, red_rows, reason) -- a pure function of the two texts."""
    ok, bad = evidence(results_text)
    summary, named = None, []
    for l in log_text.splitlines():
        m = SUMMARY.match(l)
        if m:
            summary = m
        for rx in (NOT_STARTED, ROW_RED):
            m = rx.match(l)
            if m:
                named.append(m.group(1))
    red = list(dict.fromkeys(bad + named))
    if red:
        return 'RED', ok + len(bad), red, 'row-red'
    if summary is None:
        return 'NOT-GREEN', 0, [], 'zero-rows' if 'nothing to run' in log_text else 'no-summary'
    rows, green, nred, nstart, word = int(summary[1]), int(summary[2]), int(summary[3]), int(summary[4]), summary[5]
    if word == 'RED' or nred or nstart:
        return 'RED', rows, ['unnamed'], 'summary-red-without-a-named-row'
    if rows == 0:
        return 'NOT-GREEN', 0, [], 'zero-rows'
    if ok == 0:
        return 'NOT-GREEN', rows, [], 'no-evidence'
    if green != rows or ok != rows:
        return 'NOT-GREEN', rows, [], 'inconsistent'
    return 'GREEN', rows, [], ''


def read(path):
    try:
        with open(path) as f:
            return f.read()
    except OSError:
        return ''


if __name__ == '__main__':
    if len(sys.argv) != 3:
        sys.exit('usage: weekly-verdict.py <run-host.log> <results.tsv>')
    s, n, r, why = verdict(read(sys.argv[1]), read(sys.argv[2]))
    print('\t'.join([s, str(n), ','.join(r), why]))
