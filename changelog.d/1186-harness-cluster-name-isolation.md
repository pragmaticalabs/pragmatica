### Fixed (2026-09-14 — #1186: integration-harness cluster names were fixed per runtime, so two concurrent arms shared one reaper label)
- **`run-tests.sh` assigned both cluster names as plain literals and the `--runtime jvm` branch overwrote
  them unconditionally**, so two concurrent same-runtime arms shared one cluster name, one
  `~/.aether/clusters/<name>/` state dir and one `aether-cluster` label — and **arm 1's normal teardown
  reaped arm 2's live cluster**. The symptom is nodes vanishing mid-run, which reads as a runtime or
  membership fault rather than a harness one, so the natural response is to investigate the product.
  [mechanism: `bootstrap_cloud_cluster_b` passes `--cluster "$CLUSTER_B_NAME"`, and the teardown
  safety-net passes the same name to `cloud-reaper.sh --destroy`, which selects on
  `aether-cluster=<name>`]
- **Names now resolve through `resolve_cluster_name <a|b> <container|jvm>` (`lib/cluster.sh`).** An
  explicit `CLUSTER_A_NAME`/`CLUSTER_B_NAME` from the environment always wins, **including over the jvm
  rename**; with neither set the historical defaults are returned byte for byte, so an existing run is
  unchanged. The helper deliberately never reads `CLUSTER_{A,B}_NAME` itself — the caller captures
  `CLUSTER_{A,B}_NAME_EXPLICIT` first — so it cannot mistake a default the script just assigned for a
  name the operator chose.
  [verified: `aether/tests/integration/test/test-cluster-names.sh` — 15 assertions; defaults preserved
  per runtime, override survives the jvm rename, two arms resolve to different names]
- **The name is validated against the Hetzner label-value grammar and refused with rc 2**, so a bad name
  fails *before* provisioning rather than as an opaque API error once VMs are billed.
- **Known limitation, not fixed here:** `RESULTS_JSON` is still `${SCRIPT_DIR}/test-results.json` and is
  **not** per-arm. Concurrent arms launched from the same checkout still clobber each other's results
  file, so they continue to require **separate clones**. This change isolates cluster names, state dirs
  and reaper labels only.
