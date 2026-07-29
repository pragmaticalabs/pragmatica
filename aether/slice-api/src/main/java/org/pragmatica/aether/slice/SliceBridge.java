// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import java.util.List;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.SliceCodec;


public interface SliceBridge {
    Promise<byte[]> invoke(String methodName, byte[] input);
    Promise<Unit> start();
    Promise<Unit> stop();

    default Promise<byte[]> encode(Object input) {
        return BridgeError.ENCODE_NOT_SUPPORTED.promise();
    }

    default Promise<Object> decode(byte[] bytes) {
        return BridgeError.DECODE_NOT_SUPPORTED.promise();
    }

    ClassLoader classLoader();
    List<String> methodNames();

    /// The codec this slice encodes and decodes with — its OWN codec, layered over the node codec,
    /// so it resolves the application's declared types as well as framework ones. Exposed so
    /// operator-facing diagnostics can answer "can this event type actually be published?" against
    /// the codec the slice's resources really use rather than the node-wide one (#526). Bridges
    /// that carry no codec (stubs, non-default implementations) return none.
    default Option<SliceCodec> sliceCodec() {
        return Option.none();
    }

    /// The per-injection-point system-observability cell for `methodName` (#277 increment 2), resolved
    /// at the interceptor dispatch sites so a call flows through `cell.around(...)`. Bridges that carry
    /// no cells (stubs, non-default impls) return none and the dispatch runs untouched.
    default Option<ObservabilityStrategyCell> observabilityCell(String methodName) {
        return Option.none();
    }

    /// Every observability cell this bridge holds — the full per-method set, handed to the
    /// ObservabilityCellRegistrar at slice load (register) and unload (deregister). Empty for bridges
    /// that mint no cells.
    default List<ObservabilityStrategyCell> observabilityCells() {
        return List.of();
    }

    enum BridgeError implements Cause {
        ENCODE_NOT_SUPPORTED("Encode not supported by this bridge"),
        DECODE_NOT_SUPPORTED("Decode not supported by this bridge");
        private final String message;
        BridgeError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
