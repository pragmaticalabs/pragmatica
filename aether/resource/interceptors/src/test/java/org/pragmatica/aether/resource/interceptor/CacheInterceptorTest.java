package org.pragmatica.aether.resource.interceptor;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CacheInterceptorTest {

    private static final Fn1<Object, ?> IDENTITY_KEY_EXTRACTOR = Fn1.id();

    private record TestError(String message) implements Cause {}

    private static Option<Object> getCached(CacheBackend cache, Object key) {
        return cache.get(key).await().fold(_ -> Option.none(), v -> v);
    }

    @Nested
    class CacheAsideStrategy {
        @Test
        void cacheAside_cacheMiss_callsMethodAndCachesResult() {
            var cache = InMemoryCache.inMemoryCache(60, 100);
            var interceptor = new CacheMethodInterceptor(cache, CacheStrategy.CACHE_ASIDE, IDENTITY_KEY_EXTRACTOR);
            var callCount = new AtomicInteger(0);
            Fn1<Promise<String>, String> method = request -> {
                callCount.incrementAndGet();
                return Promise.success("result-" + request);
            };

            var intercepted = interceptor.intercept(method);

            var value1 = intercepted.apply("key1").await().fold(_ -> null, v -> v);
            assertThat(value1).isEqualTo("result-key1");
            assertThat(callCount.get()).isEqualTo(1);

            var value2 = intercepted.apply("key1").await().fold(_ -> null, v -> v);
            assertThat(value2).isEqualTo("result-key1");
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        void cacheAside_cacheHit_returnsCachedWithoutMethodCall() {
            var cache = InMemoryCache.inMemoryCache(60, 100);
            cache.put("key1", "cached-value");
            var interceptor = new CacheMethodInterceptor(cache, CacheStrategy.CACHE_ASIDE, IDENTITY_KEY_EXTRACTOR);
            var callCount = new AtomicInteger(0);
            Fn1<Promise<String>, String> method = request -> {
                callCount.incrementAndGet();
                return Promise.success("result-" + request);
            };

            var intercepted = interceptor.intercept(method);
            var value = intercepted.apply("key1").await().fold(_ -> null, v -> v);

            assertThat(value).isEqualTo("cached-value");
            assertThat(callCount.get()).isZero();
        }
    }

    @Nested
    class ReadThroughStrategy {
        @Test
        void readThrough_behavesSameAsCacheAside() {
            var cache = InMemoryCache.inMemoryCache(60, 100);
            var interceptor = new CacheMethodInterceptor(cache, CacheStrategy.READ_THROUGH, IDENTITY_KEY_EXTRACTOR);
            var callCount = new AtomicInteger(0);
            Fn1<Promise<String>, String> method = request -> {
                callCount.incrementAndGet();
                return Promise.success("result-" + request);
            };

            var intercepted = interceptor.intercept(method);

            var value1 = intercepted.apply("key1").await().fold(_ -> null, v -> v);
            assertThat(value1).isEqualTo("result-key1");
            assertThat(callCount.get()).isEqualTo(1);

            var value2 = intercepted.apply("key1").await().fold(_ -> null, v -> v);
            assertThat(value2).isEqualTo("result-key1");
            assertThat(callCount.get()).isEqualTo(1);
        }
    }

    @Nested
    class WriteThroughStrategy {
        @Test
        void writeThrough_callsMethodAndCachesInChain() {
            var cache = InMemoryCache.inMemoryCache(60, 100);
            var interceptor = new CacheMethodInterceptor(cache, CacheStrategy.WRITE_THROUGH, IDENTITY_KEY_EXTRACTOR);
            var callCount = new AtomicInteger(0);
            Fn1<Promise<String>, String> method = request -> {
                callCount.incrementAndGet();
                return Promise.success("result-" + request);
            };

            var intercepted = interceptor.intercept(method);
            var value = intercepted.apply("key1").await().fold(_ -> null, v -> v);

            assertThat(value).isEqualTo("result-key1");
            assertThat(callCount.get()).isEqualTo(1);
            assertThat(getCached(cache, "key1").isPresent()).isTrue();
            assertThat(getCached(cache, "key1").or("missing")).isEqualTo("result-key1");
        }
    }

    @Nested
    class WriteBackStrategy {
        @Test
        void writeBack_callsMethodAndCachesSideEffect() {
            var cache = InMemoryCache.inMemoryCache(60, 100);
            var interceptor = new CacheMethodInterceptor(cache, CacheStrategy.WRITE_BACK, IDENTITY_KEY_EXTRACTOR);
            var callCount = new AtomicInteger(0);
            Fn1<Promise<String>, String> method = request -> {
                callCount.incrementAndGet();
                return Promise.success("result-" + request);
            };

            var intercepted = interceptor.intercept(method);
            var value = intercepted.apply("key1").await().fold(_ -> null, v -> v);

            assertThat(value).isEqualTo("result-key1");
            assertThat(callCount.get()).isEqualTo(1);
            assertThat(getCached(cache, "key1").isPresent()).isTrue();
            assertThat(getCached(cache, "key1").or("missing")).isEqualTo("result-key1");
        }
    }

    @Nested
    class WriteAroundStrategy {
        @Test
        void writeAround_callsMethodAndInvalidatesCache() {
            var cache = InMemoryCache.inMemoryCache(60, 100);
            cache.put("key1", "old-cached-value");
            var interceptor = new CacheMethodInterceptor(cache, CacheStrategy.WRITE_AROUND, IDENTITY_KEY_EXTRACTOR);
            var callCount = new AtomicInteger(0);
            Fn1<Promise<String>, String> method = request -> {
                callCount.incrementAndGet();
                return Promise.success("result-" + request);
            };

            var intercepted = interceptor.intercept(method);
            var value = intercepted.apply("key1").await().fold(_ -> null, v -> v);

            assertThat(value).isEqualTo("result-key1");
            assertThat(callCount.get()).isEqualTo(1);
            assertThat(getCached(cache, "key1").isEmpty()).isTrue();
        }
    }

    @Nested
    class ErrorHandling {
        @Test
        void allStrategies_errorResult_neverCached() {
            for (var strategy : CacheStrategy.values()) {
                var cache = InMemoryCache.inMemoryCache(60, 100);
                var interceptor = new CacheMethodInterceptor(cache, strategy, IDENTITY_KEY_EXTRACTOR);
                Fn1<Promise<String>, String> method = _ -> new TestError("test failure").promise();

                var intercepted = interceptor.intercept(method);
                var result = intercepted.apply("key1").await();
                var value = result.fold(_ -> "failed", v -> v);

                assertThat(value)
                    .as("Strategy %s should propagate failure", strategy)
                    .isEqualTo("failed");
                assertThat(getCached(cache, "key1").isEmpty())
                    .as("Strategy %s should not cache errors", strategy)
                    .isTrue();
            }
        }
    }

    @Nested
    class SharedCache {
        @Test
        void sharedCache_sameName_sharesEntries() {
            var factory = new CacheInterceptorFactory();
            var config = CacheConfig.cacheConfig("shared-cache", CacheStrategy.CACHE_ASIDE)
                                    .fold(_ -> null, v -> v);

            var interceptor1 = factory.provision(config).await().fold(_ -> null, v -> v);
            var interceptor2 = factory.provision(config).await().fold(_ -> null, v -> v);

            assertThat(interceptor1).isNotNull();
            assertThat(interceptor2).isNotNull();
            assertThat(interceptor1.cache()).isSameAs(interceptor2.cache());
        }
    }
}
