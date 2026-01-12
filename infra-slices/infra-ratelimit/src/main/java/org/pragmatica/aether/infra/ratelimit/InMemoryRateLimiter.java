package org.pragmatica.aether.infra.ratelimit;

import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * In-memory implementation of RateLimiter using fixed window strategy.
 * Thread-safe implementation using synchronized blocks per key.
 */
final class InMemoryRateLimiter implements RateLimiter {
    private final ConcurrentHashMap<String, RateLimitConfig> configs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, WindowState> windows = new ConcurrentHashMap<>();
    private final RateLimitConfig defaultConfig;

    InMemoryRateLimiter(RateLimitConfig defaultConfig) {
        this.defaultConfig = defaultConfig;
    }

    @Override
    public Promise<RateLimitResult> acquire(String key) {
        return acquire(key, 1);
    }

    @Override
    public Promise<RateLimitResult> acquire(String key, int permits) {
        var config = configs.getOrDefault(key, defaultConfig);
        var state = getOrCreateWindow(key, config);
        return Promise.success(state.tryAcquire(config, permits));
    }

    @Override
    public Promise<RateLimitResult> check(String key) {
        var config = configs.getOrDefault(key, defaultConfig);
        var state = getOrCreateWindow(key, config);
        return Promise.success(state.checkLimit(config));
    }

    @Override
    public Promise<Unit> configure(String key, RateLimitConfig config) {
        configs.put(key, config);
        return Promise.success(unit());
    }

    private WindowState getOrCreateWindow(String key, RateLimitConfig config) {
        return windows.computeIfAbsent(key, k -> new WindowState(config.window()));
    }

    /**
     * Thread-safe window state using synchronized methods.
     */
    private static final class WindowState {
        private final long windowNanos;
        private long windowStart;
        private int count;

        WindowState(TimeSpan window) {
            this.windowNanos = window.nanos();
            this.windowStart = System.nanoTime();
            this.count = 0;
        }

        synchronized RateLimitResult tryAcquire(RateLimitConfig config, int permits) {
            var now = System.nanoTime();
            resetIfExpired(now);
            if (count + permits > config.maxRequests()) {
                return createDeniedResult(config, now);
            }
            count += permits;
            return RateLimitResult.allowed(config.maxRequests() - count);
        }

        synchronized RateLimitResult checkLimit(RateLimitConfig config) {
            var now = System.nanoTime();
            resetIfExpired(now);
            var remaining = Math.max(0, config.maxRequests() - count);
            if (remaining > 0) {
                return RateLimitResult.allowed(remaining);
            }
            return createDeniedResult(config, now);
        }

        private void resetIfExpired(long now) {
            if (now - windowStart >= windowNanos) {
                windowStart = now;
                count = 0;
            }
        }

        private RateLimitResult createDeniedResult(RateLimitConfig config, long now) {
            var windowEnd = windowStart + windowNanos;
            var retryNanos = Math.max(0, windowEnd - now);
            return RateLimitResult.denied(timeSpan(retryNanos)
                                                  .nanos());
        }
    }
}
