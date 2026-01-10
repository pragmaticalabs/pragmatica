package org.pragmatica.aether.ttm.model;

import org.pragmatica.aether.config.TTMConfig;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Unit.unit;

/**
 * Interface for TTM model inference.
 * <p>
 * Implementations wrap ONNX Runtime or other ML inference engines.
 */
public interface TTMPredictor {
    /**
     * Run inference on input data.
     *
     * @param input float[windowMinutes][features] input matrix from MinuteAggregator.toTTMInput()
     *
     * @return Predicted values for next time step (float[features])
     */
    Promise<float[]> predict(float[][] input);

    /**
     * Get confidence score for last prediction.
     */
    double lastConfidence();

    /**
     * Check if the predictor is ready for inference.
     */
    boolean isReady();

    /**
     * Close and release resources.
     */
    Unit close();

    /**
     * Create predictor from config.
     */
    static Result<TTMPredictor> ttmPredictor(TTMConfig config) {
        return TTMPredictorImpl.create(config);
    }

    /**
     * Create a no-op predictor for testing or when TTM is disabled.
     */
    static TTMPredictor noOp() {
        return NoOpTTMPredictor.INSTANCE;
    }
}

/**
 * No-op predictor for testing or when TTM is disabled.
 */
final class NoOpTTMPredictor implements TTMPredictor {
    static final NoOpTTMPredictor INSTANCE = new NoOpTTMPredictor();

    private NoOpTTMPredictor() {}

    @Override
    public Promise<float[]> predict(float[][] input) {
        return Promise.success(new float[FeatureIndex.FEATURE_COUNT]);
    }

    @Override
    public double lastConfidence() {
        return 0.0;
    }

    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public Unit close() {
        return unit();
    }
}
