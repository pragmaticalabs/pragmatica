package org.pragmatica.aether.cli;

import picocli.CommandLine.ITypeConverter;

/// Case-insensitive converter for OutputFormat enum.
public class OutputFormatConverter implements ITypeConverter<OutputFormat> {
    @Override public OutputFormat convert(String value) {
        return OutputFormat.valueOf(value.toUpperCase());
    }
}
