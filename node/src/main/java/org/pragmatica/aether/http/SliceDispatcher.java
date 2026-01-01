package org.pragmatica.aether.http;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.routing.Route;
import org.pragmatica.lang.Promise;

import java.util.Map;

/**
 * Dispatches HTTP requests to slice methods.
 */
public interface SliceDispatcher {
    /**
     * Dispatch a request to a slice method.
     *
     * @param route           The matched route
     * @param resolvedParams  The resolved binding parameters
     * @return Promise with the response object
     */
    Promise<Object> dispatch(Route route, Map<String, Object> resolvedParams);

    /**
     * Create a SliceDispatcher with the given invoker and artifact resolver.
     */
    static SliceDispatcher sliceDispatcher(SliceInvoker invoker, ArtifactResolver artifactResolver) {
        return new SliceDispatcherImpl(invoker, artifactResolver);
    }

    /**
     * Resolves slice IDs to artifacts.
     */
    @FunctionalInterface
    interface ArtifactResolver {
        Artifact resolve(String sliceId);
    }
}

class SliceDispatcherImpl implements SliceDispatcher {
    private final SliceInvoker invoker;
    private final SliceDispatcher.ArtifactResolver artifactResolver;

    SliceDispatcherImpl(SliceInvoker invoker, SliceDispatcher.ArtifactResolver artifactResolver) {
        this.invoker = invoker;
        this.artifactResolver = artifactResolver;
    }

    @Override
    public Promise<Object> dispatch(Route route, Map<String, Object> resolvedParams) {
        var target = route.target();
        var sliceId = target.sliceId();
        var methodName = target.methodName();
        var artifact = artifactResolver.resolve(sliceId);
        if (artifact == null) {
            return new HttpRouterError.SliceNotFound(sliceId).promise();
        }
        // Build request object from resolved params
        // For simplicity, if there's a single param (body), use it directly
        // Otherwise, we'd need to construct a request object
        Object request;
        if (resolvedParams.size() == 1) {
            request = resolvedParams.values()
                                    .iterator()
                                    .next();
        }else {
            // Empty or multiple params - use the map (empty map is safer than null)
            request = resolvedParams;
        }
        var finalRequest = request;
        return MethodName.methodName(methodName)
                         .async()
                         .flatMap(method -> {
                                      Promise<Object> invokePromise = invoker.invokeLocal(artifact,
                                                                                          method,
                                                                                          finalRequest,
                                                                                          Object.class);
                                      return Promise.promise(promise -> {
                                                                 invokePromise.onSuccess(promise::succeed)
                                                                              .onFailure(cause -> promise.fail(new HttpRouterError.InvocationFailed(sliceId,
                                                                                                                                                    methodName,
                                                                                                                                                    cause)));
                                                             });
                                  });
    }
}
