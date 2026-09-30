### Fixed (2026-09-30 — integration harness: the 12-network partition test leaked its partition when S05 failed)
- **A failed S05 left the partition in place.** `test-partition-quorum-gate.sh` healed the two minority
  nodes only at the end of `test_partition_does_not_destabilize_majority`, so any `return 1` between
  the partition and that line skipped it. A cloud run at `5c1a726b7` leaked two Hetzner partition
  firewalls that way, S06 then ran against a never-healed partition, and the next test inherited it.
  The heal now runs on every exit path of the step (failed disconnect, S05 violation, success).
- **The EXIT-trap cleanup skipped the heal silently on cloud.** It resolved each minority node with
  `container_for_node`, which is empty once CTM has replaced the node, and `[ -n "$c" ] && ...`
  swallowed that. Cleanup now heals by node id in that case (`cloud_heal_partition` needs only the id;
  the firewall name derives from it), and every path that does not heal logs a WARN naming the node
  and the reason. Docker-mode behaviour is unchanged apart from that log line.
- **Other partition tests checked for the same shape:** the other three `12-network` tests
  (`test-gossip-encryption.sh`, `test-quic-connectivity.sh`, `test-swim-detection.sh`) never
  partition; the only other caller of the partition primitives, `test/test-restore-gate.sh`, is a stub
  suite that removes its own firewall records.
  [verified: `aether/tests/integration/test/test-partition-heal-on-failure.sh` (4 stub tests running the
  real script against recording stubs); reverting the script to base reddens 3 of 4 (the docker case
  passes on base by design — it pins unchanged behaviour). No cloud run was made.]
