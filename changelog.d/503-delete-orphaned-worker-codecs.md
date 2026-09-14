### Removed (2026-09-14 — #503: `WorkerCodecs` was an orphaned codec registry that made dead codecs look registered)
- **Decision: delete.** `WorkerCodecs.workerCodecs(SliceCodec)` had no production caller (the standalone
  worker runtime it assembled codecs for was removed long ago); a codec registered only there LOOKED
  registered and was not — the #492 mechanism, where `CommunityMetricsSnapshot` broadcasts threw
  `No codec registered` on the live mesh while its codec sat in this assembly. The ticket's precondition
  for deleting was an audit of the registry's exclusive types for live traffic; all five are traffic-less,
  each traced to its producer rather than to a grep count: `WorkerMutation` — sent by
  `MutationForwarder.forward`, reachable only from `WorkerDeploymentManager`, which `AetherNode` constructs
  into a local it never uses; `SnapshotRequest`/`SnapshotResponse` — sent by `WorkerBootstrap`, likewise
  constructed and dropped; `FollowerHeartbeat` — consumed by `FollowerHealthTracker`, which nothing
  references; `DHTRelayMessage` — sent by `WorkerDHTNetwork`, constructed nowhere. A wire type only
  the dead registry composed therefore never crossed the wire; wiring `WorkerCodecs` into a runtime
  that does not exist is not an option. `NodeCodecs` already carries everything that IS sent (the #492
  metrics types, plus its own byte-identical `InetSocketAddress`/`TimeSpan` codecs).
  [mechanism: the removal is enforced by the compiler (root `clean install`, 145/145) and by
  `WireAssignmentTripwireTest`, whose derived set no longer includes the five types — the re-recorded
  baseline diff is exactly those five `TAG` lines (`SnapshotRequest 128`, `SnapshotResponse 129`,
  `FollowerHeartbeat 85`, `WorkerMutation 89`, `DHTRelayMessage 90`) and nothing else, which is the
  reviewable delta #964 asks for; `SystemCodecPinningTest#workerCodecs_everySystemType_hasAHandAssignedTag`
  and the `DeadSurfaceCommissioningTest` `#503` positive control are retired with the registry]
- **What is deliberately left, and filed separately.** The four generated sub-registries
  (`MutationCodecsNode`, `BootstrapCodecsNode`, `HeartbeatCodecsNode`, `NetworkCodecsNode`), the five
  `@Codec` types they hold, their `SystemTags` pins (85, 89, 90, 128, 129) and the dead worker-runtime
  remnants that would produce them (`WorkerDeploymentManager`, `WorkerBootstrap`, `MutationForwarder`,
  `FollowerHealthTracker`, `WorkerDHTNetwork`) are now composed by nothing — the same "looks registered"
  shape one level down. Removing them is a worker-runtime deletion with its own wire-tag retirement,
  not a registry chore; ticket draft in the report.
- [unverified: the five types' absence from the wire is inferred from their producers being unreachable
  (constructed-and-dropped locals, unreferenced classes), not from a packet capture on a worker-mode
  node; a future change that drives `WorkerDeploymentManager` would fail loudly at `writeToStream`
  (`No codec registered`) rather than silently, which is the #492 symptom, now with no dead registry
  to hide behind.]
