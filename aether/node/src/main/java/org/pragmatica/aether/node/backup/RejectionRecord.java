// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.backup;

import org.pragmatica.lang.Option;


/// STUB (red commit): renders nothing, parses nothing.
public record RejectionRecord(String lineageId, long incarnation, long revision, String incarnationId, String commit) {
    public static RejectionRecord rejectionRecord(String lineageId,
                                                  long incarnation,
                                                  long revision,
                                                  String incarnationId,
                                                  String commit) {
        return new RejectionRecord(lineageId, incarnation, revision, incarnationId, commit);
    }

    public String render() {
        return "";
    }

    public static Option<RejectionRecord> parse(String text) {
        return Option.none();
    }
}
