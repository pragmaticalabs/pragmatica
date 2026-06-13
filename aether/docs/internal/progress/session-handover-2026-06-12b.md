# Session Handover — 2026-06-12b (END OF SESSION)

**One-line state:** Local gate **15/15 ×3** ✅ and **Hetzner Cloud 15/15** ✅ (real-environment milestone met);
**history rewrite STAGED locally (25 commits on alpha base) — NOT PUSHED**; backup branch
`backup/pre-cleanup-rc1` is the parachute and **must be preserved until the user explicitly says
drop + gc**. Next session starts at "PUSH SEQUENCE" below, on the user's explicit go.

---

## WHERE WE ARE (exact)

- **Branch:** `release-1.0.0-rc1`, HEAD = the rewritten history: 24 cohesive subsystem commits on
  the alpha base `03fe57bb4` (`main`) + this handover commit = 25. Order: core → integrations
  (consensus/swim/dht/storage/db/rest) → slice-processor → jbct → aether (membership → deployment →
  invoke → pg → cloud → cli → node → dashboard → forge → slice → runtime) → tests → examples →
  docs → build.
- **Integrity proof:** `git diff backup/pre-cleanup-rc1 HEAD` = ONLY additive handover doc(s)
  (+0 deletions). Zero committed code differs from the pre-rewrite tip.
