// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
        return ! annotatedMethods.isEmpty();
    }
}
