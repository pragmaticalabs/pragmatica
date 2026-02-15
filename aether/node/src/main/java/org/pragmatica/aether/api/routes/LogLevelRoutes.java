package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.LogLevelRegistry;
import org.pragmatica.aether.api.ManagementApiResponses.LogLevelResetResponse;
import org.pragmatica.aether.api.ManagementApiResponses.LogLevelSetResponse;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.Set;
import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;

/// Routes for runtime log level management: set, reset, list log levels.
public final class LogLevelRoutes implements RouteSource {
    private static final Set<String> VALID_LEVELS = Set.of(
        "TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL", "OFF");

    private final LogLevelRegistry logLevelRegistry;

    private LogLevelRoutes(LogLevelRegistry logLevelRegistry) {
        this.logLevelRegistry = logLevelRegistry;
    }

    public static LogLevelRoutes logLevelRoutes(LogLevelRegistry logLevelRegistry) {
        return new LogLevelRoutes(logLevelRegistry);
    }

    // Request DTO
    record SetLogLevelRequest(String logger, String level) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(
            // GET - list all runtime-configured levels
            Route.<Object>get("/api/logging/levels")
                 .toJson(logLevelRegistry::allLevels),
            // POST - set log level
            Route.<LogLevelSetResponse>post("/api/logging/levels")
                 .withBody(SetLogLevelRequest.class)
                 .toJson(this::handleSetLevel),
            // DELETE - reset logger to config default
            Route.<LogLevelResetResponse>delete("/api/logging/levels")
                 .withPath(aString())
                 .to(this::handleResetLevel)
                 .asJson());
    }

    private Promise<LogLevelSetResponse> handleSetLevel(SetLogLevelRequest req) {
        return validateSetRequest(req).async()
                                      .flatMap(valid -> logLevelRegistry.setLevel(valid.logger(), valid.level().toUpperCase())
                                                                        .map(_ -> new LogLevelSetResponse("level_set",
                                                                                                          valid.logger(),
                                                                                                          valid.level().toUpperCase())));
    }

    private Result<SetLogLevelRequest> validateSetRequest(SetLogLevelRequest req) {
        if (req.logger() == null || req.logger().isEmpty()) {
            return LogLevelError.MISSING_FIELDS.result();
        }
        if (req.level() == null || req.level().isEmpty()) {
            return LogLevelError.MISSING_FIELDS.result();
        }
        if (!VALID_LEVELS.contains(req.level().toUpperCase())) {
            return LogLevelError.INVALID_LEVEL.result();
        }
        return Result.success(req);
    }

    private Promise<LogLevelResetResponse> handleResetLevel(String loggerName) {
        if (loggerName.isEmpty()) {
            return LogLevelError.LOGGER_REQUIRED.promise();
        }
        return logLevelRegistry.resetLevel(loggerName)
                               .map(_ -> new LogLevelResetResponse("level_reset", loggerName));
    }

    private enum LogLevelError implements Cause {
        MISSING_FIELDS("Missing logger or level field"),
        INVALID_LEVEL("Invalid level. Must be one of: TRACE, DEBUG, INFO, WARN, ERROR, FATAL, OFF"),
        LOGGER_REQUIRED("Logger name required");

        private final String message;

        LogLevelError(String message) {
            this.message = message;
        }

        @Override
        public String message() {
            return message;
        }
    }
}
