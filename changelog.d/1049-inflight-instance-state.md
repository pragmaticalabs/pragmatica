### Fixed (2026-09-13 — #1049: auto-heal re-provisions a replacement it is still waiting on)
- **The leader forgot an in-flight cloud replacement 45s after minting it and minted a duplicate.** The
  in-flight entry expired at `split_timeout × 3` (45s at the default) while a Hetzner JVM replacement takes
  50–63s from mint to membership, so the deficit re-opened, a second node was minted, and the surplus was
  then drained as `OVERPROVISION_PARTITION_HEAL`.
- In-flight replacements are now tracked by what the compute provider reports about the instance, not by
  a timer. The leader keeps the entry while the provider lists the instance as provisioning or running. It
  drops the entry when the provider reports the instance stopped, terminated or failed, or when the
  provider no longer lists an instance it has already listed. After a drop, the deficit re-opens and
  re-dispatches after the normal deficit debounce.
  [design intent — unverified: unit-tested on the real reconcile path with a fake provider in aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/membership/ntt/LeaderReconcilerTest.java; no multi-node or cloud run yet]
- **A replacement the provider has never listed is taken as deleted only on repeated evidence.** Twelve
  consecutive successful listings must omit it, and three minutes must have passed, both counted from when
  its create call resolved (or from when a new leader inherited it). A listing that fails, or a status the
  provider cannot state, counts toward neither. So a listing that lags a create by less than three minutes
  never mints a duplicate, however long the create itself took. An instance lost before it was ever listed
  is re-dispatched about three and a half minutes after its create resolved. Twelve is three minutes of
  polls at the default `split_timeout`; three minutes is a judgement set against AWS's advice to retry a
  Describe "up to a few minutes" after a create and Azure Resource Graph's documented staleness, not a
  measured lag.
  [design intent — unverified: unit-tested in aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/membership/ntt/LeaderReconcilerTest.java; live provider listing lag was not measured]
- **Provider statuses that say neither "coming" nor "going" no longer read as terminated.** Hetzner
  `unknown`, GCP `REPAIRING`, Azure `PowerState/unknown` and `Canceled`, and any status a provider mapping
  does not recognise now read as unknown. Infrastructure readiness keeps polling such an instance until its
  timeout, and the auto-heal tracker keeps the replacement until its ceiling, instead of dropping it and
  minting another while it still exists.
  [design intent — unverified: each provider's mapping is pinned by a unit test over its documented states; readiness by aether/environment-integration/src/test/java/org/pragmatica/aether/environment/ComputeProviderReadinessTest.java]
- **On Azure the auto-heal listing now reports a VM's actual state.** Resource Graph rows were read as
  running whatever the VM was doing, so a failed or deallocated VM held its slot for the whole ceiling. The
  power state is now read first, then the provisioning state; a VM whose provisioning Azure reports
  `Failed`, with no power state, reads as failed and is re-dispatched after the debounce.
  [design intent — unverified: unit-tested on the row mapping; the Resource Graph property paths were not checked against a live query]
- A hard per-source ceiling bounds every in-flight replacement, including one whose provider cannot
  answer. It is set by the new `[source.<name>] replacement_ceiling` key (default `10m`) and read from the
  cloud source that backs the replacement. It is refused at load on non-cloud sources (PF-26), when it is not
  a positive duration, and when it does not exceed `5m` — the three-minute first-listing floor plus a
  two-minute join allowance. The leader evicts past the ceiling whatever the provider reports, so a shorter
  one would re-dispatch replacements that are still booting.
  [design intent — unverified: unit-tested in aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/cluster/ClusterTopologyManagerReplacementStateTest.java and the aether-config parser, validator and diff tests]
- A new leader asks the provider about the replacements it inherits from the prior leader, instead of
  ageing them out. Their ceiling keeps running from the mint time each replacement's node id carries (the
  timestamp of its ULID), so a leader change does not restart it; an id without a ULID (a configured
  `<prefix>-<ordinal>` id) restarts it, and a mint time in the future counts as now. A new leader whose clock
  runs ahead of the prior leader's ends the ceiling early by that skew. The absence count and the
  three-minute floor start afresh at inheritance.
  [mechanism: the inherited set carries node ids, a minted node id carries its mint time, and the provider lookup is by the node-id tag stamped at create, so no wire or KV change is involved]
- A replacement is also given up on before its ceiling when the provider's infrastructure readiness check
  fails the provision — on a cloud, after five minutes still provisioning (`ReadinessPolicy.cloudDefault`).
  It is then re-dispatched after the debounce.
  [mechanism: a failed provision call removes the in-flight entry; pinned by `LeaderReconcilerTest$ProvisionDispositionWedgeFix#dispatch_genuineProvisionFailure_removesInFlightPlaceholder`]
- Provider status is polled once per in-flight replacement every `split_timeout`, on the leader only,
  and never from a reconcile pass. A slow provider cannot pile up queries: one is outstanding at a time.
- **AWS, GCP and Azure node-id lookups matched nothing, and fixing them is part of this fix.** Those
  providers stamp the `aether-node-id` tag but passed the upper-layer `aether.node-id` key through
  untranslated. Without the translation, every in-flight replacement on those clouds would read as deleted
  and be re-dispatched, which is the duplicate-mint storm this entry fixes. **This widens what terminate and
  restart by node id reach:** on those clouds they now find and act on the VM, where before they failed to
  find an instance.
  [design intent — unverified: each provider's filter builder is pinned by a unit test in its `*ComputeProviderTest`; no call against a live cloud API]
- **Known limitation — a replacement the leader gives up on is not terminated.** When the provider reports it
  failed, when its ceiling passes, or when its readiness check fails, the in-flight entry is dropped and a new
  replacement is minted, but the old instance keeps running and billing. Recovery: every auto-heal create logs
  a WARN `CTM: auto-heal PROVISIONED a billable instance … instanceId=<id>, nodeId=<id>`; delete an instance
  whose node never joined by that `instanceId`, or through the `aether-cluster` label sweep at teardown.
  [mechanism: none of the three drop paths calls terminate]
- Operator action: none required. To wait longer for slow boots (for example a container runtime pulling
  an image on a fresh VM), raise `replacement_ceiling` on the cloud source.
