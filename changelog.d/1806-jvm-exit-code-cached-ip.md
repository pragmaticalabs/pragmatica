### Fixed (2026-10-01 — integration harness: S19's exit-code step FAILED on survivors that had halted with exit 2)
- **`Survivor exit codes are 2` (JVM runs) read the unit through a live node-id lookup**, which after the quorum-loss drain
  misses for CTM survivors (not in `bootstrap-state.json`, cluster API down), so ssh was never reached and the step FAILED on
  VMs that had halted correctly (tier 2 had read `ExecMainStatus=2` from the same VMs). The step now reads at the pre-kill
  cached address (`_s19_resolve_survivor_ip` + `cloud_ssh_ip`) and proves WHICH VM answered: the same ssh command prints the
  VM's own `AETHER_NODE_ID` (`/etc/aether/node.env`); a different or missing id is a FAIL (cached IPs are recycled), and an
  unresolvable address is an honest FAIL, not a guess.
  [verified: `aether/tests/integration/test/test-chaos-harness.sh` FJ1-FJ4, F1-F4 and G3 now carry the identity. No cloud run.]
