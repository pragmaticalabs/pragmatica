### Fixed (2026-09-19 — #1295: durable pub/sub subscribers never received the messageId; a 2-arg subscriber dead-lettered every event)
- **A context-carrying durable subscriber never received a single event.** The slice processor
  generates an adapter for a `(T event, MessageContext context)` subscriber that expects the runtime to
  hand it a `ContextualEvent`. The durable dispatcher (`StreamConsumerManager`) only ever passed the bare
  decoded event, so the generated adapter failed with a `ClassCastException`. The failure is lifted into a
  failed promise, so there was no crash, but every event was retried 5 times and then dead-lettered.
  `MessageContext.messageContext(` and `ContextualEvent.contextualEvent(` had no production caller, and
  `Projection.onEvent(event, MessageContext)` could never run in a deployed node.
- The dispatcher now builds a `MessageContext(messageId, topic, partition, offset)`. `messageId` is the
  publisher's envelope id (the idempotency key), `topic` is the topic address, and `partition` and
  `offset` identify the position the event was read from. It delivers the event through the new
  `SliceInvoker.invokeLocalWithContext` → `SliceBridge.invokeWithContext`. The subscribing slice's bridge
  picks the argument from the handler's **own declared parameter type**:
  - a generated 2-arg adapter declares `ContextualEvent` and receives `contextualEvent(event, context)`;
  - a 1-arg subscriber receives the bare event, exactly as before.

  No wire format, manifest, KV record or generated code changed. [mechanism:
  `DefaultSliceBridge.argumentFor` selects on `parameterType().rawType()`; pinned in-JVM by
  `DefaultSliceBridgeMessageContextTest`, `StreamConsumerManagerTest$TopicGroupDispatch`, which uses the
  real `DurableTopicPublisher` and asserts the publisher's own messageId arrives, and
  `GeneratedContextualSubscriberDispatchTest`, which runs against the real generated factory]
- The context is an in-process value, never on the wire. Context delivery is LOCAL by construction:
  `invokeLocalWithContext` resolves the slice exactly as `invokeLocal` does and fails, rather than
  forwards, when the slice is not local. [mechanism: `invocationHandler.localSlice(...)` or
  `SLICE_NOT_FOUND`; pinned by `SliceInvokerMessageContextTest`]
- **Not in this change:** the message `key` and delivery `attempt` are not part of `MessageContext`, and
  the durable envelope does not carry a key. Keyed-publish dedup therefore still cannot reach the
  subscriber. That is a follow-up ticket. [design intent — unverified: multi-node durable delivery of the
  context; the composed-path Forge suite `DurableTopicDeliveryForgeTest` remains `@Disabled`]
