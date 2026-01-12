package org.pragmatica.aether.infra.aspect;

import org.pragmatica.aether.slice.Aspect;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;

/**
 * Factory for creating logging aspects.
 * Wraps slice method invocations with logging.
 */
public interface LoggingAspectFactory extends Slice {
    /**
     * Create a logging aspect with the given configuration.
     */
    <T> Aspect<T> create(LogConfig config);

    /**
     * Create a logging aspect with default configuration.
     */
    default <T> Aspect<T> create(String name) {
        return create(LogConfig.logConfig(name)
                               .unwrap());
    }

    /**
     * Enable or disable logging globally.
     */
    Unit setEnabled(boolean enabled);

    /**
     * Check if logging is enabled.
     */
    boolean isEnabled();

    /**
     * Factory method.
     */
    static LoggingAspectFactory loggingAspectFactory() {
        return new DefaultLoggingAspectFactory();
    }

    @Override
    default Promise<Unit> start() {
        return Promise.success(Unit.unit());
    }

    @Override
    default Promise<Unit> stop() {
        return Promise.success(Unit.unit());
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
