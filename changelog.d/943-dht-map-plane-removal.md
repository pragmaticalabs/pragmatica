### Removed (2026-09-11 — #943: the DHT `ReplicatedMap` routing plane was constructed, threaded through `AetherNode` and never read)
- **`AetherMaps` and the generic `ReplicatedMap` machinery under `aether/aether-dht` were unreachable,
  not merely unused.** The three routing maps (`endpoints`, `slice-nodes`, `http-routes`) were built at
  node start and passed into the DHT gossip handlers, but their accessors — `endpoints()`,
  `sliceNodes()`, `httpRoutes()` — had **zero production call sites**, so nothing ever wrote a key into
  them and nothing ever read one. The maps were permanently empty on every node.
- The remote-receive path could not populate them either. `NamespacedReplicatedMap.onRemotePut` and
  `onRemoteRemove` ignore any key that does not start with the map's `"<name>/"` prefix, and the only
  code that emits such a key is `put()`/`remove()` — reachable only through those same zero-caller
  accessors. No node could write a prefixed key, so no node's gossip dispatch could ever match one.
  [mechanism: `NamespacedReplicatedMap.onRemotePut` prefix guard vs. `prefixKey` called only from
  `put`/`remove`; `AetherMaps.dispatchRemotePut` is the sole production entry point]
- Deleted: `AetherMaps`, `ReplicatedMap`, `NamespacedReplicatedMap`, `CachedReplicatedMap`,
  `ReplicatedMapFactory`, `DhtBackedMapFactory`, `ReplicatedMapError`, `MapSubscription` and
  `DhtNodeCleanup`, with their five test classes, plus the dead `asHttpRouteSubscription()` adapter on
  `AppHttpServer` and the `AetherMaps` wiring in `AetherNode`.
- **`DhtNodeCleanup` never ran.** It was reachable only from its own test, not from SWIM DEAD detection
  or any other path; dead-node endpoint state is removed by
  `EndpointRegistry.unregisterEndpointsForNodeArtifact` off `NodeArtifactKey` removal, as it always was.
  [mechanism: zero non-test call sites at `7fa26e2ca`]
- **No guarantee changes, in either direction.** Routing, slice placement and endpoint selection were
  served throughout by the consensus KV (`DeploymentMap`, `HttpRouteRegistry`, `EndpointRegistry`) and
  keep their quorum-durable guarantee. The previously documented eventual/not-crash-durable downgrade
  for these three key types **never applied**, because no data was ever on that plane; the
  `known-limitations.md` entry claiming it has been withdrawn rather than restated.
- `aether/aether-dht` remains, and its module description now names what it actually holds —
  partition-ownership epoch fencing and replication cooldown.
- [unverified: no live-cluster run was performed for this change. The removal rests on static
  reachability — a repo-wide word-bounded search over tracked files found zero remaining `.java`
  references to any of the nine types (positive control `AetherNode` = 1069 hits, negative control = 0)
  — plus a green full-reactor build and the dead-surface gate, not on observed cluster behaviour.]
