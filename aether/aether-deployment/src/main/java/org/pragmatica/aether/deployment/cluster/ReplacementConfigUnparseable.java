// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.lang.Cause;


/// #1543 F2: the committed cluster configuration does not parse, so the source of a node whose record carries none cannot be derived. Refused
/// rather than read as "no config" (which would commit the source "default" into the record for good); the message names the committed
/// configuration and the parser's reason, and reaches the operator through the replacement record's reason and the paused upgrade.
public record ReplacementConfigUnparseable(String parseFailure) implements Cause {
    @Override
    public String message() {
        return "the committed cluster configuration does not parse (" + parseFailure
               + "), so the source of a node whose record carries none cannot be derived; fix the committed configuration";
    }
}
