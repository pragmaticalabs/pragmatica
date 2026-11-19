// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.List;

import org.pragmatica.aether.slice.ConfigFacade;
import org.pragmatica.config.ConfigService;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.Number;
import org.pragmatica.lang.utils.Causes;

/// The [ConfigFacade] values the slice runtime adapter hands to slices: the no-op facade and the adapter over [ConfigService].
public interface DeploymentConfigFacades {
    ConfigFacade NO_OP_CONFIG = new NoOpDeploymentConfigFacade();

    record NoOpDeploymentConfigFacade() implements ConfigFacade {
        private static final Cause NO_CONFIG = Causes.cause("Config service not available");

        @Override
        public Result<String> requireString(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override
        public Result<Integer> requireInt(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override
        public Result<Long> requireLong(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override
        public Result<Double> requireDouble(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override
        public Result<Boolean> requireBoolean(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override
        public Result<List<String>> requireStringList(String section, String key) {
            return NO_CONFIG.result();
        }

        @Override
        public Option<String> getString(String section, String key) {
            return Option.none();
        }

        @Override
        public Result<Option<Integer>> getInt(String section, String key) {
            return Result.success(Option.none());
        }

        @Override
        public Result<Option<Long>> getLong(String section, String key) {
            return Result.success(Option.none());
        }

        @Override
        public Result<Option<Double>> getDouble(String section, String key) {
            return Result.success(Option.none());
        }

        @Override
        public Result<Option<Boolean>> getBoolean(String section, String key) {
            return Result.success(Option.none());
        }
    }

    @SuppressWarnings("JBCT-UTIL-02")
    static ConfigFacade configServiceToFacade(ConfigService svc) {
        return new ConfigServiceConfigFacade(svc);
    }

    record ConfigServiceConfigFacade(ConfigService delegate) implements ConfigFacade {
        private static final Cause MISSING_KEY = Causes.cause("Required config key not found");

        @Override
        public Result<String> requireString(String section, String key) {
            return delegate.getString(section + "." + key)
                           .toResult(MISSING_KEY);
        }

        @Override
        public Result<Integer> requireInt(String section, String key) {
            return getInt(section, key).flatMap(value -> value.toResult(MISSING_KEY));
        }

        // ConfigService exposes no numeric getters beyond getInt, so the adapter parses the string
        // itself — through core's Result-returning parsers, not Long.parseLong inside a map, which
        // threw NumberFormatException out of a facade whose whole contract is Result (#276 R20).
        // A malformed value is a named failure on require* AND on get* (#1098): the Option-returning
        // get* used to read it as absent, the same as ConfigurationProvider's own getLong/getDouble
        // did for the slice-api facade before #1098.
        @Override
        public Result<Long> requireLong(String section, String key) {
            return getLong(section, key).flatMap(value -> value.toResult(MISSING_KEY));
        }

        @Override
        public Result<Double> requireDouble(String section, String key) {
            return getDouble(section, key).flatMap(value -> value.toResult(MISSING_KEY));
        }

        // The parse failure is mapped at the Result boundary to a cause that NAMES the key and the
        // value; Number.parseX's own cause is a Causes.fromThrowable, whose message is the whole
        // stack trace with the key nowhere in it (review of #1092, SF-2).
        private static final Fn2<Cause, String, String> NOT_A_LONG = Causes.forTwoValues("Config key %s is not a long: \"%s\"");

        private static final Fn2<Cause, String, String> NOT_A_DOUBLE = Causes.forTwoValues("Config key %s is not a double: \"%s\"");

        @Override
        public Result<Boolean> requireBoolean(String section, String key) {
            return getBoolean(section, key).flatMap(value -> value.toResult(MISSING_KEY));
        }

        private static final Cause STRING_LIST_NOT_SUPPORTED = Causes.cause("String list config not supported via legacy ConfigService adapter");

        @Override
        public Result<List<String>> requireStringList(String section, String key) {
            return STRING_LIST_NOT_SUPPORTED.result();
        }

        @Override
        public Option<String> getString(String section, String key) {
            return delegate.getString(section + "." + key);
        }

        @Override
        public Result<Option<Integer>> getInt(String section, String key) {
            return delegate.getInt(section + "." + key);
        }

        @Override
        public Result<Option<Long>> getLong(String section, String key) {
            return parseOptional(section + "." + key, Number::parseLong, NOT_A_LONG);
        }

        @Override
        public Result<Option<Double>> getDouble(String section, String key) {
            return parseOptional(section + "." + key, Number::parseDouble, NOT_A_DOUBLE);
        }

        @Override
        public Result<Option<Boolean>> getBoolean(String section, String key) {
            return delegate.getBoolean(section + "." + key);
        }

        private <T> Result<Option<T>> parseOptional(String fullKey,
                                                    Fn1<Result<T>, String> parser,
                                                    Fn2<Cause, String, String> notParseable) {
            return delegate.getString(fullKey)
                           .fold(() -> Result.success(Option.none()),
                                 value -> parser.apply(value)
                                                .map(Option::some)
                                                .mapError(_ -> notParseable.apply(fullKey, value)));
        }
    }
}
