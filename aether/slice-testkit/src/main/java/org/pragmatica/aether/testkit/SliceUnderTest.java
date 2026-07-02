// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit;

import org.pragmatica.aether.resource.notification.Notification;
import org.pragmatica.aether.testkit.assertion.DbAssertions;
import org.pragmatica.aether.testkit.fake.HttpCall;
import org.pragmatica.lang.Contract;

import java.util.List;


/// Handle to a spun-up slice (spec §3.2). Exposes the typed client (G3) and the captured
/// side-effects — published "facts" (G4), notifications, outbound HTTP calls, and real DB rows.
///
/// Closing releases fakes and stops any provisioned containers; use with try-with-resources.
public interface SliceUnderTest<T> extends AutoCloseable {
    /// The slice's own generated interface — call its methods directly (no reflection, no codec).
    T client();

    /// Events published to `topic` through a registered [org.pragmatica.aether.testkit.fake.CapturingPublisher].
    <E> List<E> published(String topic);

    /// Notifications sent through registered capturing senders.
    List<Notification> notifications();

    /// Outbound HTTP calls recorded by the [org.pragmatica.aether.testkit.fake.FakeHttpClient] at `section`.
    List<HttpCall> httpCalls(String section);

    /// Row-assertion helper for the connector registered at `section` (container path).
    DbAssertions db(String section);

    /// Releases fakes and stops any provisioned containers; the try-with-resources boundary.
    @Override
    @Contract
    void close();
}
