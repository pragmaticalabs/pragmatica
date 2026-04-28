// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.pg.codegen.processor;

import org.pragmatica.aether.pg.parser.PostgresParser.SourceSpan;
import org.pragmatica.aether.pg.schema.validator.ValidationError;
import org.pragmatica.lang.Contract;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.tools.Diagnostic;


public sealed interface ValidationErrorBridge {
    @Contract static void emit(Messager messager, Element element, ValidationError err) {
        messager.printMessage(Diagnostic.Kind.ERROR, formatMessage(err), element);
    }

    static String formatMessage(ValidationError err) {
        var span = err.span();
        return switch (err){
            case ValidationError.TableNotFound e -> ProcessorError.tableNotFoundInQuery(e.tableName(),
                                                                                        line(span),
                                                                                        column(span));
            case ValidationError.ColumnNotFound e -> ProcessorError.columnNotFoundInQuery(e.columnName(),
                                                                                          e.tableName(),
                                                                                          line(span),
                                                                                          column(span));
            case ValidationError.TableOrAliasNotFound e -> ProcessorError.tableOrAliasNotFound(e.name(),
                                                                                               line(span),
                                                                                               column(span));
            case ValidationError.ColumnNotResolved e -> ProcessorError.columnNotResolved(e.columnName(),
                                                                                         line(span),
                                                                                         column(span));
        };
    }

    private static int line(SourceSpan span) {
        return span.start().line();
    }

    private static int column(SourceSpan span) {
        return span.start().column();
    }

    record unused() implements ValidationErrorBridge{}
}
