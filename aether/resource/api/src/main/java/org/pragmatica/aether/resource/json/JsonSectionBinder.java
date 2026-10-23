// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.pragmatica.aether.slice.JsonCodecSelection;
import org.pragmatica.aether.slice.JsonImplementation;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.serialization.json.JsonOptions;

import static org.pragmatica.lang.Option.option;


/// Binds a slice's `[json]` section: the codecs it declares and the one its HTTP routes use.
///
/// ```toml
/// [json]
/// default = "strict"                 # optional when exactly one codec is declared
///
/// [json.codecs.strict]
/// implementation = "generated"       # generated | jackson (required)
/// null_inclusion = "exclude"         # include (default) | exclude
/// fail_on_unknown = true             # default false
/// fail_on_duplicate = true           # default false
/// max_depth = 64                     # the limits default to JsonOptions.DEFAULTS
/// max_string_length = 65536
/// max_number_length = 100
/// max_document_size = 1048576
/// ```
///
/// The keys are read from the slice's configuration provider by prefix, so a codec added through the cluster's
/// KV overlay is seen like one in the slice's `resources.toml`. No key under the section means no `[json]`: the
/// implicit generated codec under [JsonOptions#DEFAULTS]. Any key makes the section present, and a present section
/// is strict: every fault of the read is reported, each naming its key ([JsonSettingsError]). `naming` is refused
/// because names are compiled into the generated codecs ([JsonSettingsError.NamingIsBuildTime]).
public sealed interface JsonSectionBinder {
    String DEFAULT = "default";
    String NAMING = "naming";
    String CODECS = "codecs.";
    String IMPLEMENTATION = "implementation";
    String NULL_INCLUSION = "null_inclusion";
    String FAIL_ON_UNKNOWN = "fail_on_unknown";
    String FAIL_ON_DUPLICATE = "fail_on_duplicate";
    String MAX_DEPTH = "max_depth";
    String MAX_STRING_LENGTH = "max_string_length";
    String MAX_NUMBER_LENGTH = "max_number_length";
    String MAX_DOCUMENT_SIZE = "max_document_size";

    Set<String> CODEC_OPTIONS = Set.of(IMPLEMENTATION,
                                       NULL_INCLUSION,
                                       FAIL_ON_UNKNOWN,
                                       FAIL_ON_DUPLICATE,
                                       MAX_DEPTH,
                                       MAX_STRING_LENGTH,
                                       MAX_NUMBER_LENGTH,
                                       MAX_DOCUMENT_SIZE);

    /// The section as `provider` holds it now.
    static Result<JsonSectionConfig> bind(ConfigurationProvider provider, String section) {
        var prefix = section + ".";
        var keys = provider.keys().stream().filter(key -> key.startsWith(prefix)).sorted().toList();

        return keys.isEmpty()
               ? Result.success(JsonSectionConfig.defaults(section))
               : bindPresent(provider, section, keys);
    }

    private static Result<JsonSectionConfig> bindPresent(ConfigurationProvider provider,
                                                         String section,
                                                         List<String> keys) {
        var faults = new ArrayList<Cause>();
        var codecOptions = new TreeMap<String, Map<String, String>>();
        var defaultName = Option.<String> none();

        for (var key : keys) {
            var relative = key.substring(section.length() + 1);
            var value = provider.getString(key).or("");

            switch (relative) {
                case DEFAULT -> defaultName = Option.some(value);
                case NAMING -> faults.add(new JsonSettingsError.NamingIsBuildTime(key, value));
                default -> codecOption(relative).onPresent(codecAndOption -> codecOptions.computeIfAbsent(codecAndOption[0],
                                                                                                          _ -> new LinkedHashMap<>())
                                                                                         .put(codecAndOption[1], value)).onEmpty(() -> faults.add(new JsonSettingsError.UnknownKey(key)));
            }
        }

        var codecs = new LinkedHashMap<String, JsonCodecSelection>();

        codecOptions.forEach((name, options) -> selection(section, name, options).onSuccess(selection -> codecs.put(name,
                                                                                                                    selection))
                                                         .onFailure(cause -> addAll(faults, cause)));
        var selectedName = selectedName(section, defaultName, codecOptions.keySet());

        selectedName.onFailure(cause -> addAll(faults, cause));

        return faults.isEmpty()
               ? selectedName.map(name -> name.map(codecs::get)
                                              .or(JsonCodecSelection.DEFAULT))
                             .map(selected -> JsonSectionConfig.jsonSectionConfig(section, codecs, selected))
               : refused(faults);
    }

    // `codecs.<name>.<option>`, the name holding no dot and the option a known one.
    private static Option<String[]> codecOption(String relative) {
        if (!relative.startsWith(CODECS)) {
            return Option.none();
        }

        var parts = relative.substring(CODECS.length()).split("\\.", -1);

        return parts.length == 2 && !parts[0].isEmpty() && CODEC_OPTIONS.contains(parts[1])
               ? Option.some(parts)
               : Option.none();
    }

    // The codec in use: `default` when set (it must name a declared codec), the only declared codec, or none for the
    // implicit one; two or more declared without `default` is refused (owner ruling: inferred only from exactly one).
    private static Result<Option<String>> selectedName(String section,
                                                       Option<String> defaultName,
                                                       Set<String> declared) {
        var key = section + "." + DEFAULT;

        return defaultName.fold(() -> inferredName(key, declared), name -> declaredName(key, name, declared));
    }

    private static Result<Option<String>> inferredName(String key, Set<String> declared) {
        return declared.size() > 1
               ? new JsonSettingsError.MissingDefault(key, declared).result()
               : Result.success(Option.from(declared.stream().findFirst()));
    }

