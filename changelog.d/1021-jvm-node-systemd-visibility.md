### Fixed (2026-09-12 — #1021: a JVM-mode cloud node leaves no local trace when it dies)

- **A JVM-mode node was launched as a bare `java -jar … &`, so `systemctl list-units | grep -i aether`
  returned nothing.** No unit, no `systemctl status`, no `journalctl -u`: a node that exited was a
  silent absence, with nothing an operator could query between "healthy" and "the VM was replaced
  minutes later". Cloud-init now installs and starts an `aether-node.service` unit, so a dead node
  sits in `failed`/`inactive` with its exit status, and its output is in the journal.
  `[verified: aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/UserDataTemplatePeersTest.java — render_launchesTheJvmUnderASystemdUnit_thatDoesNotRestartIt]`
- **The unit does NOT restart the node, and that is the point.** `Restart=no` is load-bearing: Aether
  uses terminal-removal membership, so a crashed NodeId never returns under the same identity and
  recovery is a new-ULID replacement minted by CTM auto-heal.
  `aether/docs/operators/deployment-recovery.md` §1 mandates `Restart=no` for systemd by name — "not
  optional … required for cluster correctness" — and §2.1 records the multi-hour Hetzner chaos-test
  stall a restarting policy produced: the runtime respawned the node, the same-id rejoin was rejected,
  the container respawn-looped, and CTM never observed the failure because the restart beat its
  detector. The test rejects `Restart=always|on-failure|on-abnormal|on-abort|on-success|on-watchdog`
  and `RestartSec` by name, not merely asserting `Restart=no` is present: systemd honours the LAST
  directive, so a presence-only check would pass on a unit that also carried `on-failure`.
  `[verified: aether/aether-config/src/test/java/org/pragmatica/aether/config/cluster/SystemdUnitTemplateTest.java — RestartPolicyIsPinned]`
- **This does NOT fix #966**, and does not partially address it. A node that OOMs but never exits keeps
  its membership slot, and it is `active` to systemd no matter what it has stopped doing — no unit can
  surface that shape. This fixes the opposite case: a clean exit nobody can see.
- **`BootstrapPhaseDeploy`'s finalized-PEERS re-launch no longer uses `pkill -f`.** It rewrites the
  unit's env file and runs `systemctl restart aether-node.service`. Changing it was not optional once
  the unit exists: a `pkill` would drive the unit to `failed` — correctly, under `Restart=no` — while
  the detached `nohup` process it started ran beside it, so `systemctl status aether-node` would
  report failure for a node that was serving. That is precisely the false signal this issue exists to
  remove, and shipping the unit without this change would have made the observable worse than no unit
  at all. It also retires a pattern-matching hazard rather than re-guarding it: `pkill -f` matched the
  SSH session's own argv, which is why Bug 20a had to anchor it at `^java -jar <JAR>`; a unit name
  matches no command line.
  `[verified: aether/cli/src/test/java/org/pragmatica/aether/cli/cluster/BootstrapPhaseDeployCloudSshRestartTest.java — deployCloudSource_jvmRuntime_restartsTheUnitByName_notDockerAndNotByProcessPattern]`
- **`SystemdUnitTemplate` was already in the tree, already unit-tested, and had ZERO production
  callers** — written for this path and never connected. It is wired in rather than replaced, and
  MOVED from `aether/cli` to `aether-config` rather than copied, because `NodeUserDataRenderer` lives
  there and `aether-config` cannot depend on `cli`. Exactly one definition exists tree-wide.
- **Scope — the unit reaches bootstrap-minted nodes, NOT auto-heal replacements.** Bootstrap user-data
  is rendered by the operator's CLI, so it carries this change. A replacement's user-data is rendered
  by `ClusterTopologyManagerRecord.renderReplacementUserData` **on the leader node**, from whatever
  `aether-node` jar that VM is running — so replacements do not carry the unit until a node jar
  shipping this change is published. "The systemd unit is deployed" is true of the first path and
  false of the second. `[mechanism: shared renderer, two callers, one per side of the CLI/node boundary]`
- **Observation, not addressed here:** because replacement user-data is rendered by the leader,
  replacements inherit whatever version the LEADER runs rather than the operator's CLI — so during a
  rolling upgrade a replacement can be minted from older rendering than the operator is running.
  `[design intent — unverified]`
- **Not changed: the JVM still runs as `root`**, matching what the bare `nohup java` already ran as.
  The previously-uncalled template said `User=aether`; switching is a real improvement and an
  unverified behaviour change on a paid-run-only path (the `aether` account is created only when the
  operator supplied SSH keys, the jar is root-owned, and `aether.toml` is chowned to uid 1000 rather
  than to that account by name). Tracked separately rather than smuggled in here.
- **Not verified without a live cloud run:** that the unit loads, that the node starts under it, that
  a killed JVM leaves the unit in `failed`, and that the finalized-PEERS `systemctl restart` succeeds
  on a real host. Every claim above is pinned against the RENDERED script and the generated restart
  command; none of them has booted a VM.
