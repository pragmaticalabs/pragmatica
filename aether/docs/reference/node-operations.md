# Node Process Operations

Operator reference for the `aether-node` process itself — how it starts, how it stops, and what its
exit code tells a supervisor (systemd, Docker, Kubernetes) about what happened. This is distinct from
the cluster-level DRAIN shutdown in [Management API](management-api.md#post-apiv1nodesshutdownid)
(`POST /api/v1/nodes/shutdown/{id}`), which asks a node to self-drain over the membership channel and
falls back to a container-manager grace-terminate reap if the node never exits on its own. The page
below covers what happens *inside* the process once a stop signal — from either path, or a direct
`SIGTERM`/`Ctrl+C` — reaches its JVM shutdown hook.

## Exit codes

| Code | Meaning | Where |
|------|---------|-------|
| `0` | Clean exit — `node.stop()` completed within the shutdown bound. | `Main#shutdownNode` |
| `1` | Fatal startup failure — a boot-time gate refused to proceed. Causes: `AETHER_CLUSTER_NAME` unset, the container's `aether.cluster` label disagreeing with the configured cluster name, `AETHER_INSECURE_DEV_MODE=true` combined with real operator TLS certificates, or `node.start()` itself failing. The log line immediately before exit names the specific cause. | `Main#exitWithError`, `Main#verifyClusterLabelConsistency`, `Main#enforceClusterNamePresent` |
| `2` | Drain-completed self-exit — the membership v2 drain procedure (spec §8.2) ran its sequence to completion (stop accepting new app-layer work, quiesce in-flight requests, emit SWIM `LEAVE`) and halts the process itself once done. This is a **normal**, expected exit for a node that was told to drain, not a failure. | `AetherNode#aetherNode` (default `jvmExit` callback), `DrainProcedure` |
| `3` | Shutdown did not complete within the bound (see below) — the process was killed while still trying to stop. | `Main#shutdownNode` |

A supervisor should treat `1` as "do not restart with the same configuration" (it will fail
identically), `2` as a normal drain-completed exit — safe to restart on a fresh node identity per the
membership spec, and `3` as "the process was killed while still trying to stop" — safe to restart, but
worth checking the thread dump the process logged first.

Codes `2` and `3` used to collide (both were `2`) before #838 review round 1 separated them; see the
halt-not-exit note below for why the collision mattered beyond naming.

## Shutdown bound (#749, #750, #838)

`Main#shutdownNode` runs from the JVM shutdown hook (registered via
`Runtime.getRuntime().addShutdownHook`, so it fires on `SIGTERM` and on `Ctrl+C`) and calls
`node.stop()`, then waits up to **30 seconds** (`Main#SHUTDOWN_TIMEOUT`) via `Promise#await(TimeSpan)`.

- If `node.stop()` settles within 30s: its result is logged (a failure result is logged at ERROR but
  is **not** itself a non-zero exit — the process still exits `0`, since the shutdown sequence itself
  ran to completion). `[design intent — unverified]`
- If it has **not** settled within 30s: the process logs an ERROR naming the timeout, dumps every live
  thread (name, state, lock, full stack, including virtual threads — via
  `HotSpotDiagnosticMXBean#dumpThreads`) at ERROR level, flushes the log4j2 context synchronously, and
  calls `Runtime.getRuntime().halt(3)`.
  `[verified: core/src/test/java/org/pragmatica/lang/PromiseAllOfCarrierStarvationTest.java` — proves the
  underlying bounded-timeout mechanism `Main#shutdownNode` relies on holds under carrier saturation;
  `[verified: aether/node/src/test/java/org/pragmatica/aether/MainShutdownTest.java]` — pins that the
  expiry path flushes logs before halting, with its own exit code, via an injected seam; the real 30s
  wall-clock wait itself is `[design intent — unverified]`, not covered by an end-to-end test]`.

**Why `Runtime.getRuntime().halt(3)` and not `System.exit(3)`:** `System.exit()` runs the JVM's own
shutdown-hook machinery — but this code already runs *from inside* a shutdown hook, on the thread that
`Shutdown.runHooks()` uses to run every hook to completion. `System.exit()`'s `Shutdown.exit()` blocks
waiting for that same thread's hook-running lock, so the hook thread joins itself and the process
hangs — proven by driving this exact call and observing the process survive past two minutes,
ignoring `SIGTERM`, until a `SIGKILL`. `Runtime.getRuntime().halt(int)` stops the JVM immediately
without running the shutdown-hook machinery at all, the same mechanism the membership v2 drain
procedure already uses for its own self-exit (code `2`, above) — at the cost of running no further
hooks and flushing no log appenders on its own, both of which `Main#shutdownNode` therefore does
explicitly, synchronously, immediately before the halt call.

**Why 30 seconds:** generous relative to `EmberCluster`'s 10-second per-node stop bound in tests (that
bound covers one node among several stopping in parallel; this bound covers a single node stopping
alone), while still short enough that a container orchestrator's own stop/restart grace period is
violated loudly — an ERROR log, a full thread dump, and exit `3` — rather than the previous behavior,
an unbounded hook that could park forever with no signal to the operator at all.

**What the thread dump does and does not tell you:** it is the raw material for diagnosing *which*
call inside `node.stop()` blocked. As of this writing that question is explicitly **open** — #749's
root-cause investigation established that the hang mechanism exists (virtual-thread carrier
starvation could make even a `.timeout()`-guarded operation fail to fire, which #749/#750's fix
addresses at the `Promise` layer) but did not identify a specific blocking call inside
`AetherNode#stop()` from a real occurrence. The dump is the mechanism that will eventually answer that
from a live incident, not a diagnosis already reached here.

## Related

- [#749](https://github.com/pragmaticalabs/pragmatica/issues/749) — shutdown hook had no bound at all.
- [#750](https://github.com/pragmaticalabs/pragmatica/issues/750) — `Promise#timeout()`'s own delayed-failure
  task shared a carrier pool it could be starved out of, which the bound above depends on.
- [#838](https://github.com/pragmaticalabs/pragmatica/pull/838) — review round 1 found the original fix's
  `System.exit(2)` deadlocked from inside the shutdown hook, and that it collided with drain's own exit
  code `2`; this page's exit-code table and halt-vs-exit explanation reflect that round's fixes.
- [Management API — Node shutdown](management-api.md#post-apiv1nodesshutdownid) — the cluster-coordinated
  DRAIN path, distinct from the local JVM shutdown hook this page describes.
