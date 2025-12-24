package org.pragmatica.aether.http;

import org.pragmatica.aether.slice.routing.Binding;
import org.pragmatica.aether.slice.routing.BindingSource;
import org.pragmatica.lang.Result;
import org.pragmatica.net.serialization.Deserializer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves route bindings from request context to build method parameters.
 */
public interface BindingResolver {

    /**
     * Resolve all bindings for a route from the request context.
     *
     * @param bindings List of bindings to resolve
     * @param context  The request context
     * @return Map of parameter name to resolved value, or failure
     */
    Result<Map<String, Object>> resolve(List<Binding> bindings, RequestContext context);

    /**
     * Create a BindingResolver with the given deserializer for body parsing.
     */
    static BindingResolver bindingResolver(Deserializer deserializer) {
        return new BindingResolverImpl(deserializer);
    }
}

class BindingResolverImpl implements BindingResolver {

    private final Deserializer deserializer;

    BindingResolverImpl(Deserializer deserializer) {
        this.deserializer = deserializer;
    }

    @Override
    public Result<Map<String, Object>> resolve(List<Binding> bindings, RequestContext context) {
        var result = new HashMap<String, Object>();

        for (var binding : bindings) {
            var valueResult = resolveBinding(binding, context);
            if (valueResult.isFailure()) {
                return valueResult.map(_ -> result);
            }
            valueResult.onSuccess(value -> result.put(binding.param(), value));
        }

        return Result.success(result);
    }

    private Result<Object> resolveBinding(Binding binding, RequestContext context) {
        var source = binding.source();

        return switch (source) {
            case BindingSource.PathVar pv -> context.pathVariable(pv.name())
                    .<Object>map(s -> s)
                    .toResult(new HttpRouterError.BindingFailed(binding.param(), "Path variable not found: " + pv.name()));

            case BindingSource.QueryVar qv -> context.queryParam(qv.name())
                    .<Object>map(s -> s)
                    .toResult(new HttpRouterError.BindingFailed(binding.param(), "Query parameter not found: " + qv.name()));

            case BindingSource.Header h -> context.header(h.name())
                    .<Object>map(s -> s)
                    .toResult(new HttpRouterError.BindingFailed(binding.param(), "Header not found: " + h.name()));

            case BindingSource.Body ignored -> parseBody(context.body());

            case BindingSource.Cookie c -> Result.failure(
                    new HttpRouterError.BindingFailed(binding.param(), "Cookie binding not implemented: " + c.name()));

            case BindingSource.Value ignored -> Result.failure(
                    new HttpRouterError.BindingFailed(binding.param(), "Unsupported binding source for HTTP: value"));

            case BindingSource.Key ignored -> Result.failure(
                    new HttpRouterError.BindingFailed(binding.param(), "Unsupported binding source for HTTP: key"));

            case BindingSource.Metadata ignored -> Result.failure(
                    new HttpRouterError.BindingFailed(binding.param(), "Unsupported binding source for HTTP: metadata"));

            case BindingSource.RequestField ignored -> Result.failure(
                    new HttpRouterError.BindingFailed(binding.param(), "Unsupported binding source for HTTP: request"));
        };
    }

    private Result<Object> parseBody(byte[] body) {
        if (body == null || body.length == 0) {
            return Result.failure(new HttpRouterError.BindingFailed("body", "Request body is empty"));
        }

        try {
            var buf = io.netty.buffer.Unpooled.wrappedBuffer(body);
            return Result.success(deserializer.read(buf));
        } catch (Exception e) {
            return Result.failure(new HttpRouterError.DeserializationFailed(
                    org.pragmatica.lang.utils.Causes.fromThrowable(e)));
        }
    }
}
