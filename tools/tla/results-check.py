#!/usr/bin/env python3
"""Check the committed model-run evidence rows (tools/tla/results.tsv, written by run.sh) against the code they cover.

A row names the commit the run was made at. It is evidence for a PR only if that commit is part of the PR's history,
so every row must be well formed and its commit must be an ancestor of (or equal to) the head. Rows are checked for the
PR that adds them: with --base, only the rows that the diff merge-base(base, head)..head adds to the file; without --base,
every row of the file at head. (A squash merge rewrites the PR's commits, so rows already merged are history, not the
subject of a later PR.)

Red (exit 1, one `file:line: RED ...` per row): a header other than run.sh's; a row that is not 13 tab-separated columns or
has a malformed value; a commit that is not in the repository or is not an ancestor of head; a row made on a dirty tree
(its commit does not name the code that ran); a commit recorded as `none` (the run was outside a git repository).
Exit 2: an unusable invocation (unknown revision, missing file).

Usage: results-check.py [--file tools/tla/results.tsv] [--base REV] [--head REV] [--repo DIR]
"""
import argparse
import re
import subprocess
import sys

HEADER = 'commit\tdirty\tdate\tprofile\tmodule\tvariant\texpect\tactual\tviolated\tdistinct\ttlc\tverdict\tconstants'
COLS = HEADER.split('\t')
VALID = {
    'commit': re.compile(r'^(?:[0-9a-f]{40}|none)$'),
    'dirty': re.compile(r'^(?:clean|dirty|unknown)$'),
    'date': re.compile(r'^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$'),
    'profile': re.compile(r'^(?:small|full|sim)$'),
    'module': re.compile(r'^[A-Za-z][A-Za-z0-9_]*$'),
    'variant': re.compile(r'^[A-Za-z][A-Za-z0-9_]*$'),
    'expect': re.compile(r'^(?:pass|fail)$'),
    'actual': re.compile(r'^(?:pass|fail|error)$'),
    'violated': re.compile(r'^(?:[A-Za-z][A-Za-z0-9_]*)?$'),
    'distinct': re.compile(r'^\d*$'),
    'tlc': re.compile(r'^[^\t]*$'),
    'verdict': re.compile(r'^(?:OK|RED|ERROR)$'),
    'constants': re.compile(r'^[^\t]*$'),
}


class Error(Exception):
    pass


def git(repo, *args, ok=(0,)):
    p = subprocess.run(['git', '-C', repo] + list(args), capture_output=True, text=True)
    if p.returncode not in ok:
        raise Error(f'git {" ".join(args)}: {p.stderr.strip()}')
    return p


def rows_to_check(repo, path, base, head):
    """[(line number in the head version of the file, text)] of the rows to check; the header is returned as line 1."""
    p = git(repo, 'show', f'{head}:{path}', ok=(0, 128))
    if p.returncode != 0:
        if base is not None:
            return None   # the PR has no results file: nothing was added
        raise Error(f'{path} does not exist at {head}')
    lines = p.stdout.split('\n')
    if lines and lines[-1] == '':
        lines.pop()
    numbered = list(enumerate(lines, 1))
    if base is None:
        return numbered
    mb = git(repo, 'merge-base', base, head).stdout.strip()
    old = git(repo, 'show', f'{mb}:{path}', ok=(0, 128))
    seen = set(old.stdout.split('\n')) if old.returncode == 0 else set()
    # a row counts as added by the PR when its exact text is not in the file at the merge base
    return [(n, l) for n, l in numbered if n == 1 or l not in seen]


def check(repo, path, base, head):
    git(repo, 'rev-parse', '--verify', '--quiet', f'{head}^{{commit}}')
    head_sha = git(repo, 'rev-parse', f'{head}^{{commit}}').stdout.strip()
    sel = rows_to_check(repo, path, base, head)
    if sel is None:
        return [], 0
    red = []
    if not sel or sel[0][1] != HEADER:
        return [f'{path}:1: RED the header is not the evidence-row header ({HEADER.replace(chr(9), " ")})'], 0
    verdicts = {}
    count = 0
    for n, text in sel[1:]:
        if text.strip() == '':
            continue
        count += 1
        cells = text.split('\t')
        if len(cells) != len(COLS):
            red.append(f'{path}:{n}: RED row has {len(cells)} columns, expected {len(COLS)}')
            continue
        row = dict(zip(COLS, cells))
        bad = [c for c in COLS if not VALID[c].match(row[c])]
        if bad:
            red.append(f'{path}:{n}: RED malformed value in column {", ".join(bad)}')
            continue
        c = row['commit']
        if c == 'none':
            red.append(f'{path}:{n}: RED {row["module"]}/{row["variant"]}: commit is "none": the run was made outside a git repository')
            continue
        if row['dirty'] != 'clean':
            red.append(f'{path}:{n}: RED {row["module"]}/{row["variant"]}: the run was on a {row["dirty"]} tree, so commit {c[:10]} does not name the code that ran')
            continue
        if c not in verdicts:
            if git(repo, 'cat-file', '-e', f'{c}^{{commit}}', ok=(0, 1, 128)).returncode != 0:
                verdicts[c] = 'is not a commit of this repository'
            elif git(repo, 'merge-base', '--is-ancestor', c, head_sha, ok=(0, 1)).returncode != 0:
                verdicts[c] = f'is not an ancestor of {head_sha[:10]}'
            else:
                verdicts[c] = None
        if verdicts[c]:
            red.append(f'{path}:{n}: RED {row["module"]}/{row["variant"]}: commit {c[:10]} {verdicts[c]}')
    return red, count


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--file', default='tools/tla/results.tsv')
    ap.add_argument('--base')
    ap.add_argument('--head', default='HEAD')
    ap.add_argument('--repo', default='.')
    a = ap.parse_args()
    try:
        red, count = check(a.repo, a.file, a.base, a.head)
    except Error as e:
        print(f'results-check: ERROR {e}', file=sys.stderr)
        sys.exit(2)
    for l in red:
        print(l)
    print(f'results-check: {"RED" if red else "GREEN"} {len(red)} bad of {count} rows checked in {a.file}')
    sys.exit(1 if red else 0)


if __name__ == '__main__':
    main()
