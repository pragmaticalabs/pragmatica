### Fixed (2026-09-13 — #1052: a transient DHT encryption-marker timeout killed a joining node)
- **A node joining while the cluster's membership churned exited 30 s after joining.** #858's
  post-formation DHT encryption-marker check (`StorageFactory.verifyDhtMarkers`) treated any outcome
  except success as fatal. When the ring was still converging, the marker get/put timed out
  (`DhtMarkerCheckTimedOut`) or failed fast on `DHTError.QuorumNotReached`. `start()` failed and
  `Main#exitWithError` called `System.exit(1)`. Reproduced on docker: a replacement joining during
  `Kill_2_nodes` logged `DHT encryption-marker check for instance 'content' timed out after 30000ms`
  30.1 s after join and exited. That matches cloud deaths 35–41 s after join, which could not be
  confirmed on cloud because those logs went with their VMs.
- **Only a definite refusal is still fatal.** `EncryptedTierRequiresKeyring` (the marker read back and
  no keyring configured) fails `start()` after one attempt, and the exit path is unchanged
  (`Main#exitWithError`, exit code 1). [mechanism: the cause implements `Cause.Terminal`, and core
  `Retry` stops on `isTerminal()` without scheduling another attempt.] Anything else (a timed-out
  attempt, no quorum, an unreachable peer) is retried with exponential backoff: 1 s, doubling to a 30 s
  cap, jittered, with no give-up bound. Each attempt stays bounded at 30 s. Each failed attempt logs one
  WARN line naming the instance and the attempt number, through `StorageFactory`'s SLF4J logger (not
  `System.Logger`, #1077). [mechanism: `StorageFactory.verifyDhtMarker` runs `Retry` with
  `Integer.MAX_VALUE` attempts on `DHT_MARKER_RETRY_BACKOFF`; `attemptAndReport` counts attempts per call
  and emits the WARN.]
- **While the check is pending, the node stays in the cluster and is not ready.**
  - Every operation on the instance's DHT tier stays gated (#858 C1/#874) and is refused with
    `StorageError.TierNotAdmitted` after its 30 s admission bound. [mechanism: the gate is now resolved
    only by the final outcome. Before this fix, a timed-out attempt resolved the shared, first-writer-wins
    gate with its timeout cause, so a retry could never have admitted the tier.]
  - Lifecycle `ACTIVE` and reported `READY` wait for every gate to admit, so `/health/ready` answers 503.
    [mechanism: the NDM self-ready signal is composed with `StorageFactory.dhtAdmission`, built from the
    same gates.]
  - A new `dht-admission` readiness component names the instances still pending.
  - **Recovery:** a pending check clears on its own once the DHT answers; no operator action is needed.
    If `dht-admission` stays DOWN, the DHT cannot reach quorum for that namespace. Check cluster
    membership (`/api/v1/nodes`) and the node's WARN attempt lines. A refusal clears only by configuring
    the keyring the marker names, or by migrating to a fresh namespace (#831).
- **The check now runs after `armPeriodicTasks`, not before.** It can retry for as long as a ring takes
  to converge. `LeaderManager` picks the lowest id of the sorted topology, so a replacement still
  retrying can already be leader, and holding periodic work behind the check would disable that
  leader's periodic ticks. What must not precede verification stays enforced by the tier gate and the
  readiness gate, not by this ordering. [mechanism: `PeriodicTasks#isCancelled` is the loop's stop signal.
  A stopped node's next attempt ends the loop with the terminal `EncryptionError.DhtMarkerCheckAbandoned`.
  In practice only `stop()` raises that signal: the other canceller, the failed-boot guard, runs at
  construction, before the loop exists.]
- **A stop during the retry is not a boot failure.** SIGTERM while the check is still retrying runs the
  shutdown hook, whose `stop()` ends `start()` with `DhtMarkerCheckAbandoned`. `Main` now logs that at
  INFO and lets the hook own the exit, instead of `Failed to start node` at ERROR plus a second
  `System.exit(1)`. Every other start failure still exits 1. [mechanism: `Main.onStartFailure` matches
  the abandon cause; pinned by `MainShutdownTest$StartFailure`.]
- **"Could not read" is never treated as "marker absent".** [mechanism: `DistributedDHTClient.get`
  returns an absent value only after an R-set quorum answered with a miss. Too few live replicas fail
  fast with `QuorumNotReached`, an empty target set fails with `NO_AVAILABLE_NODES`, and an unanswered
  quorum fails with the operation timeout. The only path that turns a failure into an absent value is
  the post-miss fallback probe of replicas outside the R-set, which runs after the quorum has already
  answered "absent". That is pre-existing #428 behaviour and this fix does not change it.]
- **Test coverage, below the `[verified:]` bar.** None of these tests is multi-node or live-path.
  - `StorageFactoryDhtMarkerRetryTest` (7) drives the assembled `StorageInstance` against a scripted
    DHT client. It includes a read refused with `TierNotAdmitted` after the real 30 s admission bound
    while the check keeps retrying, and captures the per-attempt WARN through log4j2: three failed
    attempts give exactly three WARNs, each naming the instance and its attempt number
    (`verifyDhtMarker_warnsOncePerFailedAttempt_namingInstanceAndAttemptNumber`; WARN demoted to DEBUG,
    or the attempt number dropped, reds it). Its no-DHT-client admission case now creates an instance,
    so the "no marker check anywhere" assertion is not vacuous.
  - `AetherNodeDhtMarkerPostFormationBootTest` (5) runs a real self-forming single-node `start()`: the
    refusal still fails `start()`, periodic work arms before the check settles, a node whose tier
    is not admitted never reaches `ACTIVE` while an admitted control does, and `/health/ready`'s
    `dht-admission` component reads the REAL node's `storageSetups()` -- DOWN naming `artifacts` while
    its gate is unresolved, UP once `start()` admitted it
    (`readiness_reportsDhtAdmissionDownNamingArtifacts_beforeStart_andUpOnceAdmitted`; a `List.of()`
    at the wiring reds it, which `StatusRoutesDhtAdmissionTest`'s `Map.of()` stub cannot see).
  - `StatusRoutesDhtAdmissionTest` (3), `PeriodicTasksTest` and `MainShutdownTest$StartFailure` (2)
    cover the rest.
  - Red on the unmodified base for the tests that compile there; mutation probes per hunk are in the
    PR description and the fix-round reports.
- **What is NOT covered:** no cloud or multi-node docker run of the fix; the docker repro is the
  evidence for the defect only. [design intent — unverified: a replacement joining under
  `Kill_2_nodes` churn now verifies and becomes ready once the ring converges.] `Promise.allOfOrCancel`
  in `StorageFactory.verifyDhtMarkers` cancels sibling checks on a definite refusal. A cancelled
  sibling's retry loop then runs until the node's stop signal fires. In production that is `Main`'s
  exit; in Ember it is `abortStart`'s stop.
