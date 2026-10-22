/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.pragmatica.json;

import java.util.function.Consumer;

import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.serialization.json.JsonOptions;
import org.pragmatica.serialization.json.NamingStrategy;

import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper.Builder;

import static org.pragmatica.json.JsonError.PathNotFound.pathNotFound;


/// Functional wrapper around Jackson's JsonMapper providing Result-based API.
/// All operations return Result<T> instead of throwing exceptions, enabling
/// seamless composition with other functional constructs.
///
/// Usage:
/// ```java
/// var mapper = JsonMapper.create();
///
/// mapper.writeAsString(user)
///     .onSuccess(json -> logger.info("Serialized: {}", json))
///     .onFailure(cause -> logger.error("Failed: {}", cause));
///
/// mapper.readString(json, User.class)
///     .map(user -> user.withUpdatedTimestamp())
///     .flatMap(userRepo::save);
/// ```
public interface JsonMapper {
    /// Serialize value to JSON string.
    ///
    /// @param value The value to serialize
    /// @param <T>   Value type
    ///
    /// @return Result containing JSON string or error
    <T> Result<String> writeAsString(T value);

    /// Serialize value to JSON bytes.
    ///
    /// @param value The value to serialize
    /// @param <T>   Value type
    ///
    /// @return Result containing JSON bytes or error
    <T> Result<byte[]> writeAsBytes(T value);

    /// Deserialize from JSON string.
    ///
    /// @param json JSON string
    /// @param type Target class
    /// @param <T>  Target type
    ///
    /// @return Result containing deserialized value or error
    <T> Result<T> readString(String json, Class<T> type);

    /// Deserialize from JSON bytes.
    ///
    /// @param json JSON bytes
    /// @param type Target class
    /// @param <T>  Target type
    ///
    /// @return Result containing deserialized value or error
    <T> Result<T> readBytes(byte[] json, Class<T> type);

    /// Deserialize from JSON string using TypeToken.
    ///
    /// @param json      JSON string
    /// @param typeToken Type token for generic types
    /// @param <T>       Target type
    ///
    /// @return Result containing deserialized value or error
    <T> Result<T> readString(String json, TypeToken<T> typeToken);

    /// Deserialize from JSON bytes using TypeToken.
    ///
    /// @param json      JSON bytes
    /// @param typeToken Type token for generic types
    /// @param <T>       Target type
    ///
    /// @return Result containing deserialized value or error
    <T> Result<T> readBytes(byte[] json, TypeToken<T> typeToken);

    /// Parse JSON string into a Jackson tree model.
    ///
    /// @param json JSON string
    ///
    /// @return Result containing JsonNode tree or error
    Result<JsonNode> readTree(String json);

    /// Pretty-print a JSON string with indentation.
    ///
    /// @param json JSON string
    ///
    /// @return Result containing formatted JSON string or error
    Result<String> prettyPrint(String json);

    /// Extract a field value using dot-notation path.
    /// For objects/arrays, returns their JSON representation.
    /// For scalar values, returns the text value.
    ///
    /// @param json    JSON string
    /// @param dotPath Dot-separated path (e.g., "cluster.leaderId")
    ///
    /// @return Result containing the value as a string or error
    default Result<String> extractField(String json, String dotPath) {
        return readTree(json).flatMap(tree -> navigatePath(tree, dotPath));
    }

    /// Navigate a JsonNode tree using a dot-separated path.
    /// Numeric segments are interpreted as array indices when the current node is an array.
    private static Result<String> navigatePath(JsonNode root, String dotPath) {
        var node = root;

        for (var segment : dotPath.split("\\.")) {
            node = navigateSegment(node, segment);
            if (node.isMissingNode()) {
                return pathNotFound(dotPath).result();
            }
        }

        return Result.success(nodeToString(node));
    }

    private static JsonNode navigateSegment(JsonNode node, String segment) {
        if (node.isArray() && segment.matches("\\d+")) {
            return node.path(Integer.parseInt(segment));
        }

        return node.path(segment);
    }

    /// Convert a JsonNode to its string representation.
    private static String nodeToString(JsonNode node) {
        return node.isValueNode()
               ? node.asText()
               : node.toString();
    }

    /// Creates a new JsonMapper builder.
    ///
    /// @return Builder instance
    static JsonMapperBuilder jsonMapper() {
        return new JsonMapperImpl.BuilderImpl();
    }

    /// Creates a JsonMapper with Pragmatica types support enabled.
    ///
    /// @return JsonMapper instance
    static JsonMapper defaultJsonMapper() {
        return jsonMapper().withPragmaticaTypes()
                         .build();
    }

