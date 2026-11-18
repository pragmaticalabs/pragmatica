package org.pragmatica.jbct.cli.skeleton;

import java.nio.charset.CharacterCodingException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;

import org.pragmatica.lang.Cause;


/// A source file that could not be read, with the reason in one line: no stack trace.
public record SourceNotRead(String reason) implements Cause {
    public static Cause sourceNotRead(Throwable failure) {
        return new SourceNotRead(reasonOf(failure));
    }

    private static String reasonOf(Throwable failure) {
        return switch (failure) {
            case NoSuchFileException _ -> "no such file";
            case AccessDeniedException _ -> "access denied";
            case CharacterCodingException _ -> "not valid UTF-8";
            default -> failure.getMessage() == null
                       ? failure.getClass().getSimpleName()
                       : failure.getMessage().lines().findFirst().orElse("");
        };
    }

    @Override
    public String message() {
        return reason;
    }
}
