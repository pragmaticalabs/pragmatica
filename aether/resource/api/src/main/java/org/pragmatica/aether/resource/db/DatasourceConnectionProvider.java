// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.ResourceProvisioningError;
import org.pragmatica.config.ConfigService;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;


public interface DatasourceConnectionProvider {
    Promise<SqlConnector> connector(String datasourceName);
    Promise<Unit> release(String datasourceName);
    Promise<Unit> releaseAll();

    /// A connector cached under `cacheKey` and created from the datasource `configSection` (#2100 E-B, N5b/N7).
    ///
    /// The default ignores the key and serves the section, so an implementation that does not override it observes
    /// exactly the one-argument call (every existing fake keyed by name sees today's argument). A production
    /// implementation that forgets to override would share by section; the instance override is pinned by a tripwire.
    default Promise<SqlConnector> connector(String cacheKey, String configSection) {
        return connector(configSection);
    }

    /// Releases the connector cached under `cacheKey` (#2100 E-B, N5b/N7); the default serves the section, see
    /// [#connector(String, String)].
    default Promise<Unit> release(String cacheKey, String configSection) {
        return release(configSection);
    }

    static DatasourceConnectionProvider datasourceConnectionProvider() {
        return new DatasourceConnectionProviderInstance(DatasourceConnectionProvider::loadFromConfigService);
    }

    static DatasourceConnectionProvider datasourceConnectionProvider(Fn2<Result<?>, String, Class<?>> configLoader) {
        return new DatasourceConnectionProviderInstance(configLoader);
    }

    private static Result<?> loadFromConfigService(String section, Class<?> configClass) {
        return ConfigService.instance()
                            .toResult(ResourceProvisioningError.ConfigServiceNotAvailable.INSTANCE)
                            .flatMap(svc -> svc.config(section, configClass));
    }
}

@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"})
class DatasourceConnectionProviderInstance implements DatasourceConnectionProvider {
    private static final Logger log = LoggerFactory.getLogger(DatasourceConnectionProviderInstance.class);

    private static final Cause NO_FACTORY = Causes.cause("No SqlConnector factory found that supports the configuration");

    private final ConcurrentHashMap<String, Promise<SqlConnector>> connectors = new ConcurrentHashMap<>();
    private final Fn2<Result<?>, String, Class<?>> configLoader;
    private final List<ResourceFactory<SqlConnector, DatabaseConnectorConfig>> factories;

    @SuppressWarnings("unchecked")
    DatasourceConnectionProviderInstance(Fn2<Result<?>, String, Class<?>> configLoader) {
        this.configLoader = configLoader;
        var loaded = new ArrayList<ResourceFactory<SqlConnector, DatabaseConnectorConfig>>();

        ServiceLoader.load(ResourceFactory.class)
                     .stream()
                     .map(ServiceLoader.Provider::get)
                     .filter(f -> f.resourceType() == SqlConnector.class)
                     .map(f -> (ResourceFactory<SqlConnector, DatabaseConnectorConfig>) f)
                     .forEach(loaded::add);
        loaded.sort(Comparator.<ResourceFactory<SqlConnector, DatabaseConnectorConfig>> comparingInt(ResourceFactory::priority).reversed());
        this.factories = List.copyOf(loaded);
    }

    @Override
    public Promise<SqlConnector> connector(String datasourceName) {
        return connector(datasourceName, datasourceName);
    }

    @Override
    public Promise<SqlConnector> connector(String cacheKey, String configSection) {
        var fresh = Promise.<SqlConnector> promise();

        return option(connectors.putIfAbsent(cacheKey, fresh)).or(() -> provisionInto(cacheKey, configSection, fresh));
    }

    @Override
    public Promise<Unit> release(String datasourceName) {
        return release(datasourceName, datasourceName);
    }

    @Override
    public Promise<Unit> release(String cacheKey, String configSection) {
        return option(connectors.remove(cacheKey)).map(promise -> promise.flatMap(DatabaseConnector::stop))
                     .or(Promise.success(unit()));
    }

