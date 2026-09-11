### Fixed (2026-09-11 — #718 shape 4: Forge's cluster-start budget is configurable, and its timeout says what did not finish)
- **`[cluster] start_timeout_seconds` is now a `forge.toml` setting** (default `60`, unchanged).
  It was a hard-coded literal at the single `await` site, so a host where formation is merely slow
  had no way to buy more time — Forge simply exited. `EmberConfig` gains a `startTimeoutSeconds`
  component, rejects a non-positive budget (which would expire before the cluster could possibly
  form), and both consumers — `ForgeServer.startCluster` and `EmberInstance` — read it.
- **The timeout message now names what did not finish.** It was
  `"Failed to start cluster: " + cause.message()`, which on the timeout path surfaces as
  `Promise is not resolved within specified timeout` — a sentence naming no phase, no peer and no
  port, and **identical whether nothing formed at all or four of five nodes were consensus-active**.
  It now reports, all read from the cluster at the deadline and none inferred: the budget and the
  setting that governs it, how many of N nodes were consensus-active, the leader (or `none`), each
  node with its state and QUIC port, and any per-node start failure — or, when none was recorded,
  that the budget expired with the start still in progress rather than with a node erroring out.
- **It states what Forge DID verify and refuses to diagnose what it did not.** The #1008 preflight
  verified the QUIC range was free immediately before the start, so a port collision is ruled out and
  the reader need not re-check it. Stale `forge-data`, host load and a genuine consensus fault are
  offered as candidates without ranking, explicitly labelled as unchecked — the same discipline as
  the #952 startup-deploy message.
- **The message never calls a node healthy.** `state` is `AetherNode.isReady()` (consensus-active),
  labelled as such and not as a general health verdict — #727's defect was an unconditional
  `"healthy"` literal that made every failing cluster report healthy nodes beside `leader=none`.
- **Explicitly NOT coupled to the 60-second higher-id grace** in `QuicClusterNetwork` (#491). The two
  values are numerically equal and serve unrelated purposes — that grace exists to avoid a cold-boot
  dual-Hello race for a never-connected peer. No evidence of a real relationship was found, and per
  owner ruling none was assumed; neither value is derived from the other.
  [mechanism: `ForgeServer.clusterStartFailureMessage` renders only values read from
  `EmberCluster.status()` at the deadline — `ForgeServerMessageTest.ClusterStartFailure` (9 tests),
  `EmberConfigStartTimeoutTest` (5 tests); mutation-probed, see the PR for the reddened sets]
