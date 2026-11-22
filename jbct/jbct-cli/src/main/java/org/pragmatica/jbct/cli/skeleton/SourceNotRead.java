package org.pragmatica.jbct.cli.skeleton;

import java.nio.charset.CharacterCodingException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;

import org.pragmatica.lang.Cause;

import static org.pragmatica.lang.utils.Causes.cause;


/// A source file that could not be read, with the reason in one line: no stack trace.
public sealed interface SourceNotRead {
    static Cause sourceNotRead(Throwable failure) {
        return cause(reasonOf(failure));
    }

    private static String reasonOf(Throwable failure) {
        return switch (failure) {
            case NoSuchFileException _ -> "no such file";
            case AccessDeniedException _ -> "access denied";
            case CharacterCodingException _ -> "not valid UTF-8";
            default -> described(failure);
        };
    }

    private static String described(Throwable failure) {
        return failure.getMessage() == null
               ? failure.getClass()
                        .getSimpleName()
               : firstLine(failure.getMessage());
    }

    private static String firstLine(String text) {
        return text.lines()
                   .findFirst()
                   .orElse("");
    }

    record unused() implements SourceNotRead {}
}