    @Override
    public Promise<Unit> releaseAll() {
        var keys = List.copyOf(connectors.keySet());
        var futures = keys.stream().map(this::release).toList();

        if (futures.isEmpty()) {
            return Promise.success(unit());
        }

        return Promise.allOf(futures).map(_ -> unit());
    }

    /// N5a, FER (degrade forward): the failure still reaches every caller of the failed creation; only the cache entry
    /// is dropped, so the next call for the key creates anew instead of replaying the failed promise.
    ///
    /// The cached promise `fresh` is resolved by [#settle], which on failure removes the entry BEFORE the failure is
    /// published: no caller can observe the failure and then find it cached (an `onFailure` handler on a pending
    /// promise runs on another thread and lost that race). The entry is inserted before creation starts, so a
    /// synchronously failing creation never removes from inside a `ConcurrentHashMap` update. `remove(key, fresh)` never
    /// evicts a newer entry. The `onResult` registration is the whole use of the creation promise: its result is
    /// consumed by `settle`, and every caller, the creating one included, receives `fresh`.
    /// A loader or factory that throws breaks the contract that supplied functions are total, and would leave `fresh`
    /// pending; it is not handled here.
    private Promise<SqlConnector> provisionInto(String cacheKey, String configSection, Promise<SqlConnector> fresh) {
        createConnector(cacheKey, configSection).onResult(result -> settle(cacheKey, fresh, result));

        return fresh;
    }

    private Promise<SqlConnector> settle(String cacheKey, Promise<SqlConnector> fresh, Result<SqlConnector> result) {
        return result.fold(cause -> failEvicted(cacheKey, fresh, cause), fresh::succeed);
    }

    private Promise<SqlConnector> failEvicted(String cacheKey, Promise<SqlConnector> fresh, Cause cause) {
        connectors.remove(cacheKey, fresh);

        return fresh.fail(cause);
    }

    private Promise<SqlConnector> createConnector(String cacheKey, String configSection) {
        log.info("Creating SqlConnector for datasource '{}' from config section '{}', {} factories available",
                 cacheKey,
                 configSection,
                 factories.size());

        return loadConfig(configSection).flatMap(this::selectAndProvision);
    }

    private Promise<DatabaseConnectorConfig> loadConfig(String configSection) {
        var result = configLoader.apply(configSection, DatabaseConnectorConfig.class)
                                 .map(DatabaseConnectorConfig.class::cast);

        result.onSuccess(config -> log.info("Loaded config for '{}': type={}, jdbcUrl={}, asyncUrl={}, r2dbcUrl={}",
                                            configSection,
                                            config.type(),
                                            presence(config.jdbcUrl()),
                                            presence(config.asyncUrl()),
                                            presence(config.r2dbcUrl())))
              .onFailure(cause -> log.error("Failed to load config for section '{}': {}",
                                            configSection,
                                            cause.message()));

        return result.async();
    }

    private static String presence(Option<String> url) {
        return url.map(_ -> "present")
                  .or("absent");
    }

    private Promise<SqlConnector> selectAndProvision(DatabaseConnectorConfig config) {
        for (var factory : factories) {
            log.debug("Checking factory {} (priority {}): supports={}",
                      factory.getClass().getSimpleName(),
                      factory.priority(),
                      factory.supports(config));
            if (factory.supports(config)) {
                // #769: log the resolved effective connection target (post override precedence),
                // not the raw config, so a claim that an override was applied is checkable from
                // the log alone. Credential-free by construction: host/port/database/transport only.
                log.info("Provisioning datasource via {}: host={}, port={}, database={}",
                         factory.getClass().getSimpleName(),
                         config.effectiveHost(),
                         config.effectivePort(),
                         config.effectiveDatabase());

                return factory.provision(config);
            }
        }

        log.error("No factory supports config: type={}, jdbcUrl={}, asyncUrl={}, r2dbcUrl={}",
                  config.type(),
                  config.jdbcUrl(),
                  config.asyncUrl(),
                  config.r2dbcUrl());

        return NO_FACTORY.promise();
    }
}
