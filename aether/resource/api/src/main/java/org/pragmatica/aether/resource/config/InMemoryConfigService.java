// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.config;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.config.toml.TomlParser;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.unitResult;
import static org.pragmatica.lang.Unit.unit;


final class InMemoryConfigService implements ConfigService {
    private final Map<ConfigScope, TomlDocument> documents = new ConcurrentHashMap<>();
    private final Map<String, List<WatchEntry>> watchers = new ConcurrentHashMap<>();

    InMemoryConfigService() {
        documents.put(ConfigScope.GLOBAL, TomlDocument.EMPTY);
        documents.put(ConfigScope.NODE, TomlDocument.EMPTY);
        documents.put(ConfigScope.SLICE, TomlDocument.EMPTY);
    }

    @Override
    public Promise<Option<String>> getString(String section, String key) {
        return Promise.success(getHierarchical(section, key, TomlDocument::getString));
    }

    @Override
    public Promise<Option<String>> getString(ConfigScope scope, String section, String key) {
        return Promise.success(documents.get(scope).getString(section, key));
    }

    @Override
    public Promise<Option<Integer>> getInt(String section, String key) {
        return typedHierarchical(section, key, "integer", TomlDocument::getInt);
    }

    @Override
    public Promise<Option<Integer>> getInt(ConfigScope scope, String section, String key) {
        return typedIn(scope, section, key, "integer", TomlDocument::getInt);
    }

    @Override
    public Promise<Option<Boolean>> getBoolean(String section, String key) {
        return typedHierarchical(section, key, "boolean", TomlDocument::getBoolean);
    }

    @Override
    public Promise<Option<Boolean>> getBoolean(ConfigScope scope, String section, String key) {
        return typedIn(scope, section, key, "boolean", TomlDocument::getBoolean);
    }

    @Override
    public Promise<Option<Double>> getDouble(String section, String key) {
        return typedHierarchical(section, key, "float", TomlDocument::getDouble);
    }

    @Override
    public Promise<Option<Double>> getDouble(ConfigScope scope, String section, String key) {
        return typedIn(scope, section, key, "float", TomlDocument::getDouble);
    }

    @Override
    public Promise<Option<List<String>>> getStringList(String section, String key) {
        return typedHierarchical(section, key, "array of strings", TomlDocument::getStringList);
    }

    @Override
    public Promise<Option<List<String>>> getStringList(ConfigScope scope, String section, String key) {
        return typedIn(scope, section, key, "array of strings", TomlDocument::getStringList);
    }

    @Override
    public Promise<Unit> set(ConfigScope scope, String section, String key, Object value) {
        var current = documents.get(scope);

        documents.put(scope, current.with(section, key, value));
        notifyWatchers(section, key);

        return Promise.success(unit());
    }

    @Override
    public Result<Unit> loadToml(ConfigScope scope, String content) {
        return TomlParser.parse(content)
                         .map(doc -> storeDocument(scope, doc))
                         .mapError(cause -> new ConfigError.ParseFailed("string",
                                                                        cause.message()));
    }

    private Unit storeDocument(ConfigScope scope, TomlDocument doc) {
        documents.put(scope, doc);

        return unit();
    }

    @Override
    public TomlDocument getDocument(ConfigScope scope) {
        return documents.get(scope);
    }

    @Override
    public Promise<ConfigSubscription> watch(String section, String key, Fn1<Unit, Option<String>> callback) {
        var watchKey = watchKey(section, key);
        var entry = new WatchEntry(callback);

        watchers.computeIfAbsent(watchKey, k -> new CopyOnWriteArrayList<>()).add(entry);

        return Promise.success(new WatchSubscription(watchKey, entry));
    }

    // #1455: the typed reads answer Option, and a present-but-wrong-typed value reads as absent. A
    // service query must tell the two apart, so a wrong-typed key fails the read naming key and
    // value. The read goes through a view with its own ledger: the stored documents live as long as
    // the service, and their ledgers would otherwise collect every repeated wrong-typed read.
    private <T> Promise<Option<T>> typedHierarchical(String section,
                                                     String key,
                                                     String expected,
                                                     ValueGetter<T> getter) {
        return firstTyped(0, section, key, expected, getter);
    }