    /// Creates a JsonMapper with Pragmatica types and value objects: a JBCT value object nested in another value
    /// travels as its bare component, as the whole document as the object of that component, and is read back
    /// through its factory ([ValueObjectModule]). Slice HTTP routes use this mapper,
    /// so their Jackson path writes the wire form the generated JSON codecs write.
    ///
    /// @return JsonMapper instance
    static JsonMapper valueObjectJsonMapper() {
        return jsonMapper().withPragmaticaTypes()
                         .withValueObjects()
                         .build();
    }

    /// As [#valueObjectJsonMapper()], naming properties with `naming` and reading and writing under `options`: the
    /// Jackson fallback of a slice's generated route codecs compiled with `naming` and selected with `options`, so a
    /// type the generated codecs do not cover keeps their names and accept/refuse decisions.
    ///
    /// @param naming  the build-time naming strategy
    /// @param options the deploy-time options
    ///
    /// @return JsonMapper instance
    static JsonMapper valueObjectJsonMapper(NamingStrategy naming, JsonOptions options) {
        return jsonMapper().withPragmaticaTypes()
                         .withValueObjects()
                         .withNaming(naming)
                         .withOptions(options)
                         .build();
    }

    /// Builder interface for configuring JsonMapper.
    interface JsonMapperBuilder {
        /// Registers PragmaticaModule for Result/Option serialization.
        ///
        /// @return This builder
        JsonMapperBuilder withPragmaticaTypes();

        /// Registers ValueObjectModule: JBCT value objects travel as their bare component when nested, and as
        /// the object of that component as the whole document; `@JsonName` renames a record component or an enum
        /// constant, as in the generated JSON codecs.
        ///
        /// @return This builder
        JsonMapperBuilder withValueObjects();

        /// Applies deploy-time [JsonOptions], each mapped onto Jackson so this mapper keeps the wire form and the
        /// accept/refuse decisions of generated codecs under the same options (`codec.withOptions(options)`):
        ///
        /// - `nullInclusion` `EXCLUDE`: an empty `Option` property is left out (an as-property inclusion override
        ///   for `Option`, judged by `OptionSerializer#isEmpty`); `INCLUDE` writes `null`, as without options. Map
        ///   values and list elements keep `null`.
        /// - `failOnUnknown`: `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES`.
        /// - `failOnDuplicate`: `StreamReadFeature.STRICT_DUPLICATE_DETECTION`.
        /// - the limits: `StreamReadConstraints` (`maxNestingDepth`, `maxNumberLength`, `maxDocumentLength`, and
        ///   `maxStringLength` for both `maxStringLength` and `maxNameLength`).
        ///
        /// Called again, the last options win.
        ///
        /// @param options The options
        ///
        /// @return This builder
        JsonMapperBuilder withOptions(JsonOptions options);

        /// Names properties with `naming`, the build-time strategy the generated codecs were compiled with
        /// (`-Ajson.naming`), through the Jackson strategy that translates names identically. A property renamed by
        /// `@JsonName` keeps its name (with [#withValueObjects]).
        ///
        /// @param naming The naming strategy
        ///
        /// @return This builder
        JsonMapperBuilder withNaming(NamingStrategy naming);

        /// Configures underlying Jackson JsonMapper via builder.
        ///
        /// @param configurator Configuration function
        ///
        /// @return This builder
        JsonMapperBuilder configure(Consumer<Builder> configurator);

        /// Builds the JsonMapper instance.
        ///
        /// @return JsonMapper instance
        JsonMapper build();
    }

    /// Implementation of JsonMapper interface wrapping Jackson 3.0 JsonMapper.
    record JsonMapperImpl(tools.jackson.databind.json.JsonMapper mapper) implements JsonMapper {
        @Override
        public <T> Result<String> writeAsString(T value) {
            return Result.lift(JsonError::fromException, () -> mapper.writeValueAsString(value));
        }

        @Override
        public <T> Result<byte[]> writeAsBytes(T value) {
            return Result.lift(JsonError::fromException, () -> mapper.writeValueAsBytes(value));
        }

        @Override
        public <T> Result<T> readString(String json, Class<T> type) {
            return Result.lift(JsonError::fromException, () -> mapper.readValue(json, type));
        }

        @Override
        public <T> Result<T> readBytes(byte[] json, Class<T> type) {
            return Result.lift(JsonError::fromException, () -> mapper.readValue(json, type));
        }

        @Override
        public Result<JsonNode> readTree(String json) {
            return Result.lift(JsonError::fromException, () -> mapper.readTree(json));
        }

