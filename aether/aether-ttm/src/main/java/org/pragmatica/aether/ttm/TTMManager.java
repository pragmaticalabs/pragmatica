package org.pragmatica.aether.ttm;

import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.metrics.MinuteAggregator;
import org.pragmatica.aether.ttm.error.TTMError;
import org.pragmatica.aether.ttm.model.ScalingRecommendation;
import org.pragmatica.aether.ttm.model.TTMForecast;
import org.pragmatica.aether.ttm.model.TTMPredictor;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageReceiver;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Manages TTM lifecycle, leader awareness, and periodic evaluation.
///
/// Only runs inference on the leader node. Followers receive state updates
/// via Rabia replication (through the DecisionTreeController threshold adjustments).
@SuppressWarnings("JBCT-RET-01") // MessageReceiver callbacks and registration methods — void is intentional
public interface TTMManager {
    /// React to leader changes.
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    /// Get current forecast if available.
    Option<TTMForecast> currentForecast();

    /// Get current TTM state.
    TTMState state();

    /// Register callback for forecast updates.
    void onForecast(Consumer<TTMForecast> callback);

    /// Get the TTM configuration.
    TtmConfig config();

    /// Check if TTM is actually enabled and functional.
    /// Returns false for NoOpTTMManager or if model failed to load.
    boolean isEnabled();

    /// Stop the TTM manager.
    Unit stop();

    /// Create TTM manager.
    ///
    /// @param config                   TTM configuration
    /// @param aggregator               MinuteAggregator providing input data
    /// @param controllerConfigSupplier Supplier for current controller config
    ///
    /// @return Result containing TTMManager or error
    static Result<TTMManager> ttmManager(TtmConfig config,
                                         MinuteAggregator aggregator,
                                         Supplier<ControllerConfig> controllerConfigSupplier) {
        if (!config.enabled()) {
            return Result.success(noOp(config));
        }
        return TTMPredictor.ttmPredictor(config)
                           .map(predictor -> createManager(config, predictor, aggregator, controllerConfigSupplier));
    }

