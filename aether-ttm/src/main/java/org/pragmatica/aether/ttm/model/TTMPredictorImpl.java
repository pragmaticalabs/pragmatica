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

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ONNX Runtime implementation of TTMPredictor.
 */
final class TTMPredictorImpl implements TTMPredictor {
    private static final Logger log = LoggerFactory.getLogger(TTMPredictorImpl.class);

    private final OrtEnvironment env;
    private final OrtSession session;
    private final TTMConfig config;
    private volatile double lastConfidence = 0.0;
    private volatile boolean ready = true;

    private TTMPredictorImpl(OrtEnvironment env, OrtSession session, TTMConfig config) {
        this.env = env;
        this.session = session;
        this.config = config;
    }

    static Result<TTMPredictor> create(TTMConfig config) {
        if (!config.enabled()) {
            return Result.success(TTMPredictor.noOp());
        }
        return checkModelExists(config).flatMap(TTMPredictorImpl::loadModel);
    }

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

    private static TTMPredictorImpl createOnnxPredictor(TTMConfig config) throws OrtException {
        var env = OrtEnvironment.getEnvironment();
        var sessionOptions = new OrtSession.SessionOptions();
        try{
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            sessionOptions.setIntraOpNumThreads(2);
            var session = env.createSession(config.modelPath(), sessionOptions);
            log.info("Loaded TTM model from {}", config.modelPath());
            log.debug("Model inputs: {}", session.getInputNames());
            log.debug("Model outputs: {}", session.getOutputNames());
            return new TTMPredictorImpl(env, session, config);
        } catch (OrtException e) {
            sessionOptions.close();
            throw e;
        }
    }

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
        lastConfidence = calculateConfidence(output);
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
        return lastConfidence;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public Unit close() {
        ready = false;
        Result.lift(e -> new TTMError.InferenceFailed("Error closing ONNX session: " + e.getMessage()),
                    this::closeSession)
              .onFailure(cause -> log.warn(cause.message()));
        return Unit.unit();
    }

    private Unit closeSession() throws OrtException {
        session.close();
        return Unit.unit();
    }
}
