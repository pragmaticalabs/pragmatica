package org.pragmatica.aether.ttm.onnx;

import org.pragmatica.aether.config.TTMConfig;
import org.pragmatica.aether.ttm.error.TTMError;
import org.pragmatica.aether.ttm.model.FeatureIndex;
import org.pragmatica.aether.ttm.model.TTMPredictor;
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

/// ONNX Runtime implementation of {@link TTMPredictor}.
record OnnxTTMPredictor(OrtEnvironment env,
                        OrtSession session,
                        TTMConfig config,
                        AtomicReference<Double> lastConfidenceRef,
                        AtomicBoolean ready) implements TTMPredictor {
    private static final Logger log = LoggerFactory.getLogger(OnnxTTMPredictor.class);

    static Result<TTMPredictor> onnxTTMPredictor(TTMConfig config) {
        return checkModelExists(config).flatMap(OnnxTTMPredictor::loadModel);
    }

    @Override
    public Promise<float[]> predict(float[][] input) {
        return Promise.lift(cause -> new TTMError.InferenceFailed(cause.getMessage()),
                            () -> runInference(input));
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

    float[] runInference(float[][] input) throws OrtException {
        int seqLen = input.length;
        int features = input[0].length;
        float[] flatInput = new float[seqLen * features];
        for (int i = 0; i < seqLen; i++) {
            System.arraycopy(input[i], 0, flatInput, i * features, features);
        }
        long[] shape = {1, seqLen, features};
        try (var tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(flatInput), shape);
             var results = session.run(Map.of("input", tensor))) {
            var outputTensor = (OnnxTensor) results.get(0);
            return extractPredictions(flattenOutput(outputTensor), features);
        }
    }

    private float[] extractPredictions(float[] output, int features) {
        float[] predictions = new float[FeatureIndex.FEATURE_COUNT];
        int startIdx = Math.max(0, output.length - features);
        System.arraycopy(output, startIdx, predictions, 0, Math.min(features, predictions.length));
        lastConfidenceRef.set(calculateConfidence(output));
        return predictions;
    }

    private float[] flattenOutput(OnnxTensor tensor) throws OrtException {
        var value = tensor.getValue();
        if (value instanceof float[] arr) {
            return arr;
        } else if (value instanceof float[][] arr2d) {
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
            return flat;
        } else if (value instanceof float[][][] arr3d) {
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
            return flat;
        }
        throw new OrtException("Unexpected output tensor type: " + value.getClass()
                                                                       .getName());
    }

    private double calculateConfidence(float[] output) {
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
        return Math.max(0.0,
                        Math.min(1.0,
                                 1.0 - Math.tanh(Math.sqrt(Math.max(0, variance)))));
    }

    private Unit closeSession() throws OrtException {
        session.close();
        return unit();
    }

    private static Result<TTMConfig> checkModelExists(TTMConfig config) {
        var modelPath = Path.of(config.modelPath());
        return Files.exists(modelPath)
               ? Result.success(config)
               : new TTMError.ModelLoadFailed(config.modelPath(), "File not found").result();
    }

    private static Result<TTMPredictor> loadModel(TTMConfig config) {
        return Result.lift(e -> new TTMError.ModelLoadFailed(config.modelPath(), e.getMessage()),
                           () -> createPredictor(config));
    }

    private static TTMPredictor createPredictor(TTMConfig config) throws OrtException {
        var env = OrtEnvironment.getEnvironment();
        var sessionOptions = new OrtSession.SessionOptions();
        try{
            sessionOptions.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            sessionOptions.setIntraOpNumThreads(2);
            var session = env.createSession(config.modelPath(), sessionOptions);
            logModelMetadata(session);
            validateInputSchema(session);
            var predictor = new OnnxTTMPredictor(env,
                                                 session,
                                                 config,
                                                 new AtomicReference<>(0.0),
                                                 new AtomicBoolean(true));
            runWarmupInference(predictor, config);
            return predictor;
        } catch (OrtException e) {
            sessionOptions.close();
            throw e;
        }
    }

    private static void logModelMetadata(OrtSession session) throws OrtException {
        log.info("TTM model metadata:");
        log.info("  Input names: {}", session.getInputNames());
        log.info("  Output names: {}", session.getOutputNames());
        for (var entry : session.getInputInfo()
                                .entrySet()) {
            log.info("  Input '{}': {}",
                     entry.getKey(),
                     entry.getValue()
                          .getInfo());
        }
        for (var entry : session.getOutputInfo()
                                .entrySet()) {
            log.info("  Output '{}': {}",
                     entry.getKey(),
                     entry.getValue()
                          .getInfo());
        }
    }

    private static void validateInputSchema(OrtSession session) throws OrtException {
        var inputNames = session.getInputNames();
        if (!inputNames.contains("input")) {
            throw new OrtException("Expected input tensor named 'input', found: " + inputNames);
        }
        var outputNames = session.getOutputNames();
        if (!outputNames.contains("output")) {
            throw new OrtException("Expected output tensor named 'output', found: " + outputNames);
        }
    }

    private static void runWarmupInference(OnnxTTMPredictor predictor, TTMConfig config) {
        log.info("Running warm-up inference...");
        var warmupInput = new float[config.inputWindowMinutes()][FeatureIndex.FEATURE_COUNT];
        Result.lift(e -> new TTMError.InferenceFailed("Warm-up inference failed: " + e.getMessage()),
                    () -> predictor.runInference(warmupInput))
              .onSuccess(output -> log.info("  Warm-up complete, output length: {}", output.length))
              .onFailure(cause -> log.warn("  Warm-up inference failed: {}",
                                           cause.message()));
    }
}
