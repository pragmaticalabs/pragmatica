# Session Handover — 2026-06-03

**Branch:** `release-1.0.0-rc1` · **HEAD:** `55e5c25fa` · **64 unpushed (DO NOT push)** · tree clean.

## TL;DR

The whole session converged on one structural conclusion: **the QUIC connection model was reverted from dual-dial ("natural establishment") back to single-dialer (lower-NodeId-dials gate), and that is the correct RC1 baseline.** Dual-dial was the source of the multi-session whack-a-mole (duplicate-connection races → half-open links → an `isActive()`-eviction storm → fix-on-fix). Single-dialer restored clean formation, leader re-election, and restore-READY.

**02-chaos under single-dialer: 5p/1f** (was 4p/2f-with-catastrophic-collapse under dual-dial). The lone failure is `Kill_2_nodes` (`pick_non_leader 1/2` + `generation did not quiesce within 180s`).

**The remaining residual is NOT what we spent the session chasing.** Live thread dumps + logs (2026-06-03) proved it is:
- **NOT a blocking handler** (all event loops idle in `select()`, Rabia executor parked, no BLOCKED thread, no deadlock — on the wedged node and the leader).
- **NOT dual-dial** (persists under single-dialer).
- **NOT transport establishment** (single-dialer join is fast: ~0.2s handshake, replacements sort lower and dial correctly).
- **NOT a killed-channel exception** (zero exceptions in the read/consensus path).

It IS: **an idle-but-receive-wedged consensus-stream flow-control stall** — the wedged receiver (node-5 in the capture) is *fully idle* (no thread doing anything) yet senders' CONSENSUS stream to it is perpetually "backpressured or inactive" (`Retry 1/200` every ~30s, never escalating). **Correlated with a stuck slice deployment** on the wedged node (its last log line before going silent: `Factory method echoSliceSlice returned promise, waiting for completion`; earlier `Failed to load slice test-echo-echo-slice: Artifact not found in any repository`).

## What's committed this session (all on `release-1.0.0-rc1`, unpushed)

| Commit | What | Keep? |
|--------|------|-------|
| `55e5c25fa` | **Revert natural establishment** → restore `ConnectionDirection.shouldInitiate` (lower-dials single-dialer gate); removes `prefersInitiator`/`resolveDuplicate` dual-dial adoption | **YES — the baseline** |
| `d61462250` | **broadcast uniform ClusterSync ping + global `drainNodes` set** (revert B5a per-peer `command` regression); receiver self-checks `drainNodes.contains(self)` | YES (sound on its own) |
| `037677347`+`0ee57168d` | parked keep-alive stream (feat then revert) — preserved in history, NOT in production | history only |
| `613a5d45b` | harness tuning (quiesce 180s, pick_non_leader 120s, dual-signal departure) | YES |

**Dropped (uncommitted, do not resurrect blindly):** the leader missed-pong "refresh" fix (acceptor-half, `emitPingTimeoutIfExceeded` quorum-gated `disconnect`) — investigator proved it never targets the wedged replacements; mis-aimed.

## The structural story (why we stopped whacking moles)

History (`git log -S`):
- **0.24.0 (`cbbef3a00`)**: idle-timeout disabled (`MAX_IDLE_TIMEOUT_MS=0`) + dial-gate (`shouldInitiate`) introduced together → start of the rock-solid single-dialer era.
- **`331d369dc`**: "strict ConnectionDirection + SWIM-authoritative disconnect" + `evictStaleConnection` (write-path eviction existed but stayed *dormant* under single-dialer).
- **Recent (`0b7f39905`→`b90ac4728`)**: **natural establishment** — dropped the gate → dual-dial → `prefersInitiator`/`resolveDuplicate`. Commit's own stated reason: *"multi-cloud safe"* (asymmetric reachability) — a **speculative robustness goal, not a reproduced failure.**

Dual-dial manufactured duplicate/half-open connections SWIM can't see → woke the dormant `isActive()` eviction into a storm → every "fix" since (keep-alive, refresh, grace) was scaffolding for dual-dial. **Reverting natural establishment removed the entire class.** Asymmetric/multi-cloud reachability is deferred to RC2 as deliberate design (relay/gateway or dual-dial-done-right), not bolted on.

