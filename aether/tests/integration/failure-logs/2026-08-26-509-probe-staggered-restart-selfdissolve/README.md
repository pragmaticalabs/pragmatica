# 2026-08-26 — #509 probe run 1: ghost-detector self-fence cascade (#642)

Run: `PostRestartSlowRejoinDeficitFillProbeTest` (uncommitted, with the EmberCluster
held-back-start seam), HEAD ec20a0dd2 + working tree. Command:
`mvn -f aether/forge/forge-tests/pom.xml verify -Dit.test=PostRestartSlowRejoinDeficitFillProbeTest -Dfailsafe.excludedGroups=`
Run hung (cluster dead under it); JVM killed manually after 45 min (the failsafe fork
timeout also failed to reap it — separate harness note).

> **CORRECTION HISTORY.** The first version of this README attributed the cascade to
> "cold-boot suppression lifting before the QUIC single-dialer grace allows SWIM
> confirmation". Investigation (recorded on #642) REFUTED all three legs of that
> hypothesis: all trio links formed within 1.0s of restart (log:1501-1525), SWIM reached
> full trio membership by 18:31:31 (log:1622-1627), and the 60s dialer grace never bound.
> What follows is the pinned mechanism.

## Pinned mechanism (#642): ghost QuorumLossDetectors from the PREVIOUS incarnation

`QuorumLossDetector` has no stop() and its timers live on the process-wide SharedScheduler;
`AetherNode.stop()` never quiesces it (it DOES stop the neighboring coreAbsenceDetector,
#590, for exactly this shared-JVM hazard). After the old nodes stopped at 18:31:03-20,
each old detector's frozen below-threshold member count kept re-arming every 15s until its
75s-from-OLD-boot window expired, then fired `QUORUM_LOSS drain INTENT` — and
`EmberCluster.handleSelfDrain` resolves `nodes.remove(id)` + `.stop()` against the id-keyed
LIVE registry, so each ghost murdered the NEW incarnation of its own id:

- log:2285 (18:32:09, old node 2's chain: stop 18:31:09.82 + arithmetic in #642) kills live slowjoin-2
- log:2663 (18:32:11, old node 1's chain) kills live slowjoin-1
- log:4955/4964 (18:32:41) — node 3's fence is the only GENUINE one: with 1 and 2 murdered
  it really was a minority (log:3842, 3900). Correct behavior, wrong world.
- Held-back 4/5 were NEVER STARTED: `startHeldBackNodes()` (probe line 375) was never
  reached — the probe hung awaiting a cluster that was being killed under it. Their 45-min
  snapshot ticking is a created-but-unstarted node running periodic tasks scheduled at
  ASSEMBLY time (defect 3 below).

Secondary real defects surfaced (neither caused this run's failure):
1. `swimBootAtMs` is assembly-anchored (AetherNode:2649-2653): a node started >75s after
   creation boots with ZERO cold-boot protection.
2. `periodicTasks` scheduling runs inside assembleNode (AetherNode:3592-3607), so a
   created-but-unstarted node performs live recurring work.
3. Probe defect: no fail-fast on a started node dying; it awaited 5/5 for 45 minutes.

## #509 verdict evidence (stands — deficit-fill premise did not fire)

Every LeaderReconciler pass suppressed provisioning; zero provision() calls:
- log line 870  (18:31:06) 5/5 formed:        reason=NO_DEFICIT
- line 971/1030 (18:31:09/11, teardown decay): reason=WITHIN_DEBOUNCE
- line 2683/3393 (18:32:11/20, trio):          reason=WITHIN_DEBOUNCE (deficitAge to 8.3s)
- line 4976/5129 (18:32:42/57, node 3 view):   reason=COLD_START_NOT_FULL (deficitAge 15s)

CAVEAT: the staggered-REJOIN half of the #509 scenario was never exercised (held nodes
never started), so #509 stays open until #642's fix lands and this probe reruns green
end-to-end (owner ruling on #509, 2026-08-26).

Production relevance of #642: HIGH for the in-JVM harness (every stop-and-restart forge
test leaves an armed ghost fence — false-red generator and result masker), LOW for
production (jvmExit = halt(2); process death kills the ghost) [design intent — unverified].
