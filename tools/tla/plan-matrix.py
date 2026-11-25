#!/usr/bin/env python3
"""Plan the shards of a TLA+ models run: one job per (model, profile), so the wall time of a run is the slowest model's.

Usage: plan-matrix.py --event EVENT --profile PROFILE --modules REGEX [--models models.tsv]
Prints the GitHub matrix as one JSON line: {"include": [{"module", "root", "profile", "job_minutes", "tlc_timeout",
"skipped_host"}]}. tlc_timeout is the per-config cap of the small and sim profiles; for full and heavy it is "" because every row
runs under its own budget from budgets.tsv (run.sh reads it; an empty TLC_TIMEOUT is no override). skipped_host lists, by name,
the rows of a full or heavy shard whose budget does not fit a GitHub-hosted runner (budgets.tsv `@hosted`): run.sh does not run
them there and records SKIPPED-HOST, and tools/tla/run-host.sh runs them on a build host. They are also printed to stderr, one
line each, so the plan step's log names every one; they are never passes.

What runs, by event (the profile argument is the workflow_dispatch input; push and schedule carry none):
  push                 profile small, every model matching the regex.
  schedule             profile small for every model, plus profile heavy for every model that has a row marked `heavy` in
                       models.tsv (the rows the small profile skips by name; they run here and on demand only).
  workflow_dispatch    the chosen profile for every model matching the regex; heavy only for models with a heavy row.
Exit 2: an unusable invocation, an unknown event or profile, or no shard to run (a regex that matches no model is an error,
never an empty green run).
"""
import argparse
import json
import os
import re
import sys

PROFILES = ('small', 'full', 'sim', 'heavy')
# per job (minutes) and per config (TLC_TIMEOUT, seconds). Small is bounded by the slowest model's rows in sequence:
# measured on cloudbb-5, Fsm2Leader 94 min without its heavy row, RabiaAdopt 90 min.
BUDGET = {'small': (150, 1200), 'sim': (60, 300), 'full': (350, 18000), 'heavy': (350, 18000)}


def read_models(path):
    models = {}   # module -> [root, has_heavy]
    with open(path) as f:
        for line in f:
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            c = line.rstrip('\n').split('\t')
            if len(c) < 7:
                raise ValueError(f'{path}: row with {len(c)} columns: {line.strip()[:60]}')
            m = models.setdefault(c[0], [c[1], False])
            if m[0] != c[1]:
                raise ValueError(f'{path}: module {c[0]} has two source roots')
            m[1] = m[1] or c[5] == 'heavy'
    return models


def read_rows(path):
    """[(module, variant, small)] of models.tsv, in file order."""
    rows = []
    with open(path) as f:
        for line in f:
            if line.strip() and not line.lstrip().startswith('#'):
                c = line.rstrip('\n').split('\t')
                rows.append((c[0], c[2], c[5]))
    return rows


def read_budgets(path, rows):
    """(default, hosted, {(module, variant): (timeout_s, heap_g)}) of budgets.tsv; heap_g is 0 for the JVM default.
    The table is validated like run.sh validates it: five columns, a positive timeout, heap <N>g or -, no repeated row, no row
    models.tsv lacks, a `*` default row and an @hosted row."""
    known = {(m, v) for m, v, _ in rows}
    table, default, hosted = {}, None, None
    with open(path) as f:
        for line in f:
            if not line.strip() or line.lstrip().startswith('#'):
                continue
            c = line.rstrip('\n').split('\t')
            if len(c) != 5:
                raise ValueError(f'{path}: row with {len(c)} columns: {line.strip()[:50]}')
            if not re.fullmatch(r'[0-9]+', c[2]) or int(c[2]) == 0:
                raise ValueError(f'{path}: {c[0]}/{c[1]}: timeout_s must be a positive whole number')
            if not re.fullmatch(r'([0-9]+g|-)', c[3]):
                raise ValueError(f'{path}: {c[0]}/{c[1]}: heap must be <N>g or -')
            value = (int(c[2]), 0 if c[3] == '-' else int(c[3][:-1]))
            if c[0] == '*':
                if c[1] != '*' or default is not None:
                    raise ValueError(f'{path}: malformed or second default row')
                default = value
            elif c[0] == '@hosted':
                if c[1] != 'limit' or c[3] == '-' or hosted is not None:
                    raise ValueError(f'{path}: malformed or second @hosted row')
                hosted = value
            else:
                if (c[0], c[1]) not in known:
                    raise ValueError(f'{path}: {c[0]}/{c[1]}: no such row in models.tsv')
                if (c[0], c[1]) in table:
                    raise ValueError(f'{path}: second row {c[0]}/{c[1]}')
                table[(c[0], c[1])] = value
    if default is None or hosted is None:
        raise ValueError(f'{path}: needs a `*` default row and an @hosted row')
    return default, hosted, table