### Dead ends ruled out THIS session (do not re-tread)
1. **Dedicated keep-alive stream** (parked `037677347`) — wrong layer (per-stream blind) + codec never wired into the runtime registry (`QuicCodecs.CODECS` generated but not composed into the node's `SliceCodec`). DOA.
2. **Leader missed-pong refresh** (acceptor half) — fires but never targets the storming replacements; mis-aimed.
3. **`writeToStream` fresh-connection grace** (skip `isActive()` evict for young links) — **CATASTROPHIC regression**: cluster collapsed to 0 nodes, re-election timed out, mass-restart failed. The immediate `isActive()`-evict is **load-bearing** for re-formation/re-election/restart convergence; cannot disable/grace it.
4. **Off-event-loop handler dispatch** — would mask nothing: thread dumps prove no handler ever blocks the loop.

## The actual remaining root (next session starts here)

**Symptom:** after `Kill_2` → auto-heal provisions 2 replacements; one reaches READY, one does not within 120s (`pick_non_leader 1/2`); `generation` doesn't quiesce in 180s. READY itself converges in 0s on most restores — the residual is a *latency/stall*, not a collapse.

**Mechanism (live-confirmed 2026-06-03, capture run, `--skip-teardown`):**
- The wedged *receiver* (node-5 in capture) is **fully idle**: `multiThreadIoEventLoopGroup-*` in `NioIoHandler.select()`, `pool-2-thread-1` (Rabia executor) parked WAITING, no BLOCKED thread, no deadlock.
- Senders' CONSENSUS stream to it is perpetually `backpressured or inactive` (`o.p.l.u.Retry` `attempt 1/200 @ 0.025S`, repeating every ~30s — never escalates to evict+reconnect).
- A **self-sustaining QUIC stream flow-control stall**: window exhausted at the sender, receiver not advancing it, but receiver has no work (idle) → neither side recovers. Sender retries the *same* stuck stream forever instead of evicting+reconnecting.
- **Correlated with a hung slice deployment** on the wedged node: last activity `Factory method echoSliceSlice returned promise, waiting for completion`; earlier `Failed to load slice test-echo-echo-slice: Artifact not found in any repository`. Deploy/slice-load runs on the **generation-snapshot (KV) observer path** (`NodeDeploymentManager` + `ClusterGenerationSnapshot`), NOT the Rabia apply path — so "slice-load gates consensus apply" is **plausible but unconfirmed** (no thread is blocked).

**What thread-dumps + logs CANNOT resolve:** why an *idle* receiver's per-stream flow-control window stops advancing. Needs **netty-level QUIC stream-state instrumentation** (per-stream window + auto-read on the receiver), correlated with the slice-deploy lifecycle.

### Next steps (in order)
1. **Add QUIC stream-state logging** on the receiver: per-CONSENSUS-stream flow-control window size + `isWritable`/auto-read state, logged when a sender first sees `backpressured or inactive`. Re-run `02 --skip-teardown`, find the wedged node, read the window state. This pins whether the window is genuinely 0 (receiver not consuming) vs a spurious `isWritable` flip.
2. **Investigate the slice-deploy correlate**: does a hung/failed slice deployment (`test-echo-echo-slice` artifact-not-found → factory-promise-await) gate or starve the consensus-stream consumption? Check whether `NodeDeploymentManager` / `SliceFactory` shares a thread or lock with the consensus read path, or whether the deploy FSM stuck-state matters. The artifact-not-found-then-found-at-/tmp suggests an artifact-propagation race worth its own look.
3. **Sender-side recovery**: `retryConsensusWrite(200× @ 25ms)` retries the same flow-control-stuck stream forever and never escalates to evict+reconnect. Even after the root is fixed, consider escalating a long-stuck CONSENSUS stream to a connection refresh (carefully — see dead-end #3; do not naively grace `isActive()`).

## Test-infra notes (bit us this session)
- **Zombie-container contamination**: `cleanup_cluster_zombies` leaves prior-run replacement containers behind ("survivors after sweep") → next run's Cluster B fails to form (360s timeout) → *silently invalidates the run*. **Always `ssh $TARGET_HOST 'docker ps -aq --filter name=aether-a-node --filter name=aether-b-node | xargs -r docker rm -f'` before a run.** Don't trust the harness sweep.
- **Live thread dump recipe** (PID 1 is a shell wrapper, not the JVM; `kill -3 1` is a no-op): `pid=$(docker exec <node> jcmd -l | grep -iE 'aether|node.jar' | awk '{print $1}'); docker exec <node> jcmd $pid Thread.print`. jcmd is at `/opt/java/openjdk/bin/jcmd`.
- **Docker is authoritative**; in-process spikes misled repeatedly. Single-instance + clean-slate + orphan-check per existing discipline.

## State of the captured cluster
A `02 --skip-teardown` capture run left Cluster B nodes up on `$TARGET_HOST` for forensics; being torn down at end of this session (handover preserves the findings).
