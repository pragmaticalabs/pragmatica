### Fixed (2026-10-04 — #1944: a publish to a node that had not applied the stream's config answered 500)
- **`POST /api/v1/streams/.../publish` to a node whose copy of the stream is not yet the committed life answered
  `500 {"detail":"Stream config not yet visible on this node: <stream>"}`.** The refusal is made by `committedLife`
  before anything reaches the ring, the WAL or a replica, and it clears within seconds (a freshly started or replaced
  node), but a 500 told clients it was permanent. Only two pre-append refusals reached the Management publish funnel's
  503 mapping (`NOT_ENOUGH_REPLICAS` and a forwarded `RemotePublishRetryable`).
- **The mapping now also covers the owner-local siblings that refuse before any append:** the not-yet-visible config
  (`StreamConfigNotYetVisible`), an owner that has not finished promotion (`OwnerNotActivated`) and a node that is not
  the committed owner (`NotOwnerAppend`) answer `503` (`PublishRetryable`: "refused before writing, retry: <cause>").
  It stays an allow-list, not "any transient cause": a transient cause can follow a write, and `PublishRetryable` claims
  the opposite. No `Retry-After` is added (none on this path since #1735).
- Pinned by `StreamApiRoutesPublishPartitionTest`: the real publish route with the write router refusing as
  `committedLife` does, the two siblings, and a control that a timeout, `StreamNotFound` and `EventTooLarge` keep their own cause.
