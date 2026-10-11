// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.cli;

import picocli.CommandLine.ITypeConverter;


public class OutputFormatConverter implements ITypeConverter<OutputFormat> {
    @Override
    public OutputFormat convert(String value) {
        return OutputFormat.valueOf(value.toUpperCase());
    }
}
