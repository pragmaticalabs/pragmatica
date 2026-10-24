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
        generatedRoutes = Files.readString(generatedFile("TestSliceRoutes.java"));
    }

    private static Path generatedFile(String name) {
        var relative = Paths.get("target", "generated-sources", "annotations", "com", "example", "testslice", name);
        var moduleDir = Paths.get(System.getProperty("user.dir"));
        var candidate = moduleDir.resolve(relative);

        return Files.exists(candidate)
               ? candidate
               : moduleDir.resolve(Path.of("jbct", "slice-processor-tests")).resolve(relative);
    }

    @Test
    void registry_coversJsonBodiesAndResponses_notBinaryOrText() {
        assertThat(TestSliceRoutesJsonCodecs.CODECS).containsKeys(CreateRequest.class, CreateResponse.class, UpdateRequest.class,
                                                                  OrderResponse.class, SearchResult.class);
        assertThat(TestSliceRoutesJsonCodecs.CODECS.keySet()).noneMatch(type -> type == String.class || type == byte[].class);
    }

    @Test
    void factory_passesGeneratedCodecWithJacksonFallback() {
        assertThat(generatedRoutes).contains("jsonMapper, mountMode, jsonCodecs(slice, jsonMapper))");
        assertThat(generatedRoutes).contains("jsonMapper, RouteMountMode.pathMode(), jsonCodecs(slice, jsonMapper))");
        assertThat(generatedRoutes).contains("GeneratedJsonCodec.generatedJsonCodec(TestSliceRoutesJsonCodecs.CODECS,");
        assertThat(generatedRoutes).contains("TestSliceRoutesJsonCodecs.CONTAINERS,");
        // #2378: the given mapper is the fallback as it is under default options; otherwise one built with them.
        assertThat(generatedRoutes).contains("JsonCodecAdapter.forMapper(selection.options().equals(org.pragmatica.serialization.json.JsonOptions.DEFAULTS) ? jsonMapper : ");
    }

    // TestSlice's only List roots are responses (search, getOrders): an encode sees the runtime value, never the
    // declared type, so they get element codecs but no container codec. Body roots keep theirs (wireform signupAll).
    @Test
    void responseOnlyListRoots_getNoContainerCodec() throws IOException {
        var holder = Files.readString(generatedFile("TestSliceRoutesJsonCodecs.java"));

        assertThat(TestSliceRoutesJsonCodecs.CONTAINERS).isEmpty();
        assertThat(holder).contains("CONTAINERS = java.util.Map.ofEntries(\n    );")
                          .doesNotContain("ListRootJson")
                          .doesNotContain("TypeToken<java.util.List<com.example.testslice.SearchResult>>")
                          .doesNotContain("TypeToken<java.util.List<com.example.testslice.OrderResponse>>");
        assertThat(TestSliceRoutesJsonCodecs.CODECS).containsKeys(SearchResult.class, OrderResponse.class);
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
