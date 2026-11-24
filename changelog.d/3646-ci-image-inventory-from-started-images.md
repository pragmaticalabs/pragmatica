### Fixed (2026-11-24 — #3646: the CI image inventory missed images named by a method return)
- **`redis:7` and `valkey/valkey:8` were not in `tools/ci-test-images.txt`**, so the `integrations/net/redis` test group never pre-pulled
  them and a registry-mirror quota failure ejected a green PR. Both are listed, and a new source shape reads
  `String image() { return "..."; }` in the test sources of any module that starts a container.
  [verified: `tools/test_check_ci_test_images.py` `test_method_return_image_is_seen`, `test_the_redis_contract_images_are_in_the_table`]
- **The inventory no longer rests on source shapes.** Each test-group job records every container create the Docker daemon sees
  (`tools/ci-record-started-images.sh`, around the test step) and `check-ci-test-images.py --started --plan` compares the record with
  the list by one table, (row state for the group's modules, started?) -> verdict: an image the group started fails by name unless
  the group's pre-pull pulls it, so an unlisted image, an image listed only for other modules, and an image listed only with the
  excluded `!` marker all fail; a group whose modules start nothing and list only excluded rows (g1: `aether/node`) expects nothing
  and is green; an empty record is red only when the group's pre-pull pulls a non-Ryuk image. Names are compared through one
  function (mirror prefix, Docker Hub host, `library/`, implicit `:latest`; a digest is kept; an image ID is red with its own reason).
  Scope: the test-group matrix job; the shard, forge and S3 jobs keep the source-shape check only.
  [verified: the table and g1 in `StartedImagesTest`, the forms in `NormalisationTest`, the python/bash row selection agreement in
  `PrepullTest.test_the_checker_and_the_pre_pull_select_the_same_rows_for_every_plan`; the `ci.yml` wiring (record step id, compare `if`,
  `--prefix`, `--plan`, stop, timeouts) in `WorkflowWiringTest`, which parses the file]
  [verified: the real `docker events` invocation on cloudbb-5's daemon, `RealDockerRecorderTest` (a container created under the recorder
  is recorded under its mirror-prefixed name)]
  [unverified: `nohup docker events &` surviving across GitHub steps on ubuntu-latest, and `docker events` permissions there: first CI run of `test-group-g1..g4`]
