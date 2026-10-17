package com.example.testslice;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.pragmatica.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// The slice processor generates reflection-free JSON codecs for every JSON route body and response type
/// (TestSliceRoutesJsonCodecs), wires them into the generated factory, and leaves the rest to Jackson.
class GeneratedRouteJsonCodecTest {
    private static final JsonMapper JACKSON = JsonMapper.defaultJsonMapper();
    private static String generatedRoutes;

    @BeforeAll
    static void readGeneratedRoutes() throws IOException {
        var relative = Paths.get("target", "generated-sources", "annotations", "com", "example", "testslice", "TestSliceRoutes.java");
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var candidate = moduleDir.resolve(relative);

        generatedRoutes = Files.readString(Files.exists(candidate)
                                           ? candidate
                                           : moduleDir.resolve(Path.of("jbct", "slice-processor-tests")).resolve(relative));
    }

    @Test
    void registry_coversJsonBodiesAndResponses_notBinaryOrText() {
        assertThat(TestSliceRoutesJsonCodecs.CODECS).containsKeys(CreateRequest.class, CreateResponse.class, UpdateRequest.class,
                                                                  OrderResponse.class, SearchResult.class);
        assertThat(TestSliceRoutesJsonCodecs.CODECS.keySet()).noneMatch(type -> type == String.class || type == byte[].class);
    }

    @Test
    void factory_passesGeneratedCodecWithJacksonFallback() {
        assertThat(generatedRoutes).contains("jsonMapper, mountMode, jsonCodec(jsonMapper))");
        assertThat(generatedRoutes).contains("jsonMapper, RouteMountMode.pathMode(), jsonCodec(jsonMapper))");
        assertThat(generatedRoutes).contains("GeneratedJsonCodec.generatedJsonCodec(TestSliceRoutesJsonCodecs.CODECS,");
        assertThat(generatedRoutes).contains("TestSliceRoutesJsonCodecs.CONTAINERS,");
        assertThat(generatedRoutes).contains("JsonCodecAdapter.forMapper(jsonMapper)");
    }

    // Owner ruling: the convenience overload uses the mapper the node passes, so a slice behaves the same either way.
    @Test
    void convenienceFactory_defaultsToValueObjectMapper() {
        assertThat(generatedRoutes).contains("return create(slice, JsonMapper.valueObjectJsonMapper());")
                                   .doesNotContain("defaultJsonMapper");
    }

    @Test
    void generatedCodecs_matchJacksonBytes_andDecodeJacksonOutput() {
        var request = new CreateRequest("Widget \"Pro\" é", "a@b.example");
        var response = new CreateResponse(42L, "Widget");
        var order = new OrderResponse(7L, "SHIPPED", 19.99);

        assertSameBytes(TestSliceRoutesJsonCodecs.CreateRequestJson.CODEC.encode(request), request);
        assertSameBytes(TestSliceRoutesJsonCodecs.CreateResponseJson.CODEC.encode(response), response);
        assertSameBytes(TestSliceRoutesJsonCodecs.OrderResponseJson.CODEC.encode(order), order);
        assertThat(TestSliceRoutesJsonCodecs.CreateRequestJson.CODEC.decode(JACKSON.writeAsBytes(request).unwrap()).unwrap()).isEqualTo(request);
        assertThat(TestSliceRoutesJsonCodecs.OrderResponseJson.CODEC.decode(JACKSON.writeAsBytes(order).unwrap()).unwrap()).isEqualTo(order);
    }

    @Test
    void missingRequiredField_isRefused() {
        assertThat(TestSliceRoutesJsonCodecs.CreateRequestJson.CODEC.decode("{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8))
                                                                    .isFailure()).isTrue();
    }

    private static void assertSameBytes(byte[] ours, Object value) {
        assertThat(new String(ours, StandardCharsets.UTF_8)).isEqualTo(new String(JACKSON.writeAsBytes(value).unwrap(), StandardCharsets.UTF_8));
    }
}
