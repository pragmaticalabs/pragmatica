// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;


/// Runtime counterpart of the bootstrap reference grammar. Resolves named environment bindings;
/// secrets references use the established AETHER_<UPPERCASE_NAME> environment convention.
/// Missing bindings fail before provider construction; values never enter failure messages.
public interface ConfigReferenceValues {
    Pattern REFERENCE = Pattern.compile("\\$\\{(env|secrets):([^}]+)}");

    static Result<String> resolve(String value) {
        return resolve(value,
                       name -> Option.option(System.getenv(name)));
    }

    static Result<String> resolve(String value, Function<String, Option<String>> environment) {
        var matcher = REFERENCE.matcher(value);
        var result = new StringBuilder();
        var position = 0;

        while (matcher.find()) {
            var name = environmentName(matcher.group(1), matcher.group(2));
            var binding = environment.apply(name).filter(text -> !text.isBlank());

            if (binding.isEmpty()) {
                return Causes.cause("Required configuration environment binding is missing: " + name).result();
            }

            result.append(value, position, matcher.start());
            result.append(binding.or(""));
            position = matcher.end();
        }

        result.append(value, position, value.length());

        return rejectUnresolved(result.toString());
    }

    static Result<Map<String, String>> resolve(Map<String, String> values) {
        return resolve(values,
                       name -> Option.option(System.getenv(name)));
    }

    static Result<Map<String, String>> resolve(Map<String, String> values,
                                               Function<String, Option<String>> environment) {
        return Result.allOf(values.entrySet()
                                  .stream()
                                  .map(entry -> resolve(entry.getValue(),
                                                        environment).map(value -> Map.entry(entry.getKey(),
                                                                                            value)))).map(entries -> entries.stream()
                                                                                                                            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                                                                                                                  Map.Entry::getValue)));
    }

    private static String environmentName(String type, String name) {
        return type.equals("secrets")
               ? "AETHER_" + name.toUpperCase(java.util.Locale.ROOT)
                                 .replace('-', '_')
               : name;
    }

    private static Result<String> rejectUnresolved(String value) {
        return value.contains("${")
               ? Causes.cause("Unresolved configuration reference in provider binding").result()
               : Result.success(value);
    }
}
