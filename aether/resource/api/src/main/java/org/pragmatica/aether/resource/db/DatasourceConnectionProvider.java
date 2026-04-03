package org.pragmatica.aether.resource.db;

import org.pragmatica.aether.resource.ResourceFactory;
import org.pragmatica.aether.resource.ResourceProvisioningError;
import org.pragmatica.config.ConfigService;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;


/// Provides SqlConnector instances for named datasources independently of slice lifecycle.
/// Used by schema migration orchestrator and potentially other infrastructure components.
///
/// Connections are lazily created on first request, cached by datasource name,
/// and released on demand or during shutdown.
public interface DatasourceConnectionProvider {
    Promise<SqlConnector> connector(String datasourceName);
    Promise<Unit> release(String datasourceName);
    Promise<Unit> releaseAll();

    static DatasourceConnectionProvider datasourceConnectionProvider() {
        return new DatasourceConnectionProviderInstance(DatasourceConnectionProvider::loadFromConfigService);
    }

    static DatasourceConnectionProvider datasourceConnectionProvider(Fn2<Result<?>, String, Class<?>> configLoader) {
        return new DatasourceConnectionProviderInstance(configLoader);
    }

    private static Result<?> loadFromConfigService(String section, Class<?> configClass) {
        return ConfigService.instance().toResult(ResourceProvisioningError.ConfigServiceNotAvailable.INSTANCE)
                                     .flatMap(svc -> svc.config(section, configClass));
    }
}

@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) class DatasourceConnectionProviderInstance implements DatasourceConnectionProvider {
    private static final Logger log = LoggerFactory.getLogger(DatasourceConnectionProviderInstance.class);

    private static final Cause NO_FACTORY = Causes.cause("No SqlConnector factory found that supports the configuration");

    private final ConcurrentHashMap<String, Promise<SqlConnector>> connectors = new ConcurrentHashMap<>();

    private final Fn2<Result<?>, String, Class<?>> configLoader;
    private final List<ResourceFactory<SqlConnector, DatabaseConnectorConfig>> factories;

    @SuppressWarnings("unchecked") DatasourceConnectionProviderInstance(Fn2<Result<?>, String, Class<?>> configLoader) {
        this.configLoader = configLoader;
        var loaded = new ArrayList<ResourceFactory<SqlConnector, DatabaseConnectorConfig>>();
        ServiceLoader.load(ResourceFactory.class).stream()
                          .map(ServiceLoader.Provider::get)
                          .filter(f -> f.resourceType() == SqlConnector.class)
                          .map(f -> (ResourceFactory<SqlConnector, DatabaseConnectorConfig>) f)
                          .forEach(loaded::add);
        loaded.sort(Comparator.<ResourceFactory<SqlConnector, DatabaseConnectorConfig>>comparingInt(ResourceFactory::priority)
                              .reversed());
        this.factories = List.copyOf(loaded);
    }

    @Override public Promise<SqlConnector> connector(String datasourceName) {
        return connectors.computeIfAbsent(datasourceName, this::createConnector);
    }

    @Override public Promise<Unit> release(String datasourceName) {
        return option(connectors.remove(datasourceName)).map(promise -> promise.flatMap(DatabaseConnector::stop))
                     .or(Promise.success(unit()));
    }

    @Override public Promise<Unit> releaseAll() {
        var keys = List.copyOf(connectors.keySet());
        var futures = keys.stream().map(this::release)
                                 .toList();
        if (futures.isEmpty()) {return Promise.success(unit());}
        return Promise.allOf(futures).map(_ -> unit());
    }

    private Promise<SqlConnector> createConnector(String datasourceName) {
        log.info("Creating SqlConnector for datasource '{}' from config section '{}', {} factories available",
                 datasourceName,
                 datasourceName,
                 factories.size());
        return loadConfig(datasourceName).flatMap(this::selectAndProvision);
    }

    private Promise<DatabaseConnectorConfig> loadConfig(String configSection) {
        var result = configLoader.apply(configSection, DatabaseConnectorConfig.class)
                                       .map(DatabaseConnectorConfig.class::cast);
        result.onSuccess(config -> log.info("Loaded config for '{}': type={}, jdbcUrl={}, asyncUrl={}, r2dbcUrl={}",
                                            configSection,
                                            config.type(),
                                            config.jdbcUrl().isPresent()
                                            ? "present"
                                            : "absent",
                                            config.asyncUrl().isPresent()
                                            ? "present"
                                            : "absent",
                                            config.r2dbcUrl().isPresent()
                                            ? "present"
                                            : "absent"))
        .onFailure(cause -> log.error("Failed to load config for section '{}': {}",
                                      configSection,
                                      cause.message()));
        return result.async();
    }

    private Promise<SqlConnector> selectAndProvision(DatabaseConnectorConfig config) {
        for (var factory : factories) {
            log.debug("Checking factory {} (priority {}): supports={}",
                      factory.getClass().getSimpleName(),
                      factory.priority(),
                      factory.supports(config));
            if (factory.supports(config)) {
                log.info("Selected factory {} for provisioning",
                         factory.getClass().getSimpleName());
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