def over_hosted(budget, hosted):
    """THE over-limit rule (#3636), the only implementation: run.sh asks it through `--over-limit` (its list, host and default
    modes), plan() asks it directly. Inputs are the budget table's numbers alone; TLC_TIMEOUT never reaches it."""
    return budget[0] > hosted[0] or budget[1] > hosted[1]


def parse_heap(text):
    """'8g' -> 8, '-' (the JVM default) -> 0, as read_budgets stores it."""
    return 0 if text == '-' else int(text.rstrip('g'))


def skipped_host_rows(rows, budgets, module, profile):
    """The rows of (module, profile) a hosted runner cannot take, as 'Module/variant budget <s>s/<heap>'. Only full and heavy
    have per-row budgets; heavy covers the rows marked heavy only, as run.sh does."""
    if profile not in ('full', 'heavy'):
        return []
    default, hosted, table = budgets
    out = []
    for m, v, small in rows:
        if m != module or (profile == 'heavy' and small != 'heavy'):
            continue
        b = table.get((m, v), default)
        if over_hosted(b, hosted):
            out.append(f'{m}/{v} budget {b[0]}s/{b[1]}g')
    return out


def plan(models, event, profile, regex, rows=(), budgets=None):
    chosen = {k: v for k, v in models.items() if re.fullmatch(regex, k)}
    shards = []
    if event == 'push':
        want = [(k, 'small') for k in chosen]
    elif event == 'schedule':
        want = [(k, 'small') for k in chosen] + [(k, 'heavy') for k, v in chosen.items() if v[1]]
    elif event == 'workflow_dispatch':
        want = [(k, profile) for k, v in chosen.items() if profile != 'heavy' or v[1]]
    else:
        raise ValueError(f'unknown event {event!r}')
    for module, prof in want:
        minutes, tlc = BUDGET[prof]
        # full and heavy: every row has its own cap (budgets.tsv); the matrix carries none, an empty TLC_TIMEOUT overrides nothing
        skipped = skipped_host_rows(rows, budgets, module, prof) if budgets else []
        shards.append({'module': module, 'root': chosen[module][0], 'profile': prof, 'job_minutes': minutes,
                       'tlc_timeout': '' if prof in ('full', 'heavy') else tlc, 'skipped_host': ', '.join(skipped)})
    return shards


def main():
    if len(sys.argv) == 6 and sys.argv[1] == '--over-limit':   # --over-limit <timeout_s> <heap> <hosted_timeout_s> <hosted_heap>
        _, _, t, h, lt, lh = sys.argv
        try:
            sys.exit(0 if over_hosted((int(t), parse_heap(h)), (int(lt), parse_heap(lh))) else 1)
        except ValueError as e:
            print(f'plan-matrix: ERROR --over-limit: {e}', file=sys.stderr)
            sys.exit(2)
    ap = argparse.ArgumentParser()
    ap.add_argument('--event', required=True)
    ap.add_argument('--profile', default='small')
    ap.add_argument('--modules', default='.*')
    ap.add_argument('--models', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'models.tsv'))
    ap.add_argument('--budgets', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'budgets.tsv'))
    a = ap.parse_args()
    if a.profile not in PROFILES:
        print(f'plan-matrix: ERROR profile must be one of {", ".join(PROFILES)}, got {a.profile!r}', file=sys.stderr)
        sys.exit(2)
    try:
        models = read_models(a.models)   # first: it refuses a short row, which read_rows would not
        rows = read_rows(a.models)
        shards = plan(models, a.event, a.profile, a.modules, rows, read_budgets(a.budgets, rows))
    except (ValueError, OSError, re.error) as e:
        print(f'plan-matrix: ERROR {e}', file=sys.stderr)
        sys.exit(2)
    if not shards:
        print(f'plan-matrix: ERROR no shard to run: event {a.event}, profile {a.profile}, modules {a.modules!r}', file=sys.stderr)
        sys.exit(2)
    for s in shards:
        for named in filter(None, s['skipped_host'].split(', ')):
            print(f'plan-matrix: SKIPPED-HOST {named} (profile {s["profile"]}): over the hosted runner; not run there, not a pass; tools/tla/run-host.sh {s["profile"]}', file=sys.stderr)
    print(json.dumps({'include': shards}, separators=(',', ':')))


if __name__ == '__main__':
    main()