    private static TTMManager createManager(TtmConfig config,
                                            TTMPredictor predictor,
                                            MinuteAggregator aggregator,
                                            Supplier<ControllerConfig> controllerConfigSupplier) {
        var analyzer = ForecastAnalyzer.forecastAnalyzer(config);
        var scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                                                                       var thread = new Thread(r, "ttm-manager");
                                                                       thread.setDaemon(true);
                                                                       return thread;
                                                                   });
        return new ActiveTTMManager(config,
                                    predictor,
                                    analyzer,
                                    aggregator,
                                    controllerConfigSupplier,
                                    scheduler,
                                    new AtomicReference<>(),
                                    new AtomicReference<>(),
                                    new AtomicReference<>(TTMState.STOPPED),
                                    new CopyOnWriteArrayList<>());
    }

    /// Create a no-op TTM manager (for when TTM is disabled).
    static TTMManager noOp(TtmConfig config) {
        return new NoOpTTMManager(config);
    }

    /// No-op implementation for when TTM is disabled.
    record NoOpTTMManager(TtmConfig config) implements TTMManager {
        @Override
        public void onLeaderChange(LeaderChange leaderChange) {}

        @Override
        public Option<TTMForecast> currentForecast() {
            return Option.empty();
        }

        @Override
        public TTMState state() {
            return TTMState.STOPPED;
        }

        @Override
        public void onForecast(Consumer<TTMForecast> callback) {}

        @Override
        public boolean isEnabled() {
            return false;
        }

        @Override
        public Unit stop() {
            return Unit.unit();
        }
    }

    /// Implementation of TTMManager.
    record ActiveTTMManager(TtmConfig config,
                            TTMPredictor predictor,
                            ForecastAnalyzer analyzer,
                            MinuteAggregator aggregator,
                            Supplier<ControllerConfig> controllerConfigSupplier,
                            ScheduledExecutorService scheduler,
                            AtomicReference<ScheduledFuture<?>> evaluationTask,
                            AtomicReference<TTMForecast> currentForecastRef,
                            AtomicReference<TTMState> stateRef,
                            CopyOnWriteArrayList<Consumer<TTMForecast>> callbacks) implements TTMManager {
        private static final Logger log = LoggerFactory.getLogger(ActiveTTMManager.class);
        private static final Counter PREDICTION_COUNTER = Metrics.counter("ttm.predictions.count");
        private static final Counter SCALE_UP_COUNTER = Metrics.counter("ttm.recommendations", "type", "scale_up");
        private static final Counter SCALE_DOWN_COUNTER = Metrics.counter("ttm.recommendations", "type", "scale_down");
        private static final Counter ADJUST_COUNTER = Metrics.counter("ttm.recommendations", "type", "adjust_thresholds");
        private static final Counter HOLD_COUNTER = Metrics.counter("ttm.recommendations", "type", "hold");

        @Override
        public void onLeaderChange(LeaderChange leaderChange) {
            if (leaderChange.localNodeIsLeader()) {
                log.info("Node became leader, starting TTM evaluation");
                startEvaluation();
            } else {
                log.info("Node is no longer leader, stopping TTM evaluation");
                stopEvaluation();
            }
        }

        @Override
        public Option<TTMForecast> currentForecast() {
            return Option.option(currentForecastRef.get());
        }

        @Override
        public TTMState state() {
            return stateRef.get();
        }

        @Override
        public void onForecast(Consumer<TTMForecast> callback) {
            callbacks.add(callback);
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public Unit stop() {
            stopEvaluation();
            predictor.close();
            scheduler.shutdown();
            awaitSchedulerTermination();
            return Unit.unit();
        }

        private void awaitSchedulerTermination() {
            Result.lift(e -> new TTMError.InferenceFailed("Scheduler termination interrupted"),
                        () -> {
                            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                                scheduler.shutdownNow();
                            }
                            return Unit.unit();
                        })
                  .onFailure(cause -> {
                      scheduler.shutdownNow();
                      Thread.currentThread()
                            .interrupt();
                  });
        }

        private void startEvaluation() {
            stopEvaluation();
            stateRef.set(TTMState.RUNNING);
            var task = scheduler.scheduleAtFixedRate(this::runEvaluation,
                                                     config.evaluationIntervalMs(),
                                                     config.evaluationIntervalMs(),
                                                     TimeUnit.MILLISECONDS);
            evaluationTask.set(task);
            log.info("TTM evaluation started with interval {}ms", config.evaluationIntervalMs());
        }

        private void stopEvaluation() {
            stateRef.set(TTMState.STOPPED);
            var existing = evaluationTask.getAndSet(null);
            if (existing != null) {
                existing.cancel(false);
                log.info("TTM evaluation stopped");
            }
        }

        private void runEvaluation() {
            evaluateAsync().onFailure(this::handleEvaluationError);
        }

        private Promise<Unit> evaluateAsync() {
            int available = aggregator.aggregateCount();
            int required = config.inputWindowMinutes();
            if (available < required / 2) {
                log.debug("Insufficient data for TTM: {} minutes available, {} required", available, required);
                return Promise.unitPromise();
            }
            float[][] input = aggregator.toTTMInput(required);
            return predictor.predict(input)
                            .withSuccess(this::processPrediction)
                            .mapToUnit();
        }

        private void handleEvaluationError(Cause cause) {
            log.error("TTM evaluation error: {}", cause.message());
            stateRef.set(TTMState.ERROR);
        }

        private void processPrediction(float[] predictions) {
            var recentHistory = aggregator.recent(config.inputWindowMinutes());
            var controllerConfig = controllerConfigSupplier.get();
            var forecast = analyzer.analyze(predictions, predictor.lastConfidence(), recentHistory, controllerConfig);
            currentForecastRef.set(forecast);
            PREDICTION_COUNTER.increment();
            trackRecommendationType(forecast.recommendation());
            log.debug("TTM forecast: recommendation={}, confidence={}",
                      forecast.recommendation()
                              .getClass()
                              .getSimpleName(),
                      forecast.confidence());
            notifyCallbacks(forecast);
            stateRef.set(TTMState.RUNNING);
        }

        private void trackRecommendationType(ScalingRecommendation rec) {
            switch (rec) {
                case ScalingRecommendation.PreemptiveScaleUp _ -> SCALE_UP_COUNTER.increment();
                case ScalingRecommendation.PreemptiveScaleDown _ -> SCALE_DOWN_COUNTER.increment();
                case ScalingRecommendation.AdjustThresholds _ -> ADJUST_COUNTER.increment();
                case ScalingRecommendation.NoAction _ -> HOLD_COUNTER.increment();
            }
        }

        private void notifyCallbacks(TTMForecast forecast) {
            callbacks.forEach(callback -> safeInvokeCallback(callback, forecast));
        }

        private void safeInvokeCallback(Consumer<TTMForecast> callback, TTMForecast forecast) {
            Result.lift(e -> new TTMError.InferenceFailed("Callback error: " + e.getMessage()),
                        () -> {
                            callback.accept(forecast);
                            return Unit.unit();
                        })
                  .onFailure(cause -> log.warn("Forecast callback error: {}",
                                               cause.message()));
        }
    }
}
