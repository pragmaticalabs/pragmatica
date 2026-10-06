### Removed (2026-10-06 — #1543 part A: no same-NodeId restart in the product's dead surface or the harness)
- **`NodeAction.RestartNode`, `ActionResult.NodeRestarted`, `NodeLifecycleManager.restartNode` and
  `ComputeProvider.restart` (AWS, Azure, GCP, Hetzner, Docker) are deleted.** Nothing produced a `RestartNode`
  (a restart of a known NodeId is refused under #1528); compile is the pin, and a repo-wide search for the
  symbols is empty. The cloud client libraries keep their own reboot calls; only the provider seam is gone.
- **Integration harness:** `start_node`, `cloud_revive_vm`, `cloud_stop_vm` and the deleted-VM record that existed
  only to support them are removed; `restart_all_nodes` no longer powers seed VMs back on or `docker start`s exited
  containers (a non-standard cluster name now fails loudly). The 02w cleanup restores the baseline through CTM
  auto-heal, which provisions the replacement under a fresh id. The docker-mode "auto-heal does not heal"
  (`deficit=1`, `NONE_PROVISIONING`) that motivated the old `docker start` was #597, long fixed; `NONE_PROVISIONING`
  means a provision is PERMITTED.
- `aether/docker/scaling-test/k6/chaos-controller.sh` kills a node and waits for the auto-heal replacement instead
  of stopping and starting the same container.
- Pinned by `tests/integration/test/test-no-same-id-relaunch.sh` (census with an allow-list that fails when stale,
  plus the real 02w `cleanup()` against recording stubs). [unverified: the whole-cluster restart (`restart_all_nodes`
  compose cycle, Forge `StreamCrashDurability`/`MultiPartitionCrashDurability`/`DurableEntityTimerDurability`
  `stop()` -> `start()`) still relaunches the same ids — part A2 of #1543.]
