package org.pragmatica.aether.ttm.model;

import org.pragmatica.aether.config.TTMConfig;
import org.pragmatica.aether.ttm.error.TTMError;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        if (!config.enabled()) {
            return Result.success(noOp());
        }
        return checkModelExists(config).flatMap(TTMPredictor::loadModel);
    }

    /**
     * Create a no-op predictor for testing or when TTM is disabled.
     */
    static TTMPredictor noOp() {
        return noOpTTMPredictor.INSTANCE;
    }

    /**
     * No-op predictor for testing or when TTM is disabled.
     */
    record noOpTTMPredictor() implements TTMPredictor {
        static final noOpTTMPredictor INSTANCE = new noOpTTMPredictor();

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

    // --- Private implementation methods ---
    private static Result<TTMConfig> checkModelExists(TTMConfig config) {
        var modelPath = Path.of(config.modelPath());
        return Files.exists(modelPath)
               ? Result.success(config)
               : new TTMError.ModelLoadFailed(config.modelPath(), "File not found").result();
    }

    private static Result<TTMPredictor> loadModel(TTMConfig config) {
        return Result.lift(e -> new TTMError.ModelLoadFailed(config.modelPath(), e.getMessage()),
                           () -> createOnnxPredictor(config));
    }

    private static TTMPredictor createOnnxPredictor(TTMConfig config) throws OrtException {
        var log = LoggerFactory.getLogger(TTMPredictor.class);
        var env = OrtEnvironment.getEnvironment();
        var sessionOptions = new OrtSession.SessionOptions();
        try{
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            sessionOptions.setIntraOpNumThreads(2);
            var session = env.createSession(config.modelPath(), sessionOptions);
            log.info("Loaded TTM model from {}", config.modelPath());
            log.debug("Model inputs: {}", session.getInputNames());
            log.debug("Model outputs: {}", session.getOutputNames());
            return new ttmPredictor(env, session, config, new AtomicReference<>(0.0), new AtomicBoolean(true));
        } catch (OrtException e) {
            sessionOptions.close();
            throw e;
        }
    }

    /**
     * ONNX Runtime implementation of TTMPredictor.
     */
    record ttmPredictor(OrtEnvironment env,
                        OrtSession session,
                        TTMConfig config,
                        AtomicReference<Double> lastConfidenceRef,
                        AtomicBoolean ready) implements TTMPredictor {
        private static final Logger log = LoggerFactory.getLogger(ttmPredictor.class);

        @Override
        public Promise<float[]> predict(float[][] input) {
            return Promise.lift(cause -> new TTMError.InferenceFailed(cause.getMessage()),
                                () -> runInference(input))
                          .flatMap(Result::async);
        }

        private Result<float[]> runInference(float[][] input) throws OrtException {
            // Input shape: [windowMinutes][features]
            // ONNX expects: [batch_size, sequence_length, features]
            int seqLen = input.length;
            int features = input[0].length;
            float[] flatInput = new float[seqLen * features];
            for (int i = 0; i < seqLen; i++) {
                System.arraycopy(input[i], 0, flatInput, i * features, features);
            }
            // Create tensor with shape [1, seqLen, features]
            long[] shape = {1, seqLen, features};
            try (var tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatInput), shape);
                 var results = session.run(Map.of("input", tensor))) {
                // Get output tensor
                var outputTensor = (OnnxTensor) results.get(0);
                return flattenOutput(outputTensor).map(output -> extractPredictions(output, features));
            }
        }

        private float[] extractPredictions(float[] output, int features) {
            float[] predictions = new float[FeatureIndex.FEATURE_COUNT];
            int startIdx = Math.max(0, output.length - features);
            System.arraycopy(output, startIdx, predictions, 0, Math.min(features, predictions.length));
            lastConfidenceRef.set(calculateConfidence(output));
            return predictions;
        }

        private Result<float[]> flattenOutput(OnnxTensor tensor) throws OrtException {
            var value = tensor.getValue();
            // Handle different possible output shapes
            if (value instanceof float[] arr) {
                return Result.success(arr);
            } else if (value instanceof float[][] arr2d) {
                // Flatten 2D array
                int totalLen = 0;
                for (float[] row : arr2d) {
                    totalLen += row.length;
                }
                float[] flat = new float[totalLen];
                int offset = 0;
                for (float[] row : arr2d) {
                    System.arraycopy(row, 0, flat, offset, row.length);
                    offset += row.length;
                }
                return Result.success(flat);
            } else if (value instanceof float[][][] arr3d) {
                // Flatten 3D array [batch, seq, features]
                int totalLen = 0;
                for (float[][] batch : arr3d) {
                    for (float[] row : batch) {
                        totalLen += row.length;
                    }
                }
                float[] flat = new float[totalLen];
                int offset = 0;
                for (float[][] batch : arr3d) {
                    for (float[] row : batch) {
                        System.arraycopy(row, 0, flat, offset, row.length);
                        offset += row.length;
                    }
                }
                return Result.success(flat);
            }
            return new TTMError.UnexpectedOutputType(value.getClass()
                                                          .getName()).result();
        }

        private double calculateConfidence(float[] output) {
            // Simple confidence based on output magnitude and variance
            // Lower variance = higher confidence
            if (output.length == 0) {
                return 0.0;
            }
            double sum = 0, sumSq = 0;
            for (float v : output) {
                sum += v;
                sumSq += v * v;
            }
            double mean = sum / output.length;
            double variance = (sumSq / output.length) - (mean * mean);
            // Map variance to confidence (normalized to 0-1)
            // Lower variance = higher confidence
            return Math.max(0.0,
                            Math.min(1.0,
                                     1.0 - Math.tanh(Math.sqrt(Math.max(0, variance)))));
        }

        @Override
        public double lastConfidence() {
            return lastConfidenceRef.get();
        }

        @Override
        public boolean isReady() {
            return ready.get();
        }

        @Override
        public Unit close() {
            ready.set(false);
            Result.lift(e -> new TTMError.InferenceFailed("Error closing ONNX session: " + e.getMessage()),
                        this::closeSession)
                  .onFailure(cause -> log.warn(cause.message()));
            return unit();
        }

        private Unit closeSession() throws OrtException {
            session.close();
            return unit();
        }
    }
}
