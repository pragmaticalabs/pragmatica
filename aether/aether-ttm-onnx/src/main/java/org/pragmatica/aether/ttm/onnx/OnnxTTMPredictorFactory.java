// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.ttm.onnx;

import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.ttm.model.TTMPredictor;
import org.pragmatica.aether.ttm.model.TTMPredictorFactory;
import org.pragmatica.lang.Result;


public final class OnnxTTMPredictorFactory implements TTMPredictorFactory {
    @Override public Result<TTMPredictor> ttmPredictor(TtmConfig config) {
        return OnnxTTMPredictor.onnxTTMPredictor(config);
    }
}
