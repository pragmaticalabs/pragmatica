// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.example.probe758;

import java.util.List;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;

/// #758 fixture: the ticket's consumer factory shape — its signature references
/// [SeatSellabilityProbe], which its loader cannot see.
public class BuyTicketProbeFactory {
    public static Promise<Slice> buyTicketProbeSlice(SeatSellabilityProbe ignored) {
        return Promise.success(new Slice() {
            @Override
            public List<SliceMethod<?, ?>> methods() {
                return List.of();
            }
        });
    }
}
