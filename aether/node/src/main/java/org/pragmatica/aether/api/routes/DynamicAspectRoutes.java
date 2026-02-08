package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.api.DynamicAspectRegistry;
import org.pragmatica.aether.api.ManagementApiResponses.AspectModeSetResponse;
import org.pragmatica.aether.api.ManagementApiResponses.AspectRemovedResponse;
import org.pragmatica.aether.slice.DynamicAspectMode;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;

import java.util.stream.Stream;

import static org.pragmatica.http.routing.PathParameter.aString;

/// Routes for dynamic aspect management: set, remove, list aspect modes.
public final class DynamicAspectRoutes implements RouteSource {
    private final DynamicAspectRegistry aspectManager;

    private DynamicAspectRoutes(DynamicAspectRegistry aspectManager) {
        this.aspectManager = aspectManager;
    }

    public static DynamicAspectRoutes dynamicAspectRoutes(DynamicAspectRegistry aspectManager) {
        return new DynamicAspectRoutes(aspectManager);
    }

    // Request DTO
    record SetAspectRequest(String artifact, String method, String mode) {}

    @Override
    public Stream<Route<?>> routes() {
        return Stream.of(// GET - list all configured aspects
        Route.<Object>get("/api/aspects")
             .toJson(aspectManager::aspectsAsJson),
        // POST - set aspect mode
        Route.<AspectModeSetResponse>post("/api/aspects")
             .withBody(SetAspectRequest.class)
             .toJson(this::handleSetAspect),
        // DELETE - remove aspect config using path parameter
        Route.<AspectRemovedResponse>delete("/api/aspects")
             .withPath(aString())
             .to(this::handleDeleteAspect)
             .asJson());
    }

    private Promise<AspectModeSetResponse> handleSetAspect(SetAspectRequest req) {
        return validateSetRequest(req).async()
                                      .flatMap(valid -> {
                                          var mode = DynamicAspectMode.valueOf(valid.mode());
                                          return aspectManager.setAspectMode(valid.artifact(), valid.method(), mode)
                                                              .map(_ -> new AspectModeSetResponse("aspect_set",
                                                                                                  valid.artifact(),
                                                                                                  valid.method(),
                                                                                                  valid.mode()));
                                      });
    }

    private Result<SetAspectRequest> validateSetRequest(SetAspectRequest req) {
        if (req.artifact() == null || req.artifact()
                                         .isEmpty()) {
            return AspectError.MISSING_FIELDS.result();
        }
        if (req.method() == null || req.method()
                                       .isEmpty()) {
            return AspectError.MISSING_FIELDS.result();
        }
        if (req.mode() == null || req.mode()
                                     .isEmpty()) {
            return AspectError.MISSING_FIELDS.result();
        }
        try {
            DynamicAspectMode.valueOf(req.mode());
        } catch (IllegalArgumentException e) {
            return AspectError.INVALID_MODE.result();
        }
        return Result.success(req);
    }

    private Promise<AspectRemovedResponse> handleDeleteAspect(String key) {
        if (key.isEmpty()) {
            return AspectError.KEY_REQUIRED.promise();
        }
        // Key format: artifactBase/methodName (URL-encoded slash becomes path segments)
        var slashIndex = key.indexOf('/');
        if (slashIndex == -1) {
            return AspectError.KEY_REQUIRED.promise();
        }
        var artifact = key.substring(0, slashIndex);
        var method = key.substring(slashIndex + 1);
        return aspectManager.removeAspect(artifact, method)
                            .map(_ -> new AspectRemovedResponse("aspect_removed", artifact, method));
    }

    private enum AspectError implements Cause {
        MISSING_FIELDS("Missing artifact, method, or mode field"),
        INVALID_MODE("Invalid mode. Must be one of: NONE, LOG, METRICS, LOG_AND_METRICS"),
        KEY_REQUIRED("Aspect key required in format: artifactBase/methodName");
        private final String message;
        AspectError(String message) {
            this.message = message;
        }
        @Override
        public String message() {
            return message;
        }
    }
}