    private static Result<Option<String>> declaredName(String key, String name, Set<String> declared) {
        return declared.contains(name)
               ? Result.success(Option.some(name))
               : new JsonSettingsError.UnknownDefaultCodec(key, name, declared).result();
    }

    // Every value is parsed, and the limits are checked over what parsed (the default standing in for a value that
    // did not), so one read reports a malformed value and an out-of-range one together.
    private static Result<JsonCodecSelection> selection(String section, String name, Map<String, String> options) {
        var prefix = section + "." + CODECS + name + ".";
        var defaults = JsonOptions.DEFAULTS;
        var nullInclusion = parsed(prefix,
                                   options,
                                   NULL_INCLUSION,
                                   JsonSectionBinder::nullInclusion,
                                   defaults.nullInclusion());
        var failOnUnknown = parsed(prefix, options, FAIL_ON_UNKNOWN, JsonSectionBinder::bool, defaults.failOnUnknown());
        var failOnDuplicate = parsed(prefix,
                                     options,
                                     FAIL_ON_DUPLICATE,
                                     JsonSectionBinder::bool,
                                     defaults.failOnDuplicate());
        var maxDepth = parsed(prefix, options, MAX_DEPTH, JsonSectionBinder::integer, defaults.maxDepth());
        var maxString = parsed(prefix,
                               options,
                               MAX_STRING_LENGTH,
                               JsonSectionBinder::integer,
                               defaults.maxStringLength());
        var maxNumber = parsed(prefix,
                               options,
                               MAX_NUMBER_LENGTH,
                               JsonSectionBinder::integer,
                               defaults.maxNumberLength());
        var maxDocument = parsed(prefix,
                                 options,
                                 MAX_DOCUMENT_SIZE,
                                 JsonSectionBinder::integer,
                                 defaults.maxDocumentSize());
        var limits = JsonOptions.jsonOptions(nullInclusion.or(defaults.nullInclusion()),
                                             failOnUnknown.or(defaults.failOnUnknown()),
                                             failOnDuplicate.or(defaults.failOnDuplicate()),
                                             maxDepth.or(defaults.maxDepth()),
                                             maxString.or(defaults.maxStringLength()),
                                             maxNumber.or(defaults.maxNumberLength()),
                                             maxDocument.or(defaults.maxDocumentSize()))
                                .mapError(cause -> limitsRefused(prefix, options, cause));

        return Result.all(implementation(prefix, options),
                          Result.allOf(List.of(nullInclusion.mapToUnit(),
                                               failOnUnknown.mapToUnit(),
                                               failOnDuplicate.mapToUnit(),
                                               maxDepth.mapToUnit(),
                                               maxString.mapToUnit(),
                                               maxNumber.mapToUnit(),
                                               maxDocument.mapToUnit())),
                          limits)
                     .map((implementation, _, jsonOptions) -> JsonCodecSelection.jsonCodecSelection(name,
                                                                                                    implementation,
                                                                                                    jsonOptions));
    }

    private static Result<JsonImplementation> implementation(String prefix, Map<String, String> options) {
        var key = prefix + IMPLEMENTATION;

        return option(options.get(IMPLEMENTATION)).fold(() -> new JsonSettingsError.MissingImplementation(key).result(),
                                                        value -> implementationOf(key, value));
    }

    private static Result<JsonImplementation> implementationOf(String key, String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "generated" -> Result.success(JsonImplementation.GENERATED);
            case "jackson" -> Result.success(JsonImplementation.JACKSON);
            default -> new JsonSettingsError.UnknownImplementation(key, value).result();
        };
    }

    @FunctionalInterface
    interface Parser<T> {
        Option<T> parse(String value);
    }

    private static <T> Result<T> parsed(String prefix,
                                        Map<String, String> options,
                                        String option,
                                        Parser<T> parser,
                                        T fallback) {
        return option(options.get(option)).fold(() -> Result.success(fallback),
                                                value -> parser.parse(value)
                                                               .toResult(new JsonSettingsError.InvalidOption(prefix + option,
                                                                                                             value,
                                                                                                             "not a valid " + option)));
    }

    private static Option<JsonOptions.NullInclusion> nullInclusion(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "include" -> Option.some(JsonOptions.NullInclusion.INCLUDE);
            case "exclude" -> Option.some(JsonOptions.NullInclusion.EXCLUDE);
            default -> Option.none();
        };
    }

    private static Option<Boolean> bool(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> Option.some(true);
            case "false" -> Option.some(false);
            default -> Option.none();
        };
    }

    private static Option<Integer> integer(String value) {
        return Result.lift(Causes::fromThrowable,
                           () -> Integer.parseInt(value.trim()))
                     .option();
    }

    // The options' own limit refusals, each restated against its key.
    private static Cause limitsRefused(String prefix, Map<String, String> options, Cause cause) {
        var refused = Causes.composite();

        flatten(cause).forEach(single -> refused.append(single instanceof JsonOptions.InvalidLimit limit
                                                        ? new JsonSettingsError.InvalidOption(prefix + limit.option(),
                                                                                              option(options.get(limit.option())).or(String.valueOf(limit.value())),
                                                                                              limit.message())
                                                        : single));

        return refused;
    }

    private static <T> Result<T> refused(List<Cause> faults) {
        var composite = Causes.composite();

        faults.forEach(composite::append);

        return composite.result();
    }

    private static void addAll(List<Cause> faults, Cause cause) {
        faults.addAll(flatten(cause));
    }

    // Every leaf of a (possibly nested) composite, so the refusal lists each fault once.
    private static List<Cause> flatten(Cause cause) {
        return cause instanceof Causes.CompositeCause composite
               ? composite.stream()
                          .flatMap(inner -> flatten(inner).stream())
                          .toList()
               : List.of(cause);
    }

    record unused() implements JsonSectionBinder {}
}
