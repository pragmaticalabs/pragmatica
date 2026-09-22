#!/usr/bin/env python3
"""Verify that selected authority guarantees have tests which reject their removal.

Run in the foundation PR worktree after installing its dependencies. Temporarily mutates only
KVStore.java, restores its exact bytes after every run, and refuses pre-existing edits to it.
Results/logs are written under target/hierarchy-mutations. This is not a performance benchmark.

A kill is credited from a FAILING TEST, not from a failing build, so the suite must be green before
the first mutation: without that control any pre-existing red inside the target selector is scored
as a kill, and the score is highest exactly when the module is most broken. The baseline run also
asserts that every target class actually reported, because a -Dtest selector matching nothing exits
zero and would otherwise read as a clean baseline.
"""
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
relative = 'integrations/cluster/src/main/java/org/pragmatica/cluster/state/kvstore/KVStore.java'
source = root / relative
original = source.read_bytes()
committed = subprocess.check_output(['git', 'show', 'HEAD:' + relative], cwd=root)
if original != committed:
    sys.exit('Refusing to mutate an edited KVStore.java')
text = original.decode()
output = root / 'target/hierarchy-mutations'
output.mkdir(parents=True, exist_ok=True)
mutations = [
    ('read-set', 'return witness.expected()\n                      .equals(Option.option(storage.get(witness.key())));',
     'return true;', 'KVStoreLeaderTransactionTest'),
    ('epoch-mint', 'incoming.mintsEpoch() || differentOwner(incoming, stored)',
     'differentOwner(incoming, stored)', 'KVStoreOwnerFenceTest#mintRequiresSuccessorEpoch_evenForTheSameOwner'),
    ('owner-remove', '((E) owner.fenceEpoch()).compareTo(committed.fenceEpoch()) > 0',
     '((E) owner.fenceEpoch()).compareTo(committed.fenceEpoch()) >= 0', 'KVStoreOwnerFenceTest'),
    ('canonical-snapshot', 'serializer.canonical()', 'serializer', 'KVStoreCanonicalSnapshotTest'),
    ('reentrant-dispatch', 'if (dispatching) {', 'if (false) {',
     'KVStoreNotificationIsolationTest#reentrantNotificationSeesStoreAfterAllNestedApplies'),
    ('atomic-reader', 'public synchronized Map<K, V> snapshot()', 'public Map<K, V> snapshot()',
     'KVStoreNotificationIsolationTest#snapshotCannotObserveHalfAppliedBatch'),
    ('leader-authority', 'transaction.leader().equals(storage.get(LeaderKey.INSTANCE))', 'true',
     'KVStoreAuthorizedMutationTest'),
    ('read-set-acceptance', 'return witness.expected()\n                      .equals(Option.option(storage.get(witness.key())));',
     'return !witness.expected()\n                       .equals(Option.option(storage.get(witness.key())));',
     'KVStoreLeaderTransactionTest'),
    ('leader-downgrade', 'dropsLeaderAuthorization(key, incoming) || ', '', 'KVStoreAuthorizedMutationTest'),
]
reports = root / 'integrations/cluster/target/surefire-reports'
env = dict(os.environ)
env.pop('HCLOUD_TOKEN', None)


def fresh_reports(started):
    """The reports this run wrote, keyed by test class simple name."""
    return {report.stem.split('.')[-1]: ET.parse(report).getroot()
            for report in reports.glob('TEST-*.xml') if report.stat().st_mtime >= started}


def count_failures(suites):
    """Count <testcase> elements carrying a failure/error.

    NOT the <testsuite failures> attribute, which surefire reports as 0 for @Nested classes
    (KVStoreEpochFenceTest, KVStoreLeaderFenceTest and KVStoreRemoveFenceTest in this very module
    read 0 against 15/9/20 elements). Reading the attribute would score a real red as a survival
    here, and - worse - would let a red baseline pass the control below.
    """
    return sum(1 for suite in suites
               for case in suite.iter('testcase')
               if any(child.tag in ('failure', 'error') for child in case))


def run(name, test, timeout):
    started = time.time()
    command = ['mvn', '-T1', '-pl', 'integrations/cluster', 'test', '-Dtest=' + test]
    with (output / (name + '.log')).open('w') as log:
        completed = subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
    return command, completed, fresh_reports(started)


baseline_command, baseline, baseline_reports = run('baseline', 'KVStore*Test', 300)
baseline_failures = count_failures(baseline_reports.values())
baseline_cases = sum(1 for suite in baseline_reports.values() for _ in suite.iter('testcase'))
missing = sorted({test.split('#')[0] for _, _, _, test in mutations} - set(baseline_reports))
if baseline.returncode != 0 or baseline_failures or missing or not baseline_cases:
    sys.exit(f'Refusing to score mutations: the baseline is not a green run of every target class '
             f'(exit={baseline.returncode}, failing tests={baseline_failures}, tests={baseline_cases}, '
             f'target classes with no report={missing or "none"}). A mutation scored against this '
             f'baseline would credit a kill to a failure it did not cause. See '
             f'target/hierarchy-mutations/baseline.log')
print('baseline', f'GREEN ({baseline_cases} tests, {len(baseline_reports)} classes)', flush=True)
results = []
try:
    for name, before, after, test in mutations:
        if text.count(before) != 1:
            raise RuntimeError(f'{name}: mutation boundary not unique')
        source.write_text(text.replace(before, after, 1))
        command, mutated, mutated_reports = run(name, test, 180)
        failures = count_failures(mutated_reports.values())
        killed = mutated.returncode != 0 and failures > 0
        results.append(dict(mutation=name, test=test, command=command, exit=mutated.returncode,
                            assertion_failures=failures, killed=killed))
        print(name, 'KILLED' if killed else 'NOT VERIFIED', flush=True)
        source.write_bytes(original)
finally:
    source.write_bytes(original)
    (output / 'results.json').write_text(json.dumps(dict(
        source_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
        worktree=str(root),
        baseline=dict(command=baseline_command, exit=baseline.returncode, tests=baseline_cases,
                      classes=sorted(baseline_reports), failing_tests=baseline_failures),
        results=results), indent=2) + '\n')
sys.exit(0 if len(results) == len(mutations) and all(r['killed'] for r in results) else 1)
