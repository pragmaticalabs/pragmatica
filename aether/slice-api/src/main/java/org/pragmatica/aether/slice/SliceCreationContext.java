// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


public interface SliceCreationContext {
    SliceInvokerFacade invoker();
    ResourceProviderFacade resources();
    ConfigFacade config();

    default Option<String> sliceId() {
        return none();
    }

    default Aspect<?> observabilityAspect() {
        return Aspect.identity();
    }

    static SliceCreationContext sliceCreationContext(SliceInvokerFacade invoker, ResourceProviderFacade resources) {
        return DefaultSliceCreationContext.defaultSliceCreationContext(invoker,
                                                                       resources,
                                                                       none(),
                                                                       NoOpConfigFacade.INSTANCE).unwrap();
    }

    static SliceCreationContext sliceCreationContext(SliceInvokerFacade invoker,
                                                     ResourceProviderFacade resources,
                                                     String sliceId) {
        return DefaultSliceCreationContext.defaultSliceCreationContext(invoker,
                                                                       resources,
                                                                       some(sliceId),
                                                                       NoOpConfigFacade.INSTANCE).unwrap();
    }

    static SliceCreationContext sliceCreationContext(SliceInvokerFacade invoker,
                                                     ResourceProviderFacade resources,
                                                     String sliceId,
                                                     ConfigFacade config) {
        return DefaultSliceCreationContext.defaultSliceCreationContext(invoker, resources, some(sliceId), config).unwrap();
    }

    static SliceCreationContext sliceCreationContext(SliceInvokerFacade invoker,
                                                     ResourceProviderFacade resources,
                                                     ConfigFacade config) {
        return DefaultSliceCreationContext.defaultSliceCreationContext(invoker, resources, none(), config).unwrap();
    }
}

record DefaultSliceCreationContext(SliceInvokerFacade invoker,
                                   ResourceProviderFacade resources,
                                   Option<String> sliceId,
                                   ConfigFacade config) implements SliceCreationContext {
    static Result<DefaultSliceCreationContext> defaultSliceCreationContext(SliceInvokerFacade invoker,
                                                                           ResourceProviderFacade resources,
                                                                           Option<String> sliceId,
                                                                           ConfigFacade config) {
        return success(new DefaultSliceCreationContext(invoker, resources, sliceId, config));
    }
}
