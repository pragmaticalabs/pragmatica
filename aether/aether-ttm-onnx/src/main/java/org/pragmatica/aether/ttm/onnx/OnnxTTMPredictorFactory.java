package org.pragmatica.aether.ttm.onnx;

import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.ttm.model.TTMPredictor;
import org.pragmatica.aether.ttm.model.TTMPredictorFactory;
import org.pragmatica.lang.Result;

/// ONNX Runtime implementation of {@link TTMPredictorFactory}.
///
/// Discovered via ServiceLoader when `aether-ttm-onnx` is on the classpath.
public final class OnnxTTMPredictorFactory implements TTMPredictorFactory {
    @Override
    public Result<TTMPredictor> ttmPredictor(TtmConfig config) {
        return OnnxTTMPredictor.onnxTTMPredictor(config);
    }
}
