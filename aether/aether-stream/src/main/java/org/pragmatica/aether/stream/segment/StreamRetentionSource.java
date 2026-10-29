// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.segment;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.lang.Option;

/// The retention policy of a stream, by the name the segment index uses (#2201 3.2 item 2): in production the committed
/// config this node has applied. A stream it does not answer is WITHHELD from retention for as long as it does not
/// answer: a retention rule this node cannot read is not authority to delete (review R32-03; a node that has not yet
/// applied the committed config would otherwise delete in-retention history).
@FunctionalInterface
public interface StreamRetentionSource {
    Option<RetentionPolicy> policyOf(String streamName);

    /// Every stream answers `policy`.
    static StreamRetentionSource fixed(RetentionPolicy policy) {
        var answer = Option.some(policy);

        return _ -> answer;
    }
}