    private <T> Promise<Option<T>> firstTyped(int from,
                                              String section,
                                              String key,
                                              String expected,
                                              ValueGetter<T> getter) {
        return from == HIERARCHY.size()
               ? Promise.success(Option.none())
               : typedIn(HIERARCHY.get(from), section, key, expected, getter).flatMap(found -> found.fold(() -> firstTyped(from + 1,
                                                                                                                           section,
                                                                                                                           key,
                                                                                                                           expected,
                                                                                                                           getter),
                                                                                                          value -> Promise.success(Option.some(value))));
    }

    private <T> Promise<Option<T>> typedIn(ConfigScope scope,
                                           String section,
                                           String key,
                                           String expected,
                                           ValueGetter<T> getter) {
        var view = new TomlDocument(documents.get(scope).sections(),
                                    documents.get(scope).tableArrays());

        return getter.get(view, section, key)
                     .fold(() -> absentOrMistyped(view, section, key, expected),
                           value -> Promise.success(Option.some(value)));
    }

    private static <T> Promise<Option<T>> absentOrMistyped(TomlDocument view,
                                                           String section,
                                                           String key,
                                                           String expected) {
        return view.rawValue(section, key)
                   .fold(() -> Promise.success(Option.none()),
                         raw -> new ConfigError.TypeMismatch(section + "." + key,
                                                             expected,
                                                             String.valueOf(raw)).promise());
    }

    private static final List<ConfigScope> HIERARCHY = List.of(ConfigScope.SLICE, ConfigScope.NODE, ConfigScope.GLOBAL);

    private <T> Option<T> getHierarchical(String section, String key, ValueGetter<T> getter) {
        var sliceValue = getter.get(documents.get(ConfigScope.SLICE), section, key);

        if (sliceValue.isPresent()) {
            return sliceValue;
        }

        var nodeValue = getter.get(documents.get(ConfigScope.NODE), section, key);

        if (nodeValue.isPresent()) {
            return nodeValue;
        }

        return getter.get(documents.get(ConfigScope.GLOBAL), section, key);
    }

    private void notifyWatchers(String section, String key) {
        var watchKey = watchKey(section, key);

        option(watchers.get(watchKey)).onPresent(entries -> notifyEntries(entries, section, key));
    }

    private void notifyEntries(List<WatchEntry> entries, String section, String key) {
        var currentValue = getHierarchical(section, key, TomlDocument::getString);

        entries.stream().filter(WatchEntry::isActive).forEach(entry -> invokeCallback(entry, currentValue));
    }

    private static void invokeCallback(WatchEntry entry, Option<String> value) {
        entry.callback().apply(value);
    }

    private static String watchKey(String section, String key) {
        return section + "." + key;
    }

    @FunctionalInterface
    private interface ValueGetter<T> {
        Option<T> get(TomlDocument doc, String section, String key);
    }

    private record WatchEntry(Fn1<Unit, Option<String>> callback, AtomicBoolean active) {
        WatchEntry(Fn1<Unit, Option<String>> callback) {
            this(callback, new AtomicBoolean(true));
        }

        boolean isActive() {
            return active.get();
        }

        Result<Unit> cancel() {
            active.set(false);

            return unitResult();
        }
    }

    private class WatchSubscription implements ConfigSubscription {
        private final String watchKey;
        private final WatchEntry entry;

        WatchSubscription(String watchKey, WatchEntry entry) {
            this.watchKey = watchKey;
            this.entry = entry;
        }

        @Override
        public Result<Unit> cancel() {
            return entry.cancel()
                        .onSuccess(_ -> removeEntry());
        }

        private void removeEntry() {
            option(watchers.get(watchKey)).onPresent(entries -> entries.remove(entry));
        }

        @Override
        public boolean isActive() {
            return entry.isActive();
        }
    }
}
