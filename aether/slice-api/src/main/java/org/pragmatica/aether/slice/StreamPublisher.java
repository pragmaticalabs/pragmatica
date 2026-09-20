// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;

import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.PublishOutcome;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// Application-facing stream publisher SPI.
///
/// Apps obtain a `StreamPublisher<T>` for application-namespace streams via constructor injection
/// against a `[streams.X]` resource declaration. The slice-runtime resolver MUST refuse to bind a
/// `StreamPublisher<T>` for any `system:*` address — apps cannot publish to system streams. See
/// {@link FrameworkStreamPublisher} for the framework-only system-namespace SPI.
///
/// Resolver-level enforcement uses {@link #ensureAppAddress(ResourceAddress)} as the canonical
/// belt-and-suspenders check. Once {@link ResourceAddress} is plumbed into the publisher factory
/// (separate wave), the factory's provision path invokes this check before constructing a
/// publisher.
public interface StreamPublisher<T> {
    Promise<Unit> publish(T event);
    /// Batch publish (#1342): one [PublishOutcome] per event, in input order, always `events.size()` long.
    /// The promise resolves with the list even when every event failed — a batch is not atomic, and a failed
    /// batch is not "nothing was written" (#1236: per-event outcome-unknown), so the outcome travels per event
    /// instead of being folded into one `Unit` that had acknowledged refused events as success. No default:
    /// a batch derived from `publish` could not report the offsets that landed.
    Promise<List<PublishOutcome>> publishBatch(List<T> events);

    /// Resolver-side fail-safe: refuse to bind an app `StreamPublisher` for a system address.
    ///
    /// Spec §6.1: the framework-vs-app boundary is a compile-time invariant via the sealed-SPI
    /// split. This runtime check is the second layer of defense for paths that bypass normal
    /// resolution (reflection, hand-edited blueprints, test harnesses).
    static Result<ResourceAddress> ensureAppAddress(ResourceAddress address) {
        if (address.isSystem()) {
            return StreamPublisherError.General.SYSTEM_ADDRESS_REFUSED.result();
        }

        return Result.success(address);
    }

    /// Failure cases for app publisher resolution.
    sealed interface StreamPublisherError extends Cause {
        enum General implements StreamPublisherError {
            SYSTEM_ADDRESS_REFUSED("StreamPublisher cannot be bound to a system-namespace address; use FrameworkStreamPublisher");
            private final String message;
            General(String message) {
                this.message = message;
            }
            @Override
            public String message() {
                return message;
            }
        }

        /// #1342: an event was not attempted because an earlier event of the same partition group failed. A
        /// group appends in order and stops at the first failure, so this event is NOT in the log.
        record PrecedingEventFailed(int partition, Cause cause, String message) implements StreamPublisherError {
            public static PrecedingEventFailed precedingEventFailed(int partition, Cause cause) {
                return new PrecedingEventFailed(partition,
                                                cause,
                                                "Not attempted: an earlier event of partition " + partition
                                               + " failed: " + cause.message());
            }
        }
    }
}
