// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.http.adapter;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteMountMode;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.Json;
import org.pragmatica.serialization.json.JsonTypeCodec;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// One wire form per slice: a route served by the generated codecs answers with the bytes the production
/// slice-route mapper (`JsonMapper.valueObjectJsonMapper()`) writes, and a body type that falls back to that
/// mapper (`List<Signup>`: collections are decoded by Jackson) accepts the same value-object form the
/// generated codec accepts.
class SliceRouterWireFormParityTest {
    private static final JsonMapper PRODUCTION = JsonMapper.valueObjectJsonMapper();

    @Json
    record Email(String value) {
        public static Result<Email> email(String value) {
            return value.contains("@")
                   ? Result.success(new Email(value))
                   : Causes.cause("not an email: " + value).result();
        }
    }

    @Json
    record Signup(Email email) {}

    @Json
    record Out(String id, Option<String> note) {}

    private static GeneratedJsonCodec codec() {
        var codecs = new HashMap<Class<?>, JsonTypeCodec<?>>();

        codecs.putAll(SliceRouterWireFormParityTest_SignupJsonCodecs.CODECS);
        codecs.putAll(SliceRouterWireFormParityTest_OutJsonCodecs.CODECS);
        return GeneratedJsonCodec.generatedJsonCodec(codecs, JsonCodecAdapter.forMapper(PRODUCTION));
    }

    private static HttpResponseData send(Route<?> route, String path, String body, boolean generated) {
        RouteSource source = () -> Stream.of(route);
        var router = generated
                     ? SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), PRODUCTION, RouteMountMode.pathMode(), codec())
                     : SliceRouter.sliceRouter(source, ErrorMapper.defaultMapper(), PRODUCTION);
        var request = HttpRequestContext.httpRequestContext(path, "POST", Map.of(), Map.of(),
                                                            body.getBytes(StandardCharsets.UTF_8), "req_parity");

        return router.handle(request).await().unwrap();
    }

    private static String text(HttpResponseData response) {
        return response.statusCode() + " " + new String(response.body(), StandardCharsets.UTF_8);
    }

    @Test
    void emptyOptionInResponse_generatedBytesEqualProductionMapper() {
        var route = Route.<Out>post("/out")
                         .withBody(Signup.class)
                         .to(_ -> Promise.success(new Out("1", Option.none())))
                         .as(CommonContentType.APPLICATION_JSON);
        var generated = send(route, "/out", "{\"email\":\"a@b.c\"}", true);

        assertThat(text(generated)).isEqualTo("200 {\"id\":\"1\",\"note\":null}");
        assertThat(text(generated)).isEqualTo(text(send(route, "/out", "{\"email\":\"a@b.c\"}", false)));
    }

    @Test
    void valueObjectBody_bareForm_acceptedForSingleAndListBodies() {
        var single = Route.<Out>post("/one")
                          .withBody(Signup.class)
                          .to(signup -> Promise.success(new Out(signup.email().value(), Option.none())))
                          .as(CommonContentType.APPLICATION_JSON);
        var list = Route.<Out>post("/many")
                        .withBody(new TypeToken<List<Signup>>() {})
                        .to(signups -> Promise.success(new Out(signups.getFirst().email().value(), Option.some("n"))))
                        .as(CommonContentType.APPLICATION_JSON);

        assertThat(text(send(single, "/one", "{\"email\":\"a@b.c\"}", true))).isEqualTo("200 {\"id\":\"a@b.c\",\"note\":null}");
        assertThat(text(send(list, "/many", "[{\"email\":\"a@b.c\"}]", true))).isEqualTo("200 {\"id\":\"a@b.c\",\"note\":\"n\"}");
    }

    @Test
    void refusedValueObject_is400_onGeneratedAndFallbackBodies() {
        var single = Route.<Out>post("/one")
                          .withBody(Signup.class)
                          .to(_ -> Promise.success(new Out("x", Option.none())))
                          .as(CommonContentType.APPLICATION_JSON);
        var list = Route.<Out>post("/many")
                        .withBody(new TypeToken<List<Signup>>() {})
                        .to(_ -> Promise.success(new Out("x", Option.none())))
                        .as(CommonContentType.APPLICATION_JSON);

        assertThat(text(send(single, "/one", "{\"email\":\"nope\"}", true))).startsWith("400 ").contains("not an email: nope");
        assertThat(text(send(list, "/many", "[{\"email\":\"nope\"}]", true))).startsWith("400 ").contains("not an email: nope");
    }

    @Test
    void productionMapper_decodesGeneratedOutput() {
        var signup = new Signup(new Email("a@b.c"));
        var generated = SliceRouterWireFormParityTest_SignupJsonCodecs.SliceRouterWireFormParityTest_SignupJson.CODEC.encode(signup);

        assertThat(new String(generated, StandardCharsets.UTF_8)).isEqualTo(new String(PRODUCTION.writeAsBytes(signup).unwrap(),
                                                                                     StandardCharsets.UTF_8));
        assertThat(PRODUCTION.readBytes(generated, Signup.class).unwrap()).isEqualTo(signup);
    }
}
