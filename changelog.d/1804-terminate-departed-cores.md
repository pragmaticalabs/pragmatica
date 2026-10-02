### Fixed (2026-10-02 — #1804: departed cores' VMs were never terminated, silently)
- **`terminateDeparted` returned without a provider call and without a log line whenever the retirement check refused**, and nothing ever
  re-armed the reap, so a drained or replaced core's VM kept running and billing. Two refusal shapes were found in the first full JVM-runtime
  cloud run: a departing core reaped while still an installed voter (refused, never retried once voted out), and a new leader's activation
  replay selecting nodes it does not track, which `canRetireNode` refused because it required a membership descriptor.
  [mechanism: `ClusterTopologyManagerRecord.terminateDeparted`; `AetherNode.canRetireNode` / `retirementEligibleCore` at the base commit]
- A refused reap (departure, activation replay, drain-grace backstop) is now logged at WARN with the reason and **retried once per provisioning
  window, up to 60 times**, until the node is retirable. The chain stops when the node is terminated, when it shows life (parked for the next
  SWIM FAULTY, as an abandoned reap is), when its activation ends, or when the retries are spent, which ends in a WARN saying the instance may
  still be running. [verified: `ClusterTopologyManagerActuatorTest$RefusedReapRetry`]
- The retirement verdict now agrees with the replay's selection: a node **absent from membership** is judged by the core rule (not a current
  voter, a former voter or a fully-ready roster, no deployments) instead of being refused for having no descriptor. A current voter is never
  retirable, tracked or not. [verified: `aether/node/src/test/java/org/pragmatica/aether/node/CoreCandidateRetirementTest.java`]
- `ClusterTopologyManager.setRetirementAllowed(Predicate)` is replaced by `setRetirementRefusal(Function<NodeId, Option<String>>)` so the
  refusal carries its reason.
- The retry tick is gated on the activation replay's own protection (live, tracked or in flight). A provider failure at terminate is now
  logged at WARN with the provider's error and not retried (the activation replay re-selects a still-listed instance).
- **Lookups by node id are scoped to the cluster**: terminate, restart and `instancesForNode` ignore an instance labelled for another cluster,
  since core ids such as `hetzner-eu-core-1` repeat across clusters and the only match left could be another cluster's VM.
  [verified: `NodeLifecycleManagerTerminateTest`]
- **Not changed:** instances forgotten at the in-flight ceiling (#1787) are live members and are still not terminated.
