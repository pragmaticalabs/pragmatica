### Changed (CI)

- Ember's tests now run in two parallel jobs, `ember-shard-1` and `ember-shard-2`, instead of inside `build-and-test`. They were 53 of that step's 89 minutes, serial in one module, against a 90-minute limit. `tools/ember-shard.py` assigns test classes to shards from the source tree, so a new class cannot be left out; the shard count is the length of the `shard` matrix list in `ci.yml`. `build-and-test` passes `-Dember.skipTests=true` and its step timeout drops from 90 to 50 minutes. The merge gate must now also require `ember-shard-1` and `ember-shard-2` (#1643).
