# Session Handover — 2026-06-06

**Branch:** `release-1.0.0-rc1` · **HEAD:** `37e4e257b` (pushed, 0 unpushed) · tree clean. Also active: local branch `test-239` = `origin/feature/stream-namespaces-rebuild` (PR #239) checked out for validation.

## TL;DR
Two RC1 fixes shipped to rc1 (auto-heal cluster-identity + transport REMOVED-reversibility), each Docker-validated and pushed. The alarming `connectedPeerCount=2` was **disproven as a permanent defect** (transient/env — the mesh forms and re-forms to 4). Then pivoted to **co-validating PR #239** (stream namespaces + cluster-events-over-replicated-stream, supersedes #187): posted a full code review (2 blockers, both since fixed by the other agent), and am now running the full integration suite against #239 and filing validation findings. **Division of labor with another agent: it owns #239 code changes, I own validation (only I have the remote test env).**

## Shipped to rc1 this session (pushed)
| Commit | What |
|--------|------|
| `5da8fd2bf` | fix(provisioning): emit `AETHER_CLUSTER_NAME` from `ProvisionContext` (same source as the `aether.cluster` label) — stops the auto-heal boot-guard crash-loop storm |
| `9c9ba2a35` | fix(test-harness): `seed_cluster_config` substitutes `[cluster].name` with `CLUSTER_ID` so CTM replacements match compose identity |
| `426853987` | fix(transport): incarnation-gated `PeerState.readmit()` (REMOVED→INIT) on SWIM re-admit — dial-side reconciler + accept-side `onPeerConnected`, gated on `coreNodes()` |
| `37e4e257b` | docs(changelog) |

- **#76 auto-heal root:** `DockerComputeProvider` stamped label/name/NODE_ID from KV `ProvisionContext.clusterName` (`integration-test`) but forwarded `AETHER_CLUSTER_NAME` env verbatim from the leader (`b`) → boot guard `Main.verifyClusterLabelConsistency` `exit(1)` → CTM retried forever → storm → no heal → #68 quiesce-never. Regression from `30409fd1a`×guard `04d4553b9`. Docker-validated (replacements boot healthy, 0 `Exited(1)`).
- **#78 transport REMOVED-reversibility:** spec §9.4 #2 says only a strictly-higher incarnation is terminal; SWIM already re-admits via tombstone/`supersedeOrRefuse`, but the QUIC `PeerState.REMOVED` (set by FAULTY-sweep→`departurePermanent`) was an irreversible dead-end ignoring the re-admit. Fix resets REMOVED→INIT when the peer is back in `coreNodes()` (incarnation authority); probe-ack stays sole ALIVE authority (preserves `d42a86ebe` anti-resurrection). 36/36 consensus tests green.

## connectedPeerCount=2 — RESOLVED as transient/env (NOT a permanent bug)
Two clean measurements killed the "permanent mesh defect" hypothesis:
- **Pristine never-killed cluster A:** `connectedPeerCount=4` on all nodes.
- **Post-churn settled cluster B** (4 ULID replacements after kills): node-1 `connectedPeerCount=4`, 5 ACTIVE/HEALTHY, NORMAL — full re-mesh + heal. `0 Network-unreachable` that run.

So `=2` is **mid-churn (assertion measures before heal completes) + amplified by intermittent remote Docker-bridge isolation** (25/24 `Network is unreachable` one run, 0 another). The REMOVED chain only triggers under the env partition — #78 is the correct safety net for it. Full memory: `memory/project_connectedpeercount2_transient.md`. **Do not re-chase as a deterministic defect; measure SETTLED state + check `Network is unreachable` first.**

## Remaining RC1 issues (rc1, separate from #239)
- **A1 — scale-down data loss** (`03-scaling/No_data_loss` marker GET → 404): artifact on a drained node not migrated before drain completes. Genuine durability bug. *Highest-priority real correctness item.*
- **A2 — scale-down request forwarding** (`03-scaling` error rate 74–100% under load): draining-node requests not rerouted.
- **A3 — drain API + budget** (`13-edge`): `drain → 500 "Node lifecycle not found"`; third drain not rejected (budget should 409). Has a `TODO: investigate` in the test.
- **A4 — eager-DNS re-dial** (the 2 of 3 #77 defects NOT fixed): `new InetSocketAddress(host,port)` → `<unresolved>` when a container is off-bridge; `NodeInfo.resolvedAddress` never carries the SWIM IP. Matters under real partition. Fix: `createUnresolved` + per-attempt re-resolution.
- **B5 — recovery speed under churn** (`#68` quiesce-180s, 12-network READY-600s, NODE_FAILED-60s): cluster heals but exceeds thresholds; NODE_FAILED latency partly the (now-being-replaced) event path.
- **Env/test:** remote Docker-bridge flakiness (infra); 05-security needs a secure-mode cluster-B variant (+ possible `renewalStatus=HEALTHY`-when-TLS-off reporting bug).

## PR #239 — co-validation (IN PROGRESS)
**What it is:** clean rebuild of stream-namespaces (epic #165) on rc1 HEAD `37e4e257b` (so it carries my fixes). Supersedes #187. +8251/−1922, 93 files originally; the author then **added stream replication** (HRW placement, replica registry, catch-up, owner-routed publish) and **re-replicated `system:cluster-events`** (node-local→replicated). Doesn't touch my membership/auto-heal/transport files.

**My review (posted):** `gh pr review` COMMENT — 2 blockers since fixed: C1 (KVStoreSerializer serialize-without-parse → broken snapshot restore — fixed `cf9283fe5`), C3 (triad docs — fixed `e123c4d79`); C2/C4/C5/C6 cleanups.

**Validation findings (posted as PR comment `#issuecomment-4637322989`), split by lane:**
- 🐞 (other agent): **Issue 1** sustained pub/sub publish 100% fail — owner-route/auto-create race under load (`04 load-test-stream`); **Issue 2** cross-node replicated-event delivery gap (`11/All_nodes_agree_on_order` node-0 zero events — same path membership NODE_FAILED rides); **Issue 3** rolling-promote quiesce-30s (`06`, refcount-on-ACTIVE chain); **Issue 4** (low-conf) transient no-leader 503 (`07`).
- 🧪 (my lane): **Issue 5** `@Notify` namespaced stream accessed via flat pub/sub API (`08 notifications`) — stale test; **Issue 6** harness stream helpers (`cluster.sh:2291-2303`) vs new addressing — open contract question for author.

**Key framing (from the user):** pub/sub is **not** namespaced (plain names + flat API correct); slice `@Notify`/`resources.toml` streams **are** namespaced (need full `(namespace,stream,version)` address externally). So several "failures" are stale tests, not regressions. Spec: `aether/docs/specs/event-stream-namespaces-spec.md` (§3 address, §4 derivation, §9 resolution).

**Cluster-A vs rc1 (was 10/10):** failures in 04/08 (stale stream-harness), 06 (deploy quiesce), 07 (transient leader), 11 (cross-node delivery). **Cluster B still running** at handover time (02-chaos) — owe a **follow-up PR comment** with the membership `NODE_FAILED`-over-replicated-events verdict (linked to Issue 2) + the full tally.

## Resume / state
- **rc1:** HEAD `37e4e257b`, pushed, clean. `v1.0.0-rc1-candidate` tag still at `ca55c8ddc` (one HEAD behind — move if desired).
- **#239 validation:** `git checkout test-239` (tracks `origin/feature/stream-namespaces-rebuild`). Jar already built + pushed to the remote image. Full-suite log: `/tmp/full-suite-239.log`. Monitor `bf0hnaalq` watches cluster-B signals.
- **To re-run a suite:** rebuild jar `env -u HCLOUD_TOKEN mvn -pl aether/node -am install -DskipTests`, then `cd aether/tests/integration && ./run-tests.sh --env remote --skip-build [--suites N,M] [--skip-teardown]`. **Always `docker rm -f aether-*` + `docker network rm aether-{a,b}-network` + `pgrep run-tests.sh` before runs.** `HCLOUD_TOKEN` must be stripped for any mvn; never `mvn verify`/`build.sh`.

## Learnings
- **Measure SETTLED state, never mid-churn** — corrected two wrong hypotheses (baseline-mesh-defect, REMOVED-is-the-whole-story) by measuring pristine + post-settle.
- **Verify subagent claims** — confirmed C1 (serializer asymmetry) myself before flagging it as a PR blocker.
- **Bash safety classifier had a transient outage** mid-session; retry, or do read-only work and come back.
- **Stream-test triage hinges on pub/sub-vs-namespaced** — don't conflate; the flat API is correct for pub/sub, full address required for slice-namespaced streams.