        @Override
        public Result<String> prettyPrint(String json) {
            return readTree(json).flatMap(tree -> Result.lift(JsonError::fromException,
                                                              () -> mapper.writerWithDefaultPrettyPrinter()
                                                                          .writeValueAsString(tree)));
        }

        @Override
        public <T> Result<T> readString(String json, TypeToken<T> typeToken) {
            return Result.lift(JsonError::fromException, () -> mapper.readValue(json, toTypeReference(typeToken)));
        }

        @Override
        public <T> Result<T> readBytes(byte[] json, TypeToken<T> typeToken) {
            return Result.lift(JsonError::fromException, () -> mapper.readValue(json, toTypeReference(typeToken)));
        }

        /// Converts TypeToken to Jackson TypeReference.
        private static <T> tools.jackson.core.type.TypeReference<T> toTypeReference(TypeToken<T> typeToken) {
            return new tools.jackson.core.type.TypeReference<>() {
                @Override
                public java.lang.reflect.Type getType() {
                    return typeToken.token();
                }
            };
        }

        static final class BuilderImpl implements JsonMapperBuilder {
            private final java.util.List<Consumer<Builder>> configurators = new java.util.ArrayList<>();
            // Stream read constraints belong to the parser factory, which the mapper builder is created from.
            private Option<StreamReadConstraints> constraints = Option.none();

            @Override
            public JsonMapperBuilder withPragmaticaTypes() {
                return configure(builder -> builder.addModule(new PragmaticaModule()));
            }

            @Override
            public JsonMapperBuilder withValueObjects() {
                return configure(builder -> builder.addModule(new ValueObjectModule()));
            }

            @Override
            public JsonMapperBuilder withOptions(JsonOptions options) {
                constraints = Option.some(constraintsOf(options));

                return configure(builder -> applyOptions(builder, options));
            }

            @Override
            public JsonMapperBuilder withNaming(NamingStrategy naming) {
                return configure(builder -> applyNaming(builder, naming));
            }

            private static StreamReadConstraints constraintsOf(JsonOptions options) {
                return StreamReadConstraints.builder()
                                            .maxNestingDepth(options.maxDepth())
                                            .maxStringLength(options.maxStringLength())
                                            .maxNameLength(options.maxStringLength())
                                            .maxNumberLength(options.maxNumberLength())
                                            .maxDocumentLength(options.maxDocumentSize())
                                            .build();
            }

            @Contract
            private static void applyOptions(Builder builder, JsonOptions options) {
                builder.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                                  options.failOnUnknown())
                       .configure(StreamReadFeature.STRICT_DUPLICATE_DETECTION,
                                  options.failOnDuplicate())
                       .withConfigOverride(Option.class,
                                           override -> override.setIncludeAsProperty(optionInclusion(options.nullInclusion())));
            }

            // As-property inclusion: it judges properties declared as Option and leaves an Option map value or list
            // element alone (null, as generated code writes it), where the type's plain inclusion would drop map
            // entries too.
            private static JsonInclude.Value optionInclusion(JsonOptions.NullInclusion inclusion) {
                return switch (inclusion) {
                    case INCLUDE -> JsonInclude.Value.empty();
                    case EXCLUDE -> JsonInclude.Value.construct(JsonInclude.Include.NON_EMPTY,
                                                                JsonInclude.Include.USE_DEFAULTS);
                };
            }

            // AS_DECLARED is Jackson's own default, as JdkHttpClient applies JsonConfig's CAMEL_CASE.
            @Contract
            private static void applyNaming(Builder builder, NamingStrategy naming) {
                switch (naming) {
                    case AS_DECLARED -> {}
                    case SNAKE_CASE -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
                    case KEBAB_CASE -> builder.propertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
                    case PASCAL_CASE -> builder.propertyNamingStrategy(PropertyNamingStrategies.UPPER_CAMEL_CASE);
                }
            }

            @Override
            public JsonMapperBuilder configure(Consumer<Builder> configurator) {
                configurators.add(configurator);

                return this;
            }

            @Override
            public JsonMapper build() {
                var builder = constraints.map(BuilderImpl::constrainedBuilder)
                                         .or(tools.jackson.databind.json.JsonMapper::builder);

                configurators.forEach(c -> c.accept(builder));

                return new JsonMapperImpl(builder.build());
            }

            private static Builder constrainedBuilder(StreamReadConstraints constraints) {
                return tools.jackson.databind.json.JsonMapper.builder(JsonFactory.builder()
                                                                                 .streamReadConstraints(constraints)
                                                                                 .build());
            }
        }
    }
}
