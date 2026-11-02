### Changed (2026-11-02 — #2566: Ember tests run in four balanced shards)
- **The Ember shards were unbalanced.** The committed weights were hand-measured: 90.8 min in total, against 76.8-77.7 min
  measured on four CI runs, and ranked wrongly. Three shards ran 23.3 to 29.2 minutes of tests, and the slowest shard set the
  length of every CI run. The Ember and Consensus weights are now regenerated from CI run 41371046891's own reports,
  with `tools/regen-shard-weights.py`. Ember runs in four shards, planned at 19.6 min each. Scored against three other
  runs, the slowest shard was 19.5, 19.7 and 19.6 min.
- The Ember shard budget (`BUDGET_SECONDS`) and job timeouts follow the new plan: job 35 min, test step 30, budget 1500 s.
  [verified: tools/test_ember_shard.py `test_the_budget_leaves_the_test_step_its_headroom`]
