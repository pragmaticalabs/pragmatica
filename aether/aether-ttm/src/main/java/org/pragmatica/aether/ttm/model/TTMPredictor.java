package org.pragmatica.aether.ttm.model;

import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.ttm.error.TTMError;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;


/// Interface for TTM model inference.
///
/// Implementations wrap ONNX Runtime or other ML inference engines.
public interface TTMPredictor {
    Promise<float[]> predict(float[][] input);
    double lastConfidence();
    boolean isReady();
    Unit close();

    static Result<TTMPredictor> ttmPredictor(TtmConfig config) {
        if (!config.enabled()) {return Result.success(noOp());}
        return TTMPredictorFactory.INSTANCE.fold(() -> TTMError.NoProvider.noProvider().<TTMPredictor>result(),
                                                 factory -> factory.ttmPredictor(config));
    }

    static TTMPredictor noOp() {
        return NoOpTTMPredictor.INSTANCE;
    }

    record NoOpTTMPredictor() implements TTMPredictor {
        static final NoOpTTMPredictor INSTANCE = new NoOpTTMPredictor();

        @Override public Promise<float[]> predict(float[][] input) {
            return Promise.success(new float[FeatureIndex.FEATURE_COUNT]);
        }

        @Override public double lastConfidence() {
            return 0.0;
        }

        @Override public boolean isReady() {
            return false;
        }

        @Override public Unit close() {
            return unit();
        }
    }
}
