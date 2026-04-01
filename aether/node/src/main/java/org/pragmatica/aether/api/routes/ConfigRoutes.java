package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.DynamicConfigManager;
import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.http.security.AuditLog;
import org.pragmatica.aether.node.AetherNode;
import org.pragmatica.aether.api.ManagementApiResponses.ConfigRemovedResponse;
import org.pragmatica.aether.api.ManagementApiResponses.ConfigSetResponse;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;

/// Routes for dynamic configuration management: get, set, remove config overrides.
public final class ConfigRoutes implements RouteSource {
    private final DynamicConfigManager configManager;
    private final Supplier<AetherNode> nodeSupplier;

    private ConfigRoutes(DynamicConfigManager configManager, Supplier<AetherNode> nodeSupplier) {
        this.configManager = configManager;
        this.nodeSupplier = nodeSupplier;
    }

    public static ConfigRoutes configRoutes(DynamicConfigManager configManager, Supplier<AetherNode> nodeSupplier) {
        return new ConfigRoutes(configManager, nodeSupplier);
    }

    // Request DTO
    record SetConfigRequest(String key, String value, Option<String> nodeId){}

    @Override public Stream<Route<?>> routes() {
        return Stream.of(// GET /api/config - merged config (base + overrides)
        Route.<Object>get("/api/config")
             .toJson(configManager::allConfigAsJson),
        // GET /api/config/overrides - only overrides
        Route.<Object>get("/api/config/overrides")
             .toJson(configManager::overridesAsJson),
        // POST /api/config - set config value
        Route.<ConfigSetResponse>post("/api/config")
             .withBody(SetConfigRequest.class)
             .toJson(this::handleSetConfig),
        // DELETE /api/config/{key} - remove cluster-wide config
        Route.<ConfigRemovedResponse>delete("/api/config")
             .withPath(aString())
             .to(this::handleDeleteConfig)
             .asJson(),
        // DELETE /api/config/node/{nodeId}/{key} - remove node-scoped config
        Route.<ConfigRemovedResponse>delete("/api/config/node")
             .withPath(aString(),
                       aString())
             .to(this::handleDeleteNodeConfig)
             .asJson());
    }

    private Promise<ConfigSetResponse> handleSetConfig(SetConfigRequest req) {
        return validateSetRequest(req).async()
                                 .flatMap(this::applySetConfig);
    }

    private Promise<ConfigSetResponse> applySetConfig(SetConfigRequest req) {
        return req.nodeId().filter(id -> !id.isEmpty())
                         .fold(() -> configManager.setConfig(req.key(),
                                                             req.value()).onSuccess(_ -> auditAndEmitConfigSet(req.key(),
                                                                                                               "cluster"))
                                                            .map(_ -> new ConfigSetResponse("config_set",
                                                                                            req.key(),
                                                                                            req.value())),
                               nodeIdStr -> NodeId.nodeId(nodeIdStr).async()
                                                         .flatMap(nodeId -> configManager.setNodeConfig(req.key(),
                                                                                                        req.value(),
                                                                                                        nodeId).onSuccess(_ -> auditAndEmitConfigSet(req.key(),
                                                                                                                                                     "node:" + nodeIdStr))
                                                                                                       .map(_ -> new ConfigSetResponse("config_set",
                                                                                                                                       req.key(),
                                                                                                                                       req.value()))));
    }

    private Result<SetConfigRequest> validateSetRequest(SetConfigRequest req) {
        if ( req.key() == null || req.key().isEmpty()) {
        return ConfigError.MISSING_FIELDS.result();}
        if ( req.value() == null || req.value().isEmpty()) {
        return ConfigError.MISSING_FIELDS.result();}
        return Result.success(req);
    }

    private Promise<ConfigRemovedResponse> handleDeleteConfig(String key) {
        if ( key.isEmpty()) {
        return ConfigError.KEY_REQUIRED.promise();}
        return configManager.removeConfig(key).onSuccess(_ -> auditAndEmitConfigRemove(key, "cluster"))
                                         .map(_ -> new ConfigRemovedResponse("config_removed", key));
    }

    private Promise<ConfigRemovedResponse> handleDeleteNodeConfig(String nodeIdStr, String key) {
        if ( nodeIdStr.isEmpty() || key.isEmpty()) {
        return ConfigError.KEY_REQUIRED.promise();}
        return NodeId.nodeId(nodeIdStr).async()
                            .flatMap(nodeId -> configManager.removeNodeConfig(key, nodeId).onSuccess(_ -> auditAndEmitConfigRemove(key,
                                                                                                                                   "node:" + nodeIdStr))
                                                                             .map(_ -> new ConfigRemovedResponse("config_removed",
                                                                                                                 key)));
    }

    private void auditAndEmitConfigSet(String key, String scope) {
        AuditLog.configSet(key, scope);
        nodeSupplier.get().route(OperationalEvent.ConfigChanged.configChanged(key, scope, "set", "api"));
    }

    private void auditAndEmitConfigRemove(String key, String scope) {
        AuditLog.configRemoved(key, scope);
        nodeSupplier.get().route(OperationalEvent.ConfigChanged.configChanged(key, scope, "remove", "api"));
    }

    private enum ConfigError implements Cause {
        MISSING_FIELDS("Missing key or value field"),
        KEY_REQUIRED("Config key required");
        private final String message;
        ConfigError(String message) {
            this.message = message;
        }
        @Override public String message() {
            return message;
        }
    }
}
