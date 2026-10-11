// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.notification;

public record NotificationResult(String messageId, String backend) {
    public static NotificationResult notificationResult(String messageId, String backend) {
        return new NotificationResult(messageId, backend);
    }
}
