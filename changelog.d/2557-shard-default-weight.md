### Changed (2026-11-01 — #2557: a new Ember or Consensus test class no longer edits the shard weights file)
- **Adding a test class meant adding a line to `tools/ember-shard-weights.txt` or `tools/consensus-shard-weights.txt`**,
  which put every such pull request into conflict with the others. The planner now gives a class with no weight a
  default instead, the 75th percentile of the module's weights rounded up. Before, the default was the maximum, or
  the plan failed outright for a Consensus class. The budget check still fails a predicted overrun, defaults
  included. [verified: tools/test_ember_shard.py, tools/test_consensus_shard.py]
- `tools/regen-shard-weights.py --module {ember|consensus} --run <id>` rewrites a weights file from that CI run's own
  surefire reports. Each class's suite times are summed over its `@Nested` reports and rounded up, and each line
  records the run. [verified: tools/test_regen_shard_weights.py, against real reports of run 41351583462]
