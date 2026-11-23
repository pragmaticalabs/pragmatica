#!/usr/bin/env python3
"""Plan the shards of a TLA+ models run: one job per (model, profile), so the wall time of a run is the slowest model's.

Usage: plan-matrix.py --event EVENT --profile PROFILE --modules REGEX [--models models.tsv]
Prints the GitHub matrix as one JSON line: {"include": [{"module", "root", "profile", "job_minutes", "tlc_timeout"}]}.

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


def plan(models, event, profile, regex):
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
        shards.append({'module': module, 'root': chosen[module][0], 'profile': prof, 'job_minutes': minutes, 'tlc_timeout': tlc})
    return shards


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--event', required=True)
    ap.add_argument('--profile', default='small')
    ap.add_argument('--modules', default='.*')
    ap.add_argument('--models', default=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'models.tsv'))
    a = ap.parse_args()
    if a.profile not in PROFILES:
        print(f'plan-matrix: ERROR profile must be one of {", ".join(PROFILES)}, got {a.profile!r}', file=sys.stderr)
        sys.exit(2)
    try:
        shards = plan(read_models(a.models), a.event, a.profile, a.modules)
    except (ValueError, OSError, re.error) as e:
        print(f'plan-matrix: ERROR {e}', file=sys.stderr)
        sys.exit(2)
    if not shards:
        print(f'plan-matrix: ERROR no shard to run: event {a.event}, profile {a.profile}, modules {a.modules!r}', file=sys.stderr)
        sys.exit(2)
    print(json.dumps({'include': shards}, separators=(',', ':')))


if __name__ == '__main__':
    main()
