// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

import org.pragmatica.lang.Promise;


/// Resource interface for sending notifications.
///
/// Provisioned via Aether's resource framework. Backed by either SMTP or HTTP vendor API
/// depending on configuration.
public interface NotificationSender {
    Promise<NotificationResult> send(Notification notification);
}
