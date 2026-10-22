// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package com.example.jsonoptions;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import com.example.durabletopic.DurableOrderSlice;
import com.example.durabletopic.DurableOrderSliceFactory;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.adapter.SliceRouter;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.slice.JsonCodecSelection;
import org.pragmatica.aether.slice.JsonCodecSettings;
import org.pragmatica.aether.slice.JsonImplementation;
import org.pragmatica.aether.slice.ResourceProviderFacade;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceCreationContext;
import org.pragmatica.aether.slice.SliceInvokerFacade;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.serialization.json.JsonOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2378 end to end on a real routed slice: the build-time naming of routes.toml `[json]` reaches the generated codecs
/// and their Jackson fallback, the generated routes take their codec from a deployed slice's live JSON codec settings
/// (a switch applies from the next request), and the generated factory provisions those settings and refreshes them
/// on a `json` config change, while a slice without routes gets none of it.
class GeneratedJsonOptionsRouteTest {
    private static final JsonMapper PRODUCTION = JsonMapper.valueObjectJsonMapper();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(10).seconds();
    private static final JsonOptions STRICT_OPTIONS = JsonOptions.jsonOptions(JsonOptions.NullInclusion.EXCLUDE,
                                                                              true,
                                                                              false,
                                                                              1000,
                                                                              Integer.MAX_VALUE,
                                                                              Integer.MAX_VALUE,
                                                                              Integer.MAX_VALUE)
                                                                 .unwrap();
    private static final JsonCodecSelection STRICT = JsonCodecSelection.jsonCodecSelection("strict",
                                                                                           JsonImplementation.GENERATED,
                                                                                           STRICT_OPTIONS);
    private static final JsonCodecSelection STRICT_JACKSON = JsonCodecSelection.jsonCodecSelection("strict-jackson",
                                                                                                   JsonImplementation.JACKSON,
                                                                                                   STRICT_OPTIONS);

    /// A settings handle a test switches by hand; counts refreshes.
    static final class Switchable implements JsonCodecSettings {
        private volatile JsonCodecSelection current = JsonCodecSelection.DEFAULT;
        private final AtomicInteger refreshes = new AtomicInteger();

        @Override
        public JsonCodecSelection current() {
            return current;
        }

        @Override
        public Unit refresh() {
            refreshes.incrementAndGet();

            return Unit.unit();
        }

        void select(JsonCodecSelection selection) {
            current = selection;
        }
    }

    /// What the node hands the route publisher: the slice, which is also a `Slice` with its settings.
    record Deployed(JsonOptionsSlice delegate, JsonCodecSettings jsonCodecSettings) implements JsonOptionsSlice, Slice {
        @Override
        public List<SliceMethod<?, ?>> methods() {
            return List.of();
        }

        @Override
        public Promise<Profile> echo(ProfileRequest request) {
            return delegate.echo(request);
        }

        @Override
        public Promise<Map<String, Profile>> byName(ProfileRequest request) {
            return delegate.byName(request);
        }
    }

    @Test
    void generatedCodecs_nameFieldsWithTheBuildNaming() {
        var router = new JsonOptionsSliceRoutes().create(JsonOptionsSlice.jsonOptionsSlice(), PRODUCTION);

        assertThat(post(router, "/api/json/echo", "{\"display_name\":\"Ann\",\"nick_name\":\"a\"}"))
            .isEqualTo("200 {\"display_name\":\"Ann\",\"nick_name\":\"a\"}");
        assertThat(post(router, "/api/json/echo", "{\"displayName\":\"Ann\"}")).startsWith("400 ");
    }

    @Test
    void jacksonFallback_namesFieldsWithTheBuildNaming() {
        var router = new JsonOptionsSliceRoutes().create(JsonOptionsSlice.jsonOptionsSlice(), PRODUCTION);

        assertThat(post(router, "/api/json/by-name", "{\"display_name\":\"Ann\",\"nick_name\":null}"))
            .isEqualTo("200 {\"Ann\":{\"display_name\":\"Ann\",\"nick_name\":null}}");
    }

    @Test
    void deployedSlice_switchesCodec_fromTheNextRequest() {
        var settings = new Switchable();
        var router = new JsonOptionsSliceRoutes().create(new Deployed(JsonOptionsSlice.jsonOptionsSlice(), settings), PRODUCTION);

        assertThat(post(router, "/api/json/echo", "{\"display_name\":\"Ann\",\"extra\":1}"))
            .isEqualTo("200 {\"display_name\":\"Ann\",\"nick_name\":null}");

        settings.select(STRICT);

        assertThat(post(router, "/api/json/echo", "{\"display_name\":\"Ann\"}")).isEqualTo("200 {\"display_name\":\"Ann\"}");
        assertThat(post(router, "/api/json/echo", "{\"display_name\":\"Ann\",\"extra\":1}")).startsWith("400 ");
        assertThat(post(router, "/api/json/by-name", "{\"display_name\":\"Ann\"}")).isEqualTo("200 {\"Ann\":{\"display_name\":\"Ann\"}}");

        settings.select(JsonCodecSelection.DEFAULT);

        assertThat(post(router, "/api/json/echo", "{\"display_name\":\"Ann\",\"extra\":1}"))
            .isEqualTo("200 {\"display_name\":\"Ann\",\"nick_name\":null}");
    }

