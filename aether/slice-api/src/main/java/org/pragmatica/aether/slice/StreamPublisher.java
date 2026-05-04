// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.StreamAddress;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;


/// Application-facing stream publisher SPI.
///
/// Apps obtain a `StreamPublisher<T>` for application-namespace streams via constructor injection
/// against a `[streams.X]` resource declaration. The slice-runtime resolver MUST refuse to bind a
/// `StreamPublisher<T>` for any `system:*` address — apps cannot publish to system streams. See
/// {@link FrameworkStreamPublisher} for the framework-only system-namespace SPI.
///
/// Resolver-level enforcement uses {@link #ensureAppAddress(StreamAddress)} as the canonical
/// belt-and-suspenders check. Once {@link StreamAddress} is plumbed into the publisher factory
/// (separate wave), the factory's provision path invokes this check before constructing a
/// publisher.
public interface StreamPublisher<T> {
    Promise<Unit> publish(T event);

    default Promise<Unit> publishBatch(List<T> events) {
        return Promise.allOf(events.stream().map(this::publish)
                                          .toList()).mapToUnit();
    }

    /// Resolver-side fail-safe: refuse to bind an app `StreamPublisher` for a system address.
    ///
    /// Spec §6.1: the framework-vs-app boundary is a compile-time invariant via the sealed-SPI
    /// split. This runtime check is the second layer of defense for paths that bypass normal
    /// resolution (reflection, hand-edited blueprints, test harnesses).
    static Result<StreamAddress> ensureAppAddress(StreamAddress address) {
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

            @Override public String message() {
                return message;
            }
        }

        @SuppressWarnings("unused") record unused() implements StreamPublisherError {
            @Override public String message() {
                return "";
            }
        }
    }
}
