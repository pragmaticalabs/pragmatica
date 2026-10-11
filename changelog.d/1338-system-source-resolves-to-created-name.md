### Fixed (2026-10-11 — #1338: system streams were addressed by a name they are not created under, and were writable from a slice once that was fixed)
- **A stream engine key is now the full `namespace:stream:version` address in every namespace, `system` included.**
  `StreamEngineKey.engineKey` used to reduce a `system` address to its bare name (`cluster-events`), but the
  framework creates, publishes and consumes the system stream as `system:cluster-events:1.0.0`. Two consequences
  followed: a blueprint `External` `system:` source bound a consumer to a ring nobody writes to, and the
  Management-API reads of a system stream (`/streams/system/cluster-events/1.0.0/info`, metadata) answered
  "Stream not found". Both now resolve to the real stream. A user stream that is merely named `cluster-events` is an
  ordinary stream: it is no longer served at the system address and no longer refused as the system stream.
  [mechanism: `BlueprintPublishOwnershipTest$StreamBindings#publish_externalSystemSource_resolvesToTheNameTheSystemStreamIsCreatedUnder`
  and `SystemStreamManagementReadTest`; reverting `engineKey` turns both red]
- **System streams are READ-ONLY to blueprints, slices and the Management API** (owner ruling, #1338). A `system:`
  `External` source stays allowed (spec `event-stream-namespaces-spec.md` §6.1, §11.2) and binds read-only. Every
  write into a `system:` stream through `StreamWriteRouter` (slice `StreamAccess.publish`, `StreamPublisher`
  `publish`/`publishBatch`, the Management-API publish) or `DefaultStreamPublisher` is refused with
  `StreamError.SystemStreamWriteRefused` (HTTP `405`) unless the writer holds `SystemWriteGrant`, a package-private
  capability only `SystemStreamFactories` hands to the node's own system publisher. Before this, binding the
  stream (the fix above) would have let a slice append to the real system ring.
  [mechanism: `SystemStreamWriteGateTest`; making the refusal a no-op turns its three refusal tests red, withholding
  the grant from the system publisher turns its control red]
- **A slice never creates a `system:` stream.** Binding one that the runtime has not provisioned fails with
  `StreamNotFound` instead of minting it under a reserved name or comparing the slice's replication declaration with
  the committed one.
- **Behaviour changes.** A catalog create in the `system` namespace (`POST /streams/system/<name>/<version>`) used to mint
  a flat bare-named operator stream; it is now refused `400` as a reserved kind. A body-carried bare name equal to a
  system stream's bare name is no longer treated as the system stream (it still cannot be created, being not a catalog
  address).
- **Forwarded writes.** The same refusal sits on the owner's appends, which every publish reaches (single, batch,
  forwarded and the materialize-then-retry path). A write forwarded to a `system:` stream is refused at the owner unless
  the frame carries the sending node's system-writer claim (`PublishForward#systemWriter`, set only by a node's own
  system publisher). The guarantee is therefore: non-system callers and origin-side omissions cannot write a system
  stream. It is not a defence against a peer that holds the cluster secret, which can forge the claim (pinned by
  `SystemStreamForwardPinsTest#honestyPin_aPeerThatSetsTheMark_landsASystemWrite_theClaimIsForgeable`).
- **A writing role on a `system` External source is refused at deploy** (rule `source-system-not-consumer`, gating),
  instead of at the first publish. `role = "producer"` or `"both"` is refused; `consumer` or no role is accepted.
