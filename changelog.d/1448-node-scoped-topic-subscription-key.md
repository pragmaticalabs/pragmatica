### Fixed (2026-09-23 — #1448: every instance of a slice shared one topic-subscription row, and any one instance's unload removed it)

- **`AetherKey.TopicSubscriptionKey` carried no node component, so N instances of a slice collapsed
  into ONE KV row and `NodeDeploymentState.buildTopicSubscriptionRemoveCommand` deleted that shared
  row on ANY single instance's unload.** Descaling one instance therefore un-declared the durable
  consumer group **cluster-wide**: `TopicGroupDeclarationSource` synthesises every node's declaration
  from those rows, so every surviving node detached its consumer on its next reconcile pass. Nothing
  reported it — a dropped declaration has no status row, so there was no gap counter, no WARN and no
  operator-queryable surface; recovery only arrived when a scale-up re-`Put` the row. The same row
  backs ephemeral topic routing, where the symptom was `TopicPublisher.publish` returning **success
  with zero deliveries** plus one rate-limited WARN.
- **The key is now `(address, artifact, methodName, nodeId)`** and its string form is
  `topic-sub/{namespace}/{topic}/{version}/{artifact}/{method}/{node}` — the node last, so the
  addressing prefix stays intact for prefix matching. One row per (subscription, instance) means an
  unload removes exactly the unloading node's record.
  [verified: `aether/aether-deployment/src/test/java/org/pragmatica/aether/deployment/node/fsm/NodeDeploymentStateTopicSubscriptionNamespaceTest.java`]
- **The declaration is per-GROUP, not per-instance**, so `TopicGroupDeclarationSource` deduplicates:
  N node-scoped rows map to the byte-identical `ConsumerDeclaration`. Placement already absorbed
  duplicates; the un-deduplicated leak was `topicGroupStatuses`, which would have reported N identical
  status rows for one group.
- **Durable cursors do not fork.** No cursor-keying type carries a node — `SubscriptionKey(stream,
  partition, group)`, `DurableGroupIdentity.groupId(artifactBase, method)` and
  `StreamCursorCheckpointKey(stream, partition, group)` are all node-free — and the node is dropped at
  the declaration boundary before any of them is reached. The group owns the cursor, not the instance
  (durable-pubsub-spec §6).
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/stream/StreamConsumerManagerTest.java` — `twoInstancesOfOneSlice_shareOneCursor_notOnePerInstance`]
- **The survival assertion that did not exist in any form now does**:
  `descaleOfAnotherInstance_leavesThisNodeAttached` — one of several instances unloads and the durable
  group stays declared, consumed and status-carrying on the surviving node. It replaces the enabled
  tripwire shipped under #1389, which asserted the defective behaviour and whose failure message
  instructed its reader to delete it and enable this inverse.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/node/stream/StreamConsumerManagerTest.java`]
- **The ephemeral routing path needed the same treatment and gets it from the same change**: two
  instances of one slice are now two subscriptions in one round-robin group, so a publish alternates
  across them instead of only ever reaching the last writer, and one instance's unload leaves the
  other routable.
  [verified: `aether/aether-invoke/src/test/java/org/pragmatica/aether/endpoint/TopicSubscriptionRegistryTest.java`]

**WIRE BREAK — restart nodes clean; there is no migration path and none is needed (pre-GA, no users).**
`TopicSubscriptionKey` gains a record component, and the generated codec body is a positional
concatenation with no field count and no skip metadata, so a peer running the previous build
**desynchronises rather than ignoring the change** — and because the key is nested inside a Map, the
enclosing decode misparses **without raising**, which `Result.lift` cannot catch. The live vector is a
mid-life snapshot install between mixed-version nodes during a rolling restart. The wire TAG (1135) is
unchanged: it is derived from the type name, which did not change.
[mechanism: `aether/node/src/test/resources/wire-assignment-baseline.txt` records the one changed SHAPE
line; `WireAssignmentTripwireTest` reddened on it and `SystemCodecPinningTest` confirms the tag held]

- **Removed** `TopicSubscriptionRegistry.TopicSubscription.toKey()`. It had no callers anywhere in the
  repo and built a key from the node-less shape that was the defect — an unpinned constructor of the
  wrong key. The record carries all four components if it is ever needed again.