    @Test
    void jacksonSelection_keepsTheBuildNamingAndTheOptions() {
        var settings = new Switchable();
        var router = new JsonOptionsSliceRoutes().create(new Deployed(JsonOptionsSlice.jsonOptionsSlice(), settings), PRODUCTION);

        // A number where a String is declared: the generated codecs refuse it, Jackson coerces it. It tells which
        // implementation served the request.
        settings.select(STRICT);
        assertThat(post(router, "/api/json/echo", "{\"display_name\":5}")).startsWith("400 ");

        settings.select(STRICT_JACKSON);

        assertThat(post(router, "/api/json/echo", "{\"display_name\":\"Ann\"}")).isEqualTo("200 {\"display_name\":\"Ann\"}");
        assertThat(post(router, "/api/json/echo", "{\"display_name\":\"Ann\",\"extra\":1}")).startsWith("400 ");
        assertThat(post(router, "/api/json/echo", "{\"display_name\":5}")).isEqualTo("200 {\"display_name\":\"5\"}");
    }

    @Test
    void generatedFactory_provisionsTheSettings_andRefreshesThemOnAJsonChangeOnly() throws Exception {
        var settings = new Switchable();
        var slice = JsonOptionsSliceFactory.jsonOptionsSliceSlice(SliceCreationContext.sliceCreationContext(unused(SliceInvokerFacade.class),
                                                                                                            providing(settings)))
                                           .await(AWAIT)
                                           .unwrap();

        assertThat(slice.jsonCodecSettings()).isSameAs(settings);
        assertThat(slice).isInstanceOf(JsonOptionsSlice.class);

        JsonOptionsSliceFactory.notifyConfigUpdate(slice, "other", null);
        assertThat(settings.refreshes.get()).isZero();
        JsonOptionsSliceFactory.notifyConfigUpdate(slice, "json", null);
        assertThat(settings.refreshes.get()).isEqualTo(1);
    }

    @Test
    void manifest_namesTheJsonSection_andDeclaresNoResourceForIt() throws IOException {
        var manifest = manifest("JsonOptionsSlice");

        assertThat(manifest.getProperty("json.settings.section")).isEqualTo("json");
        assertThat(manifest.stringPropertyNames()).noneMatch(key -> key.startsWith("resource.")
                                                                    && "json".equals(manifest.getProperty(key)));
    }

    @Test
    void sliceWithoutRoutes_getsNoJsonSettings() throws IOException {
        var slice = DurableOrderSliceFactory.durableOrderSliceSlice(SliceCreationContext.sliceCreationContext(unused(SliceInvokerFacade.class),
                                                                                                              unused(ResourceProviderFacade.class)))
                                            .await(AWAIT)
                                            .unwrap();

        assertThat(slice.jsonCodecSettings()
                        .current()).isSameAs(JsonCodecSelection.DEFAULT);
        assertThat(Arrays.stream(DurableOrderSliceFactory.class.getMethods())
                         .map(java.lang.reflect.Method::getName)).doesNotContain("notifyConfigUpdate");
        assertThat(manifest(DurableOrderSlice.class.getSimpleName()).getProperty("json.settings.section")).isNull();
    }

    private static Properties manifest(String sliceName) throws IOException {
        var properties = new Properties();

        try (var in = GeneratedJsonOptionsRouteTest.class.getClassLoader()
                                                         .getResourceAsStream("META-INF/slice/" + sliceName + ".manifest")) {
            assertThat(in).as("manifest of " + sliceName).isNotNull();
            properties.load(in);
        }

        return properties;
    }

    // Answers only the implicit JSON settings provision; anything else is a test error.
    private static ResourceProviderFacade providing(JsonCodecSettings settings) {
        return (ResourceProviderFacade) Proxy.newProxyInstance(ResourceProviderFacade.class.getClassLoader(),
                                                               new Class[]{ResourceProviderFacade.class},
                                                               (_, method, args) -> {
                                                                   if (method.getName().equals("provide")
                                                                       && args[0] == JsonCodecSettings.class
                                                                       && "json".equals(args[1])) {
                                                                       return Promise.success(settings);
                                                                   }

                                                                   throw new UnsupportedOperationException(method.getName());
                                                               });
    }

    private static <T> T unused(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
                                                new Class[]{type},
                                                (_, method, _) -> {
                                                    throw new UnsupportedOperationException(type.getSimpleName() + "."
                                                                                            + method.getName());
                                                }));
    }

    private static String post(SliceRouter router, String path, String body) {
        return router.handle(HttpRequestContext.httpRequestContext(path, "POST", Map.of(), Map.of(),
                                                                   body.getBytes(StandardCharsets.UTF_8), "req_json"))
                     .await(AWAIT)
                     .fold(cause -> fail("router failed: " + cause.message()),
                           response -> response.statusCode() + " " + new String(response.body(), StandardCharsets.UTF_8));
    }
}
