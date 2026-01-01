package org.pragmatica.aether.slice;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

public record SliceMethod<R, T>(MethodName name,
                                Fn1<Promise<R>, T> method,
                                TypeToken<R> returnType,
                                TypeToken<T> parameterType) implements Fn1<Promise<R>, T> {
    @Override
    public Promise<R> apply(T param1) {
        return method.apply(param1);
    }
}
