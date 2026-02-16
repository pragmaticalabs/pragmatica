package org.pragmatica.aether.ttm.model;

import org.pragmatica.aether.config.TtmConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ServiceLoader;

/// SPI for TTM predictor implementations.
///
/// Implementations are discovered via {@link ServiceLoader}. When no implementation
/// is on the classpath, TTM prediction is unavailable.
public interface TTMPredictorFactory {
    Option<TTMPredictorFactory> INSTANCE = Option.from(ServiceLoader.load(TTMPredictorFactory.class)
                                                                    .findFirst());

    Result<TTMPredictor> ttmPredictor(TtmConfig config);
}
