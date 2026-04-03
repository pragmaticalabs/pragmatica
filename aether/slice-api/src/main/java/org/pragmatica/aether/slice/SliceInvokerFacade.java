package org.pragmatica.aether.slice;

import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;


/// Facade interface for slice invocation.
/// This is a simplified interface in slice-api that the full SliceInvoker implements.
///
/// Slices use this to call other slices without depending on the node module.
public interface SliceInvokerFacade {
    <R, T> Result<MethodHandle<R, T>> methodHandle(String sliceArtifact,
                                                   String methodName,
                                                   TypeToken<T> requestType,
                                                   TypeToken<R> responseType);
}
