package org.pragmatica.aether.ttm;

import org.pragmatica.aether.config.TTMConfig;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.metrics.MinuteAggregator;
import org.pragmatica.aether.ttm.error.TTMError;
import org.pragmatica.aether.ttm.model.TTMForecast;
import org.pragmatica.aether.ttm.model.TTMPredictor;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.messaging.MessageReceiver;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages TTM lifecycle, leader awareness, and periodic evaluation.
 * <p>
 * Only runs inference on the leader node. Followers receive state updates
 * via Rabia replication (through the DecisionTreeController threshold adjustments).
 */
public interface TTMManager {
    /**
     * React to leader changes.
     */
    @MessageReceiver
    void onLeaderChange(LeaderChange leaderChange);

    /**
     * Get current forecast if available.
     */
    Option<TTMForecast> currentForecast();

    /**
     * Get current TTM state.
     */
    TTMState state();

    /**
     * Register callback for forecast updates.
     */
    void onForecast(Consumer<TTMForecast> callback);

    /**
     * Get the TTM configuration.
     */
    TTMConfig config();

    /**
     * Check if TTM is actually enabled and functional.
     * Returns false for NoOpTTMManager or if model failed to load.
     */
    boolean isEnabled();

    /**
     * Stop the TTM manager.
     */
    void stop();

    /**
     * Create TTM manager.
     *
     * @param config                   TTM configuration
     * @param aggregator               MinuteAggregator providing input data
     * @param controllerConfigSupplier Supplier for current controller config
     *
     * @return Result containing TTMManager or error
     */
    static Result<TTMManager> ttmManager(TTMConfig config,
                                         MinuteAggregator aggregator,
                                         Supplier<ControllerConfig> controllerConfigSupplier) {
        if (!config.enabled()) {
            return TTMError.Disabled.INSTANCE.result();
        }
        return TTMPredictor.ttmPredictor(config)
                           .map(predictor -> createManager(config, predictor, aggregator, controllerConfigSupplier));
    }

    private static TTMManager createManager(TTMConfig config,
                                            TTMPredictor predictor,
                                            MinuteAggregator aggregator,
                                            Supplier<ControllerConfig> controllerConfigSupplier) {
        var analyzer = ForecastAnalyzer.forecastAnalyzer(config);
        return new TTMManagerImpl(config, predictor, analyzer, aggregator, controllerConfigSupplier);
    }

    /**
     * Create a no-op TTM manager (for when TTM is disabled).
     */
    static TTMManager noOp(TTMConfig config) {
        return new NoOpTTMManager(config);
    }
}

/**
 * No-op implementation for when TTM is disabled.
 */
final class NoOpTTMManager implements TTMManager {
    private final TTMConfig config;

    NoOpTTMManager(TTMConfig config) {
        this.config = config;
    }

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
    public TTMConfig config() {
        return config;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public void stop() {}
}
