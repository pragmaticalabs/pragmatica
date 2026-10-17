// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;

import org.pragmatica.lang.Cause;


/// #1543 F2: the replacement of a node whose record carries no source cannot tell which source to provision from: the committed config has
/// no source declaring the role, or several. Refused rather than guessed (a guess could provision a cloud VM for a docker node); the message
/// names the role and the candidates, and reaches the operator through the replacement record's reason and the paused upgrade.
public record ReplacementSourceUnresolved(String role, List<String> candidates) implements Cause {
    @Override
    public String message() {
        return candidates.isEmpty()
               ? "the node's record carries no source and no source of the committed config declares role " + role
                + "; declare it in a source, or set AETHER_SOURCE on the node"
               : "the node's record carries no source and role " + role
                + " is declared by sources " + candidates
                + "; set AETHER_SOURCE on the node (aether cluster scaffold --source) so its replacement knows which";
    }
}
