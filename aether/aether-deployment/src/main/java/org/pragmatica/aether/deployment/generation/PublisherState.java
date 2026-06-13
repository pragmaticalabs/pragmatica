// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

public sealed interface PublisherState {
    record Disabled() implements PublisherState {
        public static final Disabled INSTANCE = new Disabled();
    }

    record Idle() implements PublisherState {
        public static final Idle INSTANCE = new Idle();
    }

    record Publishing() implements PublisherState {
        public static final Publishing INSTANCE = new Publishing();
    }

    record PublishingDirty() implements PublisherState {
        public static final PublishingDirty INSTANCE = new PublishingDirty();
    }
}
