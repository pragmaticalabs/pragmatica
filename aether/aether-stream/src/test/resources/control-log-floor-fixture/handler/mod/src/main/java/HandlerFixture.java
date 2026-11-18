// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
// Fixture for ControlLogFloorTripwireTest: one 4-argument (ControlLog) call whose last argument carries a generic comma,
// one 7-argument call, one comment, one declaration. Exactly one of them is a hit.
class HandlerFixture {
    // StreamForwardHandler.streamForwardHandler(self, manager, transport, controlLog) in a comment does not count
    static StreamForwardHandler streamForwardHandler(NodeId self, int x) { return null; }

    Object build() {
        var sevenArguments = StreamForwardHandler.streamForwardHandler(self, manager, transport, 1L, metrics, Option.none(), Option.none());
        return StreamForwardHandler.streamForwardHandler(self, manager, transport, Holder.<Map<String, X>, Y>get());
    }
}
