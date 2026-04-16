// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.jbct.slice.model;

import java.util.List;

/// Model for plain interface dependencies that are not slices and not resources.
/// These interfaces have factory methods and are constructed directly.
///
/// @param interfaceLocalName  e.g., "ProcessLoanApplication.KycVerificationStep"
/// @param factoryMethodName   e.g., "kycVerificationStep"
/// @param parameterName       e.g., "kycStep" (from parent factory)
/// @param dependencies        this interface's factory params (leaf deps, recursively resolved)
public record PlainInterfaceModel(String interfaceLocalName,
                                   String factoryMethodName,
                                   String parameterName,
                                   List<DependencyModel> dependencies,
                                   List<MethodModel> annotatedMethods) {
    public PlainInterfaceModel {
        dependencies = List.copyOf(dependencies);
        annotatedMethods = List.copyOf(annotatedMethods);
    }

    public boolean hasAnnotatedMethods() {
        return !annotatedMethods.isEmpty();
    }
}
