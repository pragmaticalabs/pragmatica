package org.pragmatica.aether.forge;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.invoke.InvocationMessage.InvokeResponse;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/**
 * Local slice invoker for demo - routes calls directly to local slice instances
 * without network overhead. All slices run in the same JVM.
 */
public final class LocalSliceInvoker implements SliceInvoker {
    private static final Logger log = LoggerFactory.getLogger(LocalSliceInvoker.class);

    private final Map<Artifact, Slice> slices = new ConcurrentHashMap<>();
    private final Map<String, SliceMethod< ? , ? >> methodCache = new ConcurrentHashMap<>();

    public static LocalSliceInvoker localSliceInvoker() {
        return new LocalSliceInvoker();
    }

    public void register(Artifact artifact, Slice slice) {
        slices.put(artifact, slice);
        for (var method : slice.methods()) {
            var key = artifact.asString() + ":" + method.name()
                                                       .name();
            methodCache.put(key, method);
        }
        log.info("Registered slice: {}", artifact.asString());
    }

    @Override
    public Promise<Unit> invoke(Artifact slice, MethodName method, Object request) {
        return invokeAndWait(slice, method, request, Object.class)
               .map(_ -> unit());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <R> Promise<R> invokeAndWait(Artifact slice, MethodName method, Object request, Class<R> responseType) {
        var key = slice.asString() + ":" + method.name();
        var sliceMethod = (SliceMethod<R, Object>) methodCache.get(key);
        if (sliceMethod == null) {
            log.warn("Slice method not found: {}.{}", slice.asString(), method.name());
            return Causes.cause("Slice method not found: " + slice.asString() + "." + method.name())
                         .promise();
        }
        try{
            return sliceMethod.apply(request);
        } catch (Exception e) {
            log.error("Error invoking {}.{}: {}", slice.asString(), method.name(), e.getMessage());
            return Causes.fromThrowable(e)
                         .promise();
        }
    }

    @Override
    public <R> Promise<R> invokeWithRetry(Artifact slice,
                                          MethodName method,
                                          Object request,
                                          Class<R> responseType,
                                          int maxRetries) {
        // No retry needed for local calls
        return invokeAndWait(slice, method, request, responseType);
    }

    @Override
    public <R> Promise<R> invokeLocal(Artifact slice, MethodName method, Object request, Class<R> responseType) {
        return invokeAndWait(slice, method, request, responseType);
    }

    @Override
    public void onInvokeResponse(InvokeResponse response) {}

    @Override
    public Promise<Unit> stop() {
        slices.clear();
        methodCache.clear();
        return Promise.success(unit());
    }

    @Override
    public int pendingCount() {
        return 0;
    }

    public int sliceCount() {
        return slices.size();
    }

    public Map<Artifact, Slice> slices() {
        return Map.copyOf(slices);
    }
}
