### Fixed (2026-11-24 — #3646: the CI image inventory missed images named by a method return)
- **`redis:7` and `valkey/valkey:8` were not in `tools/ci-test-images.txt`**, so the `integrations/net/redis` test group never pre-pulled
  them and a registry-mirror quota failure ejected a green PR. Both are listed, and a new source shape reads
  `String image() { return "..."; }` in the test sources of any module that starts a container.
- **The inventory no longer rests on source shapes.** Each test-group job records every container create the Docker daemon sees
  (`tools/ci-record-started-images.sh`, around the test step) and `check-ci-test-images.py --started --plan` fails by name on an
  image the group started that the list does not name for the group's modules, and on an empty record from a group whose
  modules start containers (a dead recorder reads as "nothing started"). Scope: the test-group matrix job; the shard, forge and
  S3 jobs keep the source-shape check only.