- **NOT pushed. Tag `v1.0.0-rc1-candidate` NOT moved.** No open PRs (#326/#327/#328 all merged and
  folded in; #327/#328 content verified present — mapWith combinators + skill-grade headers).
- **Working tree:** clean. **Hetzner account:** empty (box torn down, ~€0.50 spent).
- **Old commit hashes are GONE from the branch** (rewrite). The backup branch holds the old
  1,460-commit history if archaeology is ever needed (LOCAL ONLY — never push it).

## PUSH SEQUENCE (next session, ONLY on explicit user go)

1. User eyeballs `git log --oneline main..HEAD` (25 commits).
2. `git push --force-with-lease origin release-1.0.0-rc1`
3. Re-point moving tag: `git tag -f v1.0.0-rc1-candidate HEAD && git push -f origin v1.0.0-rc1-candidate`
4. Backup branch: keep until user EXPLICITLY says drop; then `git branch -D backup/pre-cleanup-rc1 && git gc --prune=now`.

## THEN (ratified plan, tasks #5/#6)

- **Cut v1.0.0-rc1 tag** with scope banner: *single-trust-domain only, security OFF by default, not
  production-hardened.* FIRST verify the insecure default is LOUD (startup warning + README).
  Security gates #282/#290 = RC2 hard gate.
- **Multi-RC roadmap** from `aether/docs/internal/audits/` (#326): one-way-door-ordered between
  buckets, non-interfering within; delegate audit digestion read-only, synthesize yourself.
  Issue clusters: Security #282/290/289/295/299/313/287 · Dashboard #291-294/302-305/312 ·
  Cloud #296-298/306/307 · Interceptors #273-281 · CLI/Mgmt #300/301/308/309/311 · Docs EPIC #314.

## WHAT THIS SESSION FIXED (all validated; in the rewritten history by subsystem)

Product (10): #325 ROUTING wedge + docker app-port; join-grace-reaped container leak; drain-victim
policy (was draining live slice-owners — 96%→0% err); seed-500 DHT zombie-routing (routing filter +
quorum fail-fast + ring prune-on-DEPARTING + symmetric re-add); QUIC tombstone livelock (inbound
readmit via raw-SWIM proof-of-life + tombstone-age grace) + topology-path force-dial + quorum-loss
membership co-confirmation (later PROVEN live suppressing a 5-node suicide); poison-gossip death
path (gossiped FAULTY needs live-transport CONTRADICTION to downgrade — accept-on-unknown after the
heal-latency regression refinement); backfill cold-start deadlock (bounded wait + data-safe
self-promotion, unreachable-blocks); QUIC acceptor stream-zombie (lanes-before-CONNECTED + lazy
lane-open + bounded evict backstop); pause-clobbering on republish (read-merge paused + last-replica
unpublish); artifact-resolve eternal-hang (4 layers: chunk-scaled resolve timeout, single-flight
in-flight eviction, disk-read deadline, HTTP 504 dispatch backstop — Hetzner 09: 3h hang → 32s pass).

Harness (6): 02 forensics fixes (prior session tail); 13 set-diff replacement pin + blank-role-as-core;
12 settle-gate TOCTOU (wait IS the assertion); 12 QUIC-connectivity settled-state wait; 13 victim pick
prefers static + intersects RUNNING containers (membership can hold dead entries post-churn);
09 curls bounded `--max-time 120`. Plus 2 lint-baseline line-shift updates.

## HETZNER VALIDATION PLAYBOOK (repeatable)

- Provision: `hcloud ssh-key create --name aether-test --public-key-from-file ~/.ssh/aether_test.pub`;
  firewall `aether-gate-fw` allowing ALL tcp/udp/icmp from the operator egress IP ONLY (clusters run
  AETHER_INSECURE_DEV_MODE); `hcloud server create --name aether-gate --type ccx33 --image docker-ce
  --location fsn1 --ssh-key aether-test --firewall aether-gate-fw`.
- **GOTCHA (real finding):** aether-node containers can't reach `/var/run/docker.sock` on Ubuntu-24
  docker-ce (GID mismatch) → CTM provisioning dies (exit 126 permission denied) → auto-heal totally
  dead. Test-box unblock: `chmod 666 /var/run/docker.sock` (user-authorized). DURABLE FIX = RC2 item:
  compose `group_add: <host docker GID>` injection + ops-docs requirement.
- Run: `TARGET_HOST=<ip> AETHER_SSH_USER=root AETHER_SSH_KEY=~/.ssh/aether_test ./run-tests.sh
  --env remote --skip-build` from `aether/tests/integration`.
- Teardown: `hcloud server delete aether-gate; hcloud firewall delete aether-gate-fw;
  hcloud ssh-key delete aether-test`.
- NOTE TERMINOLOGY: the regular gate is the **remote-host Docker run** ($TARGET_HOST box), NOT
  "Hetzner" — see memory `project-test-env-is-remote-docker-not-hetzner`.

## RC2 BACKLOG ACCUMULATED THIS SESSION

#4 role-label-in-gossip (13 gate re-tighten then); QuorumCoConfirmation all-or-nothing (b)
refinement (per-member sufficiency instead of allStuck); splitTimeout T=15s tuning vs loaded-host
boot; #284 CDM retry storm (+ #68-class dead-ULID consensus retry spam seen on Hetzner);
drain route empty-id → 404 not Composite-500; docker-socket group_add + deploy ops-docs;
HttpRoutePublisher test 90s seam; `DashboardMetricsPublisher` 1s forEach + `KvStoreApiKeyValidator`
per-request scan (perf); JBCT formatter destructive bugreport (/tmp/jbct-format-bugreport.md, unfiled);
artifact-repo `MavenProtocolHandlerTest.handlePut_accepts_pom_file` 201-vs-200 PRE-EXISTING failure.

## CONSTRAINTS (verbatim — violating these has caused real incidents)

- `env -u HCLOUD_TOKEN` on EVERY mvn invocation; NEVER `mvn verify` with HCLOUD_TOKEN set.
- NEVER `-Djbct.skip=true` for aether builds. Module tests: `mvn -pl <module> test`.
- Never inline $TARGET_HOST/$AETHER_SSH_KEY/$AETHER_SSH_USER values for the standard box.
- Git: single-line conventional commits, NEVER Co-Authored-By/trailers; commit directly to the
  release branch.
- Orphan pre-flight (`pgrep -fl run-tests.sh|surefire|mvn`) as a SEPARATE command BEFORE launching
  any run (chaining check+launch in one command bypasses the gate — bit us once).
- Forensics-FIRST on any remote failure (capture container logs BEFORE killing harness/teardown —
  `docker logs` per container via ssh, tar to /tmp, scp local). Harness restore can compose-cycle
  and destroy evidence within minutes.
- Integration-harness edits shift `lint-baseline.txt` line numbers — update baseline in the same
  commit or the next run exits at Step "Lint".
- Background agents: tell them "do NOT commit" but VERIFY — one auto-committed and another then
  reverted my own legitimate commit; re-commit after review.
- Monitor filters: suite tallies + FAILs + specific forensic markers only; never `[STEP]`/`===`.
