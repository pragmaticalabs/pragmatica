// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.topic;


/// Delivery context handed to a durable-topic subscriber alongside the event it is processing
/// (durable-pubsub-spec §8). A subscriber opts in by declaring the two-argument handler shape
/// `Promise<Unit> handler(T event, MessageContext context)`; the one-argument shape is the default
/// and is unaffected.
///
/// The four fields do NOT carry the same guarantee, and the difference is the whole point of the
/// type — read it before using any of them as a key:
///
/// **`messageId` — stable identity, usable as a deduplication key.** The publisher mints one KSUID
/// per `publish` and stores it in the event's envelope, so it identifies the EVENT rather than the
/// place a copy of it happens to sit. It is the §8 idempotency key: the key the idempotency aspect
/// extracts, and the `messageId` component of a projection's `(projectionName, generation,
/// messageId)` key. It is preserved across the dead-letter hop by construction — the same id is
/// carried by the live envelope and by the dead-letter envelope written from it — so a dead-lettered
/// event keeps the identity it was published with.
///
/// **`partition` / `offset` — position of THIS delivery, never an identity.** They locate the copy
/// being delivered right now in the source stream. A redelivery of the same event (§7: crash or
/// timeout after processing-before-ack, cursor-commit lag after ack) can present a different
/// position, and a group-targeted redrive out of the dead-letter queue (§9) re-injects the event
/// with a FRESH position while `messageId` stays put. Using them to deduplicate therefore admits
/// exactly the duplicates deduplication exists to stop. They are for diagnostics, lag reasoning, and
/// ordering within a partition — nothing that must survive a retry.
///
/// **`topic` — the canonical [org.pragmatica.aether.slice.resource.ResourceAddress] string**
/// (`namespace:name:version`), the same full routing identity the publisher resolved, not the bare
/// name a slice may have declared.
///
/// Status of the redrive half, stated so the guarantee above is not read as more than it is: the
/// identity property is on disk today (one id minted at publish, carried into the dead-letter
/// envelope). The §9 management triad that performs the re-injection is specified and not yet
/// shipped; when it lands, a redriven delivery carries this same `messageId` and a new
/// `(partition, offset)` — which is why this javadoc, and the key choice it mandates, are written
/// that way now rather than migrated later.
///
/// A context describes a delivery that already happened, so it is a carrier and not a validated
/// value object: it is built by generated adapter code from an envelope the runtime has already
/// accepted, and there is no failure for a slice to recover from at this point. This matches [Topic],
/// which is likewise a descriptor rather than a parsed value.
///
/// @param messageId publisher-assigned KSUID identifying the event; the §8 idempotency key, stable
///                  across the dead-letter hop
/// @param topic     canonical `namespace:name:version` address the event was published to
/// @param partition source partition of this delivery; positional, not stable across redelivery or
///                  redrive
/// @param offset    source offset of this delivery; positional, not stable across redelivery or
///                  redrive
public record MessageContext(String messageId, String topic, int partition, long offset) {
    /// Builds a context from an envelope's `messageId` and the source position the delivery was read
    /// from. Called by generated adapter code, not normally by hand-written slices.
    public static MessageContext messageContext(String messageId, String topic, int partition, long offset) {
        return new MessageContext(messageId, topic, partition, offset);
    }
}
