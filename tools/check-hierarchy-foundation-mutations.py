#!/usr/bin/env python3
"""Verify that selected authority guarantees have tests which reject their removal.

Run in the foundation PR worktree after installing its dependencies. Temporarily mutates only
KVStore.java, restores its exact bytes after every run, and refuses pre-existing edits to it.
Results/logs are written under target/hierarchy-mutations. This is not a performance benchmark.
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
]
env = dict(os.environ)
env.pop('HCLOUD_TOKEN', None)
results = []
try:
    for name, before, after, test in mutations:
        if text.count(before) != 1:
            raise RuntimeError(f'{name}: mutation boundary not unique')
        source.write_text(text.replace(before, after, 1))
        started = time.time()
        command = ['mvn', '-T1', '-pl', 'integrations/cluster', 'test', '-Dtest=' + test]
        with (output / (name + '.log')).open('w') as log:
            run = subprocess.run(command, cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=180)
        failures = 0
        for report in (root / 'integrations/cluster/target/surefire-reports').glob('TEST-*.xml'):
            if report.stat().st_mtime >= started:
                suite = ET.parse(report).getroot()
                failures += int(suite.get('failures', '0'))
        killed = run.returncode != 0 and failures > 0
        results.append(dict(mutation=name, test=test, command=command, exit=run.returncode,
                            assertion_failures=failures, killed=killed))
        print(name, 'KILLED' if killed else 'NOT VERIFIED', flush=True)
        source.write_bytes(original)
finally:
    source.write_bytes(original)
    (output / 'results.json').write_text(json.dumps(dict(
        source_commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip(),
        worktree=str(root), results=results), indent=2) + '\n')
sys.exit(0 if len(results) == len(mutations) and all(r['killed'] for r in results) else 1)
