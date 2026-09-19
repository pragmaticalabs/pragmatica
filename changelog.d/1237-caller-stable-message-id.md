### Added (2026-09-19 — #1237: durable topic publish had no caller-stable message ID)
- **Every `DurableTopicPublisher.publish(T)` minted a fresh KSUID message ID**, so when the application
  retried a publish that may already be in the log (an outcome-unknown result, #1236), the retry was
  written as a second event with a new ID that no message-ID dedup — projection claims, the
  idempotency aspect — could match to the first.
- New overload `Publisher.publish(T message, String idempotencyKey)`. On a durable topic the key
  becomes the envelope's `messageId`, so a retry of the same logical event with the same key carries
  the same identity. A blank key is refused before anything is published.
  [mechanism: `DurableTopicPublisher.publish(T, String)` builds the `TopicEventEnvelope` from the key;
  pinned by `DurableTopicPublisherTest.publishWithKey_retryAfterOutcomeUnknown_reusesMessageId`]
- Reachable from slices without codegen changes: slices already receive `Publisher<T>` or the
  `TypedPublisher` facade codegen wraps it in, and `TypedPublisher` forwards the key.
  [mechanism: `TypedPublisher.publish(T, String)` delegates the keyed overload; pinned by
  `TypedPublisherTest.publishWithKey_forwardsKeyToWrappedPublisher`]
- The default implementation ignores the key and calls `publish(T)`, which is exact for the ephemeral
  RPC tier (no log, no message ID). A publisher that wraps another publisher must override it or the
  key is lost.
- **Retry after `PublishOutcomeUnknown` is dedup-safe only through the keyed overload with the same
  key** — stated in the `Publisher` javadoc and `durable-pubsub-spec.md` §5/§8. [unverified: no
  multi-node run exercised a keyed retry end to end; the evidence is in-JVM]
