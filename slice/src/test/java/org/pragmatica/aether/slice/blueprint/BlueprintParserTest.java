package org.pragmatica.aether.slice.blueprint;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.routing.BindingSource;
import org.pragmatica.lang.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class BlueprintParserTest {

    @Nested
    class SuccessCases {

        @Test
        void parse_succeeds_withCompleteValidDsl() {
            var dsl = """
                    # Blueprint: my-app:1.0.0
                    
                    [slices]
                    org.example:user-service:1.0.0 = 2
                    org.example:order-service:1.0.0 = 3
                    org.example:payment-service:1.0.0
                    
                    [routing:http]
                    GET:/api/users/{userId} => user-service:getUser(userId)
                    POST:/api/users => user-service:createUser(body)
                    
                    [routing:kafka]
                    order-events => order-service:handleOrderEvent(value)
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               assertThat(blueprint.id().asString()).isEqualTo("my-app:1.0.0");
                               assertThat(blueprint.slices()).hasSize(3);
                               assertThat(blueprint.routing()).hasSize(2);

                               var userService = blueprint.slices().get(0);
                               assertThat(userService.instances()).isEqualTo(2);
                               assertThat(userService.artifact()
                                                     .asString()).isEqualTo("org.example:user-service:1.0.0");

                               var paymentService = blueprint.slices().get(2);
                               assertThat(paymentService.instances()).isEqualTo(1);

                               var httpRouting = blueprint.routing().get(0);
                               assertThat(httpRouting.protocol()).isEqualTo("http");
                               assertThat(httpRouting.connector()).isEqualTo(Option.none());
                               assertThat(httpRouting.routes()).hasSize(2);

                               var kafkaRouting = blueprint.routing().get(1);
                               assertThat(kafkaRouting.protocol()).isEqualTo("kafka");
                               assertThat(kafkaRouting.routes()).hasSize(1);
                           });
        }

        @Test
        void parse_succeeds_withMinimalDsl() {
            var dsl = """
                    # Blueprint: minimal:1.0.0
                    
                    [slices]
                    org.example:service:1.0.0
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               assertThat(blueprint.id().asString()).isEqualTo("minimal:1.0.0");
                               assertThat(blueprint.slices()).hasSize(1);
                               assertThat(blueprint.routing()).isEmpty();
                           });
        }

        @Test
        void parse_succeeds_withMultipleRoutingSections() {
            var dsl = """
                    # Blueprint: multi-routing:1.0.0
                    
                    [slices]
                    org.example:service:1.0.0
                    
                    [routing:http]
                    GET:/api/test => service:test(body)
                    
                    [routing:kafka]
                    test-events => service:handleEvent(value)
                    
                    [routing:grpc]
                    TestService.Test => service:test(request.id)
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               assertThat(blueprint.routing()).hasSize(3);
                               assertThat(blueprint.routing().get(0).protocol()).isEqualTo("http");
                               assertThat(blueprint.routing().get(1).protocol()).isEqualTo("kafka");
                               assertThat(blueprint.routing().get(2).protocol()).isEqualTo("grpc");
                           });
        }

        @Test
        void parse_succeeds_withCustomConnector() {
            var dsl = """
                    # Blueprint: custom-connector:1.0.0
                    
                    [slices]
                    org.example:service:1.0.0
                    
                    [routing:http:org.aether:custom-http:1.0.0]
                    GET:/legacy/* => service:handleLegacy(body)
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               var routing = blueprint.routing().get(0);
                               assertThat(routing.protocol()).isEqualTo("http");
                               assertThat(routing.connector().isPresent()).isTrue();
                               routing.connector().onPresent(connector ->
                                                                     assertThat(connector.asString()).isEqualTo(
                                                                             "org.aether:custom-http:1.0.0")
                                                            );
                           });
        }

        @Test
        void parse_succeeds_withCommentsAndBlankLines() {
            var dsl = """
                    # Blueprint: with-comments:1.0.0
                    
                    # Slice definitions
                    [slices]
                    org.example:service:1.0.0 = 2  # Two instances
                    
                    # HTTP routing
                    [routing:http]
                    GET:/api/test => service:test(body)  # Test endpoint
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               assertThat(blueprint.slices()).hasSize(1);
                               assertThat(blueprint.routing()).hasSize(1);
                           });
        }

        @Test
        void parse_succeeds_withQueryParameters() {
            var dsl = """
                    # Blueprint: query-params:1.0.0
                    
                    [slices]
                    org.example:service:1.0.0
                    
                    [routing:http]
                    GET:/api/users?{page}&{limit} => service:listUsers(page, limit)
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               var route = blueprint.routing().get(0).routes().get(0);
                               assertThat(route.bindings()).hasSize(2);
                               assertThat(route.bindings().get(0).source()).isInstanceOf(BindingSource.QueryVar.class);
                               assertThat(route.bindings().get(1).source()).isInstanceOf(BindingSource.QueryVar.class);
                           });
        }

        @Test
        void parse_succeeds_withHeaderAndCookieBindings() {
            var dsl = """
                    # Blueprint: bindings:1.0.0
                    
                    [slices]
                    org.example:service:1.0.0
                    
                    [routing:http]
                    POST:/api/orders => service:createOrder(body, header.Authorization, cookie.sessionId)
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               var route = blueprint.routing().get(0).routes().get(0);
                               assertThat(route.bindings()).hasSize(3);
                               assertThat(route.bindings().get(0).source()).isInstanceOf(BindingSource.Body.class);
                               assertThat(route.bindings().get(1).source()).isInstanceOf(BindingSource.Header.class);
                               assertThat(route.bindings().get(2).source()).isInstanceOf(BindingSource.Cookie.class);
                           });
        }

        @Test
        void parseFile_succeeds_withValidFile() throws IOException {
            var tempFile = Files.createTempFile("blueprint", ".dsl");
            try {
                var dsl = """
                        # Blueprint: from-file:1.0.0
                        
                        [slices]
                        org.example:service:1.0.0
                        """;
                Files.writeString(tempFile, dsl);

                BlueprintParser.parseFile(tempFile)
                               .onFailureRun(Assertions::fail)
                               .onSuccess(blueprint -> {
                                   assertThat(blueprint.id().asString()).isEqualTo("from-file:1.0.0");
                                   assertThat(blueprint.slices()).hasSize(1);
                               });
            } finally {
                Files.deleteIfExists(tempFile);
            }
        }
    }

    @Nested
    class FailureCases {

        @Test
        void parse_fails_whenMissingHeader() {
            var dsl = """
                    [slices]
                    org.example:service:1.0.0
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Missing blueprint header")
                                     );
        }

        @Test
        void parse_fails_whenInvalidBlueprintId() {
            var dsl = """
                    # Blueprint: INVALID_ID
                    
                    [slices]
                    org.example:service:1.0.0
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Invalid")
                                     );
        }

        @Test
        void parse_fails_whenInvalidSection() {
            var dsl = """
                    # Blueprint: test:1.0.0
                    
                    [invalid-section]
                    org.example:service:1.0.0
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Invalid section")
                                     );
        }

        @Test
        void parse_fails_whenInvalidSliceFormat() {
            var dsl = """
                    # Blueprint: test:1.0.0
                    
                    [slices]
                    not-an-artifact
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Invalid")
                                     );
        }

        @Test
        void parse_fails_whenInvalidInstanceCount() {
            var dsl = """
                    # Blueprint: test:1.0.0
                    
                    [slices]
                    org.example:service:1.0.0 = -1
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("positive")
                                     );
        }

        @Test
        void parse_fails_whenInvalidRouteFormat() {
            var dsl = """
                    # Blueprint: test:1.0.0
                    
                    [slices]
                    org.example:service:1.0.0
                    
                    [routing:http]
                    invalid-route-format
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Invalid route format")
                                     );
        }

        @Test
        void parse_fails_whenParameterNotInPattern() {
            var dsl = """
                    # Blueprint: test:1.0.0
                    
                    [slices]
                    org.example:service:1.0.0
                    
                    [routing:http]
                    GET:/api/test => service:test(missingParam)
                    """;

            BlueprintParser.parse(dsl)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("not found in pattern")
                                     );
        }

        @Test
        void parseFile_fails_whenFileDoesNotExist() {
            var nonExistentFile = Path.of("/nonexistent/blueprint.dsl");

            BlueprintParser.parseFile(nonExistentFile)
                           .onSuccessRun(Assertions::fail)
                           .onFailure(cause ->
                                              assertThat(cause.message()).contains("Failed to read file")
                                     );
        }
    }

    @Nested
    class BindingResolution {

        @Test
        void resolveBindings_succeeds_withPathVariables() {
            var dsl = """
                    # Blueprint: test:1.0.0
                    
                    [slices]
                    org.example:service:1.0.0
                    
                    [routing:http]
                    GET:/api/users/{userId}/orders/{orderId} => service:getOrder(userId, orderId)
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               var route = blueprint.routing().get(0).routes().get(0);
                               assertThat(route.bindings()).hasSize(2);
                               assertThat(route.bindings().get(0).source()).isInstanceOf(BindingSource.PathVar.class);
                               assertThat(route.bindings().get(1).source()).isInstanceOf(BindingSource.PathVar.class);
                           });
        }

        @Test
        void resolveBindings_succeeds_withKafkaValueAndKey() {
            var dsl = """
                    # Blueprint: test:1.0.0
                    
                    [slices]
                    org.example:service:1.0.0
                    
                    [routing:kafka]
                    test-topic => service:handle(key, value)
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               var route = blueprint.routing().get(0).routes().get(0);
                               assertThat(route.bindings()).hasSize(2);
                               assertThat(route.bindings().get(0).source()).isInstanceOf(BindingSource.Key.class);
                               assertThat(route.bindings().get(1).source()).isInstanceOf(BindingSource.Value.class);
                           });
        }

        @Test
        void resolveBindings_succeeds_withGrpcRequestFields() {
            var dsl = """
                    # Blueprint: test:1.0.0
                    
                    [slices]
                    org.example:service:1.0.0
                    
                    [routing:grpc]
                    UserService.GetUser => service:getUser(request.userId, request.includeDetails)
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               var route = blueprint.routing().get(0).routes().get(0);
                               assertThat(route.bindings()).hasSize(2);
                               assertThat(route.bindings()
                                               .get(0)
                                               .source()).isInstanceOf(BindingSource.RequestField.class);
                               assertThat(route.bindings()
                                               .get(1)
                                               .source()).isInstanceOf(BindingSource.RequestField.class);
                           });
        }

        @Test
        void resolveBindings_succeeds_withMetadata() {
            var dsl = """
                    # Blueprint: test:1.0.0
                    
                    [slices]
                    org.example:service:1.0.0
                    
                    [routing:kafka]
                    test-topic => service:handle(value, metadata.timestamp)
                    """;

            BlueprintParser.parse(dsl)
                           .onFailureRun(Assertions::fail)
                           .onSuccess(blueprint -> {
                               var route = blueprint.routing().get(0).routes().get(0);
                               assertThat(route.bindings()).hasSize(2);
                               assertThat(route.bindings().get(0).source()).isInstanceOf(BindingSource.Value.class);
                               assertThat(route.bindings().get(1).source()).isInstanceOf(BindingSource.Metadata.class);
                           });
        }
    }
}
