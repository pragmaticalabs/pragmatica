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
                                   List<DependencyModel> dependencies) {
    public PlainInterfaceModel {
        dependencies = List.copyOf(dependencies);
    }
}
