// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.fake;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.notification.Notification;
import org.pragmatica.aether.resource.notification.NotificationResult;
import org.pragmatica.aether.resource.notification.NotificationSender;
import org.pragmatica.lang.Promise;


/// Capturing [NotificationSender] — records every `send(...)` and returns a deterministic scripted
/// [NotificationResult]. Generalizes ad-hoc `Stub*` notification doubles (spec §3.3).
public final class CapturingNotificationSender implements NotificationSender {
    private static final String DEFAULT_BACKEND = "capturing";

    private final List<Notification> sent = new CopyOnWriteArrayList<>();
    private final AtomicInteger sequence = new AtomicInteger();
    private final String backend;

    private CapturingNotificationSender(String backend) {
        this.backend = backend;
    }

    public static CapturingNotificationSender capturing() {
        return new CapturingNotificationSender(DEFAULT_BACKEND);
    }

    public static CapturingNotificationSender capturing(String backend) {
        return new CapturingNotificationSender(backend);
    }

    @Override
    public Promise<NotificationResult> send(Notification notification) {
        sent.add(notification);

        return Promise.success(NotificationResult.notificationResult("test-" + sequence.incrementAndGet(), backend));
    }

    /// Notifications sent so far, in send order.
    public List<Notification> sent() {
        return List.copyOf(sent);
    }
}
